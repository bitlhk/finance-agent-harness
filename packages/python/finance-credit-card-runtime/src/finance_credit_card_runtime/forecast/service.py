"""预测回执、固定容量队列和触达前快照复核。"""

from datetime import timedelta
from ..contracts import RuntimeView, BusinessError, digest
from .features import extract
from .models import ForecastProtocol, ForecastReceipt, HorizonForecast


def predict(view, predictor, *, method="baseline", business_now=None, protocol=None):
    view = RuntimeView.model_validate(view.model_dump())
    protocol = protocol or (
        ForecastProtocol.model_validate(predictor.manifest["protocol"])
        if predictor
        else ForecastProtocol()
    )
    if method not in ("model", "baseline"):
        raise BusinessError("invalid-method")
    reason = None
    status = None
    if business_now is not None and (
        business_now < view.case.observed_at
        or business_now >= view.case.observed_at + timedelta(days=1)
    ):
        status = "stale"
        reason = "business-clock-outside-validity"
    elif predictor is None:
        status = "untrained"
        reason = "model-not-bound"
    elif predictor.manifest["dataset_version"] != view.dataset_version or predictor.manifest[
        "protocol_digest"
    ] != digest(protocol):
        status = "failed"
        reason = "prediction-binding-mismatch"
    else:
        try:
            if not any(
                f.kind == "balance"
                and f.event_at <= view.case.observed_at - timedelta(days=protocol.history_days)
                for f in view.facts
            ):
                raise BusinessError("insufficient-history")
            result = predictor.predict(extract(view), method)
        except BusinessError as exc:
            status = (
                "insufficient-data"
                if exc.code in ("insufficient-data", "insufficient-history")
                else "failed"
            )
            reason = exc.code
    if status:
        result = tuple(
            HorizonForecast(horizon_days=h, status=status, reason=reason) for h in (1, 3, 7)
        )
    values = {
        "dataset_version": view.dataset_version,
        "case_id": view.case.case_id,
        "customer_id": view.case.customer_id,
        "group_id": view.case.group_id,
        "snapshot_revision": view.revision,
        "input_digest": digest(view),
        "model_digest": predictor.version if predictor else "0" * 64,
        "protocol_digest": digest(protocol),
        "observed_at": view.case.observed_at,
        "expires_at": view.case.observed_at + timedelta(days=1),
        "method": method,
        "results": result,
        "evidence_refs": tuple(f.record_id for f in view.facts),
    }
    # Use validated model JSON for deterministic date/result serialization.
    receipt = ForecastReceipt(receipt_id="0" * 64, **values)
    return receipt.model_copy(
        update={"receipt_id": digest(receipt.model_dump(mode="json", exclude={"receipt_id"}))}
    )


def checked_receipt(receipt):
    receipt = ForecastReceipt.model_validate(receipt.model_dump())
    if receipt.receipt_id != digest(receipt.model_dump(mode="json", exclude={"receipt_id"})):
        raise BusinessError("receipt-integrity-mismatch")
    return receipt


def recheck(old, view, predictor, *, business_now):
    old = checked_receipt(old)
    view = RuntimeView.model_validate(view.model_dump())
    if (old.dataset_version, old.case_id, old.customer_id, old.group_id) != (
        view.dataset_version,
        view.case.case_id,
        view.case.customer_id,
        view.case.group_id,
    ):
        raise BusinessError("recheck-subject-mismatch")
    if (
        old.input_digest == digest(view)
        and predictor
        and old.model_digest == predictor.version
        and old.observed_at <= business_now < old.expires_at
    ):
        return {
            "status": "unchanged",
            "old_receipt_id": old.receipt_id,
            "receipt": old.model_dump(mode="json"),
        }
    new = predict(view, predictor, method=old.method, business_now=business_now)
    threshold = predictor.manifest["protocol"]["probability_threshold"] if predictor else 0.2
    available = [r for r in new.results if r.status == "available"]
    state = (
        "unavailable"
        if not available
        else "withdrawn"
        if all(r.shortage_probability < threshold for r in available)
        else "recomputed"
    )
    return {
        "status": state,
        "old_receipt_id": old.receipt_id,
        "receipt": new.model_dump(mode="json"),
    }


def queue(receipts, *, horizon_days=7, k=20):
    if k < 1 or horizon_days not in (1, 3, 7):
        raise BusinessError("invalid-queue-policy")
    chosen = {}
    cohort = None
    for receipt in receipts:
        receipt = checked_receipt(receipt)
        identity = (
            receipt.dataset_version,
            receipt.observed_at,
            receipt.model_digest,
            receipt.protocol_digest,
            receipt.method,
        )
        if cohort is not None and identity != cohort:
            raise BusinessError("mixed-queue-cohort")
        cohort = identity
        result = next(r for r in receipt.results if r.horizon_days == horizon_days)
        if result.status != "available":
            continue
        key = (receipt.customer_id, receipt.observed_at)
        if key in chosen and chosen[key].input_digest != receipt.input_digest:
            raise BusinessError("conflicting-customer-observation")
        chosen[key] = receipt
    return sorted(
        chosen.values(),
        key=lambda r: (
            -next(x.shortage_probability for x in r.results if x.horizon_days == horizon_days),
            r.customer_id,
        ),
    )[:k]
