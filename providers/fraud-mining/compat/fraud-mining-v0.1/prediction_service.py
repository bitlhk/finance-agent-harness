"""Compatibility prediction service for fraud-mining v0.1.

The upstream endpoint sent raw transaction fields directly to Xihe, although the
checkpoint consumes an engineered transaction sequence. It also converted every
model error into pred_label=0 and score=0.0. This adapter assembles the expected
features and keeps model failures explicit.
"""

import math

from sqlalchemy.orm import Session

from app.mining.feature_engineering import AMOUNT_BINS, FeatureEngineering, get_bin_index
from app.mining.model_service import get_xihe_client
from app.models.sample import Sample
from app.utils.exceptions import AnalysisError, NotFoundError, ValidationError


xihe_client = get_xihe_client()

MODEL_FIELDS = (
    "amount",
    "record_type",
    "balance_bin",
    "drcrf",
    "hour_bucket_3h",
    "weekday",
    "delta_balance_sign",
    "abs_delta_balance_bin",
    "drain_flag",
    "is_new_counterparty",
    "servface_hash",
    "behavior_hash",
    "counterparty_hash",
    "w24h_event_count_bin",
    "w24h_txn_count_bin",
    "w24h_behavior_count_bin",
    "w24h_txn_amount_sum_bin",
    "w24h_outflow_amount_bin",
    "w24h_inflow_amount_bin",
    "w24h_netflow_sign",
    "w24h_netflow_abs_bin",
    "w24h_outflow_ratio_amt_bin",
    "w24h_outflow_ratio_cnt_bin",
    "w24h_abs_delta_balance_mean_bin",
    "w24h_drain_intent_ratio_bin",
    "w24h_channel_switch_count_bin",
    "w24h_cp_switch_rate_bin",
    "w24h_unique_cp_count_bin",
    "w24h_top1_cp_amount_share_bin",
    "w24h_new_cp_ratio_bin",
    "w24h_beh_to_outflow_count_bin",
    "w24h_beh_to_inflow_count_bin",
)


def _sample_dict(sample):
    if isinstance(sample, dict):
        return sample
    return {
        "cino": sample.cino,
        "timestamp": str(sample.timestamp) if sample.timestamp else "",
        "amount": float(sample.amount) if sample.amount is not None else 0,
        "balance": float(sample.balance) if sample.balance is not None else 0,
        "drcrf": sample.drcrf,
        "cashnote": sample.cashnote,
        "record_type": sample.record_type,
        "servface": sample.servface,
        "behavior": sample.behavior,
        "counterparty": sample.counterparty,
        "feature": sample.feature,
        "label": sample.label if sample.label is not None else 0,
    }


def _missing(value):
    if value is None:
        return True
    try:
        scalar = value.item() if hasattr(value, "item") else value
        return isinstance(scalar, float) and not math.isfinite(scalar)
    except (TypeError, ValueError):
        return False


def _json_scalar(value, default=-1):
    if _missing(value):
        return default
    scalar = value.item() if hasattr(value, "item") else value
    return scalar


def _engineered_rows(samples):
    feature_engineering = FeatureEngineering()
    rows = []
    feature_rows = []
    for sample in samples:
        sequence = feature_engineering.compute_features_with_history(_sample_dict(sample))
        if not sequence:
            raise ValidationError("无法形成模型所需的交易序列")
        for row in sequence:
            feature = row.get("feature")
            if feature:
                feature_rows.append({"cino": row.get("cino"), "feature": feature})

            # Xihe's amount field is categorical in this checkpoint. A single raw
            # transaction otherwise becomes an out-of-range embedding index.
            amount = row.get("amount")
            row["amount"] = get_bin_index(amount, AMOUNT_BINS) if not _missing(amount) else -1
            model_row = {
                "cino": str(_json_scalar(row.get("cino"), "")),
                "timestamp": str(_json_scalar(row.get("timestamp"), "")),
            }
            for field in MODEL_FIELDS:
                model_row[field] = _json_scalar(row.get(field))
            rows.append(model_row)
    return rows, feature_rows


def _predict(samples):
    rows, features = _engineered_rows(samples)
    result = xihe_client.predict_with_embedding(
        data=rows,
        features=features if features else None,
    )
    if not result.get("success") or not result.get("results"):
        message = result.get("message") or "羲和模型未返回预测结果"
        raise AnalysisError(message)
    return result["results"]


def predict_sample(db: Session, body: dict) -> dict:
    """Predict one sample without converting a model outage into a normal label."""
    sample_id = body.get("sample_id")
    sample_data = body.get("sample_data")
    model_version = body.get("model_version", "羲和模型v1.0")

    if sample_id:
        sample = db.query(Sample).filter(Sample.id == sample_id).first()
        if not sample:
            raise NotFoundError("样本", sample_id)
    elif sample_data:
        sample = None
    else:
        raise ValidationError("请提供 sample_id 或 sample_data")

    source = sample if sample is not None else sample_data
    result = _predict([source])[0]

    if sample is not None:
        sample.prediction_result = "fraud" if result["pred_label"] == 1 else "normal"
        sample.prediction_score = result["score"]
        db.commit()

    return {
        "sample_id": sample_id,
        "cino": result.get("cino", _sample_dict(source).get("cino")),
        "pred_label": result["pred_label"],
        "score": result["score"],
        "model_version": model_version,
    }


def batch_predict_samples(db: Session, sample_ids: list) -> dict:
    samples = db.query(Sample).filter(Sample.id.in_(sample_ids)).all()
    if not samples:
        return {"total": 0, "results": []}

    results = _predict(samples)
    by_cino = {str(result.get("cino")): result for result in results}
    mapped = []
    for sample in samples:
        result = by_cino.get(str(sample.cino))
        if result is None:
            raise AnalysisError(f"羲和模型未返回账号 {sample.cino} 的预测结果")
        sample.prediction_result = "fraud" if result["pred_label"] == 1 else "normal"
        sample.prediction_score = result["score"]
        mapped.append(result)
    db.commit()
    return {"total": len(mapped), "results": mapped}
