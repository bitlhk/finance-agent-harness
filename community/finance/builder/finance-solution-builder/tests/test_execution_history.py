import hashlib
import json
import uuid
from datetime import datetime, timedelta, timezone
import pytest
from finance_solution_builder.execution_history import ExecutionHistory
from finance_solution_builder.execution_history import observation_view


def test_observation_preflight_not_mistaken_for_capture():
    header = {"observationAssessment": {"schemaVersion": "finance-observation-assessment.v0.1", "status": "READY",
        "items": [{"requirement": {"semanticId": semantic, "obligation": "REQUIRED", "boundary": "HOST",
                                  "fields": ["requestId", "outcome"]},
                   "readiness": "READY", "collectorId": "host", "collectorVersion": "0.1",
                   "descriptor": {"support": "SUPPORTED", "verification": "VERIFIED"}}
                  for semantic in ("execution.history", "model.client.snapshot")]}}
    result = observation_view(header, True)
    assert result['items'][0]['actualCapture'] == 'INCOMPLETE'
    assert result['items'][0]['missingFields'] == ['requestId', 'outcome']
    header['requestId'] = str(uuid.uuid4())
    assert observation_view(header, True, terminal={'status': 'COMPLETED'})['items'][0]['actualCapture'] == 'CAPTURED_METADATA'
    assert result['items'][1]['actualCapture'] == 'NOT_CHECKED'
    assert observation_view(header, False)['items'][0]['actualCapture'] == 'INCOMPLETE'
    assert observation_view({}, True)['status'] == 'LEGACY_UNASSESSED'


@pytest.mark.parametrize('state,expected', [('CAPTURED', 'BODY_NOT_VERIFIED'),
    ('FAILED', 'FAILED'), ('FILTERED', 'FILTERED'), ('TRUNCATED', 'TRUNCATED')])
def test_snapshot_receipt_is_not_body_verification(state, expected):
    header = {'requestId': str(uuid.uuid4()), 'observationAssessment': {'schemaVersion': 'finance-observation-assessment.v0.1',
        'status': 'READY', 'items': [{'requirement': {'semanticId': 'model.client.snapshot'}}]}}
    report = observation_view(header, True, receipts=[{'boundary': 'CLIENT_REQUEST_PREPARED', 'state': state,
        'requestId': header['requestId'], 'callId': str(uuid.uuid4()), 'storage': 'QUEUED'}])
    assert report['items'][0]['actualCapture'] == expected
    assert report['items'][0]['bodyVerification'] == 'NOT_CHECKED'


def test_observation_unexpected_body_never_projected():
    header = {'observationAssessment': {'schemaVersion': 'finance-observation-assessment.v0.1',
        'status': 'READY', 'body': 'PRIVATE CUSTOMER', 'items': [{'requirement': {
            'semanticId': 'PRIVATE CUSTOMER', 'obligation': 'REQUIRED', 'boundary': 'HOST'},
            'descriptor': {'support': 'SUPPORTED', 'body': 'PRIVATE CUSTOMER'}}]}}
    assert 'PRIVATE CUSTOMER' not in json.dumps(observation_view(header, True))


@pytest.mark.parametrize('fields', [[{}], [1], 'requestId', [], ['requestId'] * 65])
def test_corrupt_requirement_fields_are_bounded_unknown_not_server_error(fields):
    for semantic in ('execution.history', 'capability.result'):
        header = {'observationAssessment': {'schemaVersion': 'finance-observation-assessment.v0.1',
            'status': 'READY', 'items': [{'requirement': {'semanticId': semantic,
                'fields': fields, 'capabilityIds': ['balance']}}]}}
        assert observation_view(header, True)['items'][0]['actualCapture'] == 'UNKNOWN'


def test_result_requirement_checks_each_actual_business_result():
    header = {'observationAssessment': {'schemaVersion': 'finance-observation-assessment.v0.1',
        'status': 'READY', 'items': [{'requirement': {'semanticId': 'capability.result',
            'fields': ['capabilityId', 'providerId', 'businessStatus', 'at'], 'capabilityIds': ['balance']}}]}}
    event = {'component': 'capability', 'phase': 'END', 'at': '2026-09-13T00:00:00Z',
             'links': {'capabilityId': 'balance', 'providerId': 'reference'}}
    assert observation_view(header, True)['items'][0]['actualCapture'] == 'NOT_INVOKED'
    report = observation_view(header, True, events=[event])
    assert report['items'][0]['actualCapture'] == 'INCOMPLETE'
    assert report['items'][0]['missingFields'] == ['businessStatus']
    event['links']['businessStatus'] = 'FAILED'
    assert observation_view(header, True, events=[event])['items'][0]['actualCapture'] == 'CAPTURED_METADATA'


def saved(tmp_path, owner="owner-a", terminal=True):
    store = ExecutionHistory(tmp_path)
    directory = store.directory(owner)
    directory.mkdir(parents=True, exist_ok=True)
    identity = str(uuid.uuid4())
    lock = b'{"runtime":{"family":"openjiuwen"},"capabilities":["finance.transaction-summary.query"]}'
    sha = hashlib.sha256(lock).hexdigest()
    records = [{"recordType": "request", "requestId": identity, "buildId": "agent-fixture",
                "ownerRef": hashlib.sha256(owner.encode()).hexdigest(), "createdAt": "2026-09-11T10:00:00Z",
                "serviceLockDigest": sha, "sessionRef": "session-hash", "subjectRef": "subject-hash",
                "processBootId": "boot", "instanceId": "instance"}]
    for n, phase in enumerate(("START", "END") if terminal else ("START",), 1):
        records.append({"schemaVersion": "finance-observation.v0.1", "sequence": n, "spanId": "root",
                        "phase": phase, "component": "host", "operation": "request", "elapsedNanos": n * 100,
                        "durationNanos": 100 if phase == "END" else None, "outcome": "COMPLETED", "links": {},
                        "requestId": identity, "processBootId": "boot", "instanceId": "instance",
                        "at": "2026-09-11T10:00:00Z", "dispatch": "NOT_OBSERVED"})
    if terminal:
        records.append({"recordType": "terminal", "status": "COMPLETED", "complete": True, "droppedEvents": 0})
    (directory / (identity + ".jsonl")).write_text("\n".join(map(json.dumps, records)) + "\n")
    (directory / (identity + ".service-lock.json")).write_bytes(lock)
    return store, directory, identity


def test_owner_isolation_and_pure_read(tmp_path, monkeypatch):
    store, _, identity = saved(tmp_path)
    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **kw: pytest.fail("Historical read must not execute HTTP"))
    assert len(store.list("owner-a")["records"]) == 1
    assert not store.list("owner-b")["records"]
    with pytest.raises(PermissionError):
        store.get("owner-b", identity)
    with pytest.raises(ValueError):
        store.get("owner-a", "../../secret")
    assert store.get("owner-a", identity)["versions"]["service"]["verified"]


def test_interrupted_record_does_not_invent_duration(tmp_path):
    store, directory, identity = saved(tmp_path, terminal=False)
    (directory / (identity + ".recovery.json")).write_text(json.dumps({"recoveredAt": "2026-09-11T12:00:00Z"}))
    result = store.get("owner-a", identity)
    assert result["status"] == "INTERRUPTED"
    assert result["durationNanos"] is None
    assert {"MISSING_TERMINAL_EVENT", "UNCLOSED_SPANS"} <= set(result["gaps"])
    assert result["recoveredAt"] == "2026-09-11T12:00:00Z"


def test_corruption_version_tamper_and_filter(tmp_path):
    store, directory, identity = saved(tmp_path)
    with (directory / (identity + ".jsonl")).open("a") as out:
        out.write('{"partial":')
    (directory / (identity + ".service-lock.json")).write_text('{}')
    result = store.get("owner-a", identity)
    assert not result["complete"]
    assert {"CORRUPT_OR_PARTIAL_RECORD", "SERVICE_LOCK_DIGEST_UNVERIFIED"} <= set(result["gaps"])
    assert len(store.list("owner-a", {"date": "2026-09-11"})["records"]) == 1
    assert not store.list("owner-a", {"date": "2026-09-10"})["records"]
    with pytest.raises(ValueError):
        store.list("owner-a", {"owner": "owner-b"})


def test_symlink_cannot_cross_owner(tmp_path):
    store, directory, identity = saved(tmp_path)
    other = store.directory("owner-b")
    other.parent.mkdir(exist_ok=True)
    try:
        other.symlink_to(directory)
    except OSError as error:
        pytest.skip(f"symbolic links are unavailable in this environment: {error}")
    with pytest.raises(PermissionError):
        store.list("owner-b")


@pytest.mark.parametrize("change", [{"requestId": "other-request"}, {"privateBody": "private-customer-text"},
                                  {"links": {"customer": "private-customer-text"}}, {"elapsedNanos": -1}])
def test_invalid_event_is_not_exposed(tmp_path, change):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + ".jsonl")
    records = [json.loads(line) for line in path.read_text().splitlines()]
    records[1].update(change)
    path.write_text("\n".join(map(json.dumps, records)))
    result = store.get("owner-a", identity)
    assert "INVALID_OR_MISMATCHED_EVENT" in result["gaps"]
    assert "private-customer-text" not in json.dumps(result)
    assert not result["complete"]


def test_task_response_status_is_read_without_accepting_unbounded_links(tmp_path):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + ".jsonl")
    records = [json.loads(line) for line in path.read_text().splitlines()]
    records[2]["links"]["responseStatus"] = "NEED_USER"
    path.write_text("\n".join(map(json.dumps, records)) + "\n")
    result = store.get("owner-a", identity)
    assert result["complete"]
    assert result["spans"][0]["links"]["responseStatus"] == "NEED_USER"

    records[2]["links"]["responseStatus"] = "private customer text"
    path.write_text("\n".join(map(json.dumps, records)) + "\n")
    result = store.get("owner-a", identity)
    assert not result["complete"]
    assert "INVALID_OR_MISMATCHED_EVENT" in result["gaps"]
    assert "private customer text" not in json.dumps(result)


def test_late_event_keeps_gap_visible(tmp_path):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + ".jsonl")
    records = [json.loads(line) for line in path.read_text().splitlines()]
    with path.open("a") as output:
        output.write(json.dumps({**records[1], "sequence": 3}) + "\n")
    assert "LATE_EVENT_AFTER_TERMINAL" in store.get("owner-a", identity)["gaps"]


def test_browser_timings_are_separate_immutable_and_owner_scoped(tmp_path):
    store, _, identity = saved(tmp_path)
    timing = {'firstStatusMs': 1.5, 'bodyDisplayedMs': 250, 'completedMs': 251}
    with pytest.raises(PermissionError):
        store.save_client_timing('owner-b', identity, timing)
    with pytest.raises(ValueError):
        store.save_client_timing('owner-a', identity, {**timing, 'completedMs': float('nan')})
    with pytest.raises(ValueError):
        store.save_client_timing('owner-a', identity, {**timing, 'completedMs': 0})
    store.save_client_timing('owner-a', identity, timing)
    with pytest.raises(ValueError):
        store.save_client_timing('owner-a', identity, timing)
    value = store.get('owner-a', identity)
    assert value['clientTiming']['source'] == 'BROWSER_REPORTED'
    assert value['durationNanos'] == 100
    assert value['spans'][0]['processBootId'] == 'boot'


def history_records(tmp_path, count, *, same_time=False, start=0, store=None):
    store = store or ExecutionHistory(tmp_path)
    directory = store.directory("owner-a")
    directory.mkdir(parents=True, exist_ok=True)
    identities = []
    for index in range(start, start + count):
        identity = str(uuid.UUID(int=index + 1))
        created = datetime(2026, 9, 14, tzinfo=timezone.utc) + timedelta(seconds=0 if same_time else index)
        header = {"recordType": "request", "ownerRef": hashlib.sha256(b"owner-a").hexdigest(),
                  "requestId": identity, "createdAt": created.isoformat(), "buildId": "agent-fixture",
                  "sessionRef": "session-hash", "subjectRef": "subject-hash"}
        terminal = {"recordType": "terminal", "status": "COMPLETED", "complete": True}
        (directory / (identity + ".jsonl")).write_text(json.dumps(header) + "\n" + json.dumps(terminal) + "\n")
        identities.append(identity)
    return store, identities


def test_pagination_default_twenty_legacy_hundred_and_no_thousand_cutoff(tmp_path, monkeypatch):
    store, identities = history_records(tmp_path, 1105)
    calls = []
    original = store.get
    def get(owner, request_id):
        calls.append(request_id)
        return original(owner, request_id)
    monkeypatch.setattr(store, "get", get)
    legacy = store.list("owner-a")
    assert len(legacy["records"]) == 100
    assert legacy["records"][0]["requestId"] == identities[-1]
    assert legacy["truncated"] and legacy["nextCursor"] is None
    calls.clear()
    first = store.list("owner-a", {"cursor": ""})
    assert len(first["records"]) == 20 and len(calls) == 21
    assert first["scanned"] == 1105 and first["coverage"]["scanLimit"] is None
    assert first["coverage"]["detailInspected"] == 21
    assert first["nextCursor"] and first["hasMore"]
    assert first["records"][0]["requestId"] == identities[-1]
    filtered = store.list("owner-a", {"requestId": identities[-1], "limit": "20"})
    assert [r["requestId"] for r in filtered["records"]] == [identities[-1]]
    assert filtered["scanned"] == 1105 and not filtered["hasMore"]


def test_stable_ties_cursor_watermark_and_newer_inserts(tmp_path):
    store, identities = history_records(tmp_path, 45, same_time=True)
    first = store.list("owner-a", {"limit": "7"})
    seen = [r["requestId"] for r in first["records"]]
    assert seen == list(reversed(identities))[:7]
    history_records(tmp_path, 2, same_time=True, start=100, store=store)
    page = first
    while page["nextCursor"]:
        page = store.list("owner-a", {"limit": "7", "cursor": page["nextCursor"]})
        assert page["snapshotWatermark"] == first["snapshotWatermark"]
        seen.extend(r["requestId"] for r in page["records"])
    assert seen == list(reversed(identities))
    assert len(seen) == len(set(seen))
    assert page["coverage"]["consistency"] == "LIVE_KEYSET"
    assert page["coverage"]["backdatedInserts"] == "MAY_APPEAR"


def test_cursor_binds_owner_filters_and_rejects_tamper_and_restart(tmp_path):
    store, _ = history_records(tmp_path, 4)
    filters = {"buildId": "agent-fixture", "date": "2026-09-14", "subjectRef": "subject-hash",
               "sessionRef": "session-hash", "status": "COMPLETED", "requestId": "", "limit": "2"}
    cursor = store.list("owner-a", filters)["nextCursor"]
    for key in ("buildId", "date", "subjectRef", "sessionRef", "status", "requestId"):
        with pytest.raises(ValueError, match="INVALID_HISTORY_CURSOR"):
            store.list("owner-a", {**filters, key: "changed", "cursor": cursor})
    with pytest.raises(ValueError, match="INVALID_HISTORY_CURSOR"):
        store.list("owner-b", {**filters, "cursor": cursor})
    with pytest.raises(ValueError, match="INVALID_HISTORY_CURSOR"):
        store.list("owner-a", {**filters, "cursor": ("A" if cursor[0] != "A" else "B") + cursor[1:]})
    with pytest.raises(ValueError, match="INVALID_HISTORY_CURSOR"):
        ExecutionHistory(tmp_path).list("owner-a", {**filters, "cursor": cursor})
    assert len(store.list("owner-a", {**filters, "cursor": cursor})["records"]) == 2


@pytest.mark.parametrize("limit", ["0", "101", "999", "-1", "2.5", " 20", "020", "1" * 1000, 20, True, None])
def test_invalid_history_limits_rejected(tmp_path, limit):
    with pytest.raises(ValueError, match="INVALID_HISTORY_LIMIT"):
        ExecutionHistory(tmp_path).list("owner-a", {"limit": limit})


def test_empty_paged_owner_has_complete_explicit_coverage(tmp_path):
    result = ExecutionHistory(tmp_path).list("owner-a", {"limit": ""})
    assert result["records"] == [] and result["scanned"] == 0
    assert result["nextCursor"] is None and result["hasMore"] is False
    assert result["coverage"]["dateBasis"] == "UTC"
    assert result["coverage"]["status"] == "COMPLETE"


def test_utc_date_normalization_and_microsecond_tie(tmp_path):
    store, identities = history_records(tmp_path, 3)
    dates = ["2026-09-15T00:30:00+08:00", "2026-09-14T16:30:00.0000001Z", "2026-09-14T16:30:00.0000002Z"]
    for identity, created in zip(identities, dates):
        path = store.directory("owner-a") / (identity + ".jsonl")
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[0]["createdAt"] = created
        path.write_text("\n".join(map(json.dumps, rows)))
    result = store.list("owner-a", {"date": "2026-09-14", "limit": "20"})
    assert [r["requestId"] for r in result["records"]] == list(reversed(identities))
    assert not store.list("owner-a", {"date": "2026-09-15"})["records"]


def test_explicit_half_open_range_supports_beijing_day_without_changing_legacy_date(tmp_path):
    store, identities = history_records(tmp_path, 3)
    dates = ["2026-09-14T15:59:59.999999Z", "2026-09-14T16:00:00Z", "2026-09-15T15:59:59.999999Z"]
    for identity, created in zip(identities, dates):
        path = store.directory("owner-a") / (identity + ".jsonl")
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[0]["createdAt"] = created
        path.write_text("\n".join(map(json.dumps, rows)))
    result = store.list("owner-a", {"createdFrom": "2026-09-15T00:00:00+08:00",
                                     "createdBefore": "2026-09-16T00:00:00+08:00", "limit": "20"})
    assert [row["requestId"] for row in result["records"]] == list(reversed(identities[1:]))
    assert result["coverage"]["rangeSemantics"] == "HALF_OPEN"
    assert [row["requestId"] for row in store.list("owner-a", {"date": "2026-09-14"})["records"]] == list(reversed(identities[:2]))


@pytest.mark.parametrize("filters", [
    {"createdFrom": "2026-09-15T00:00:00Z"},
    {"createdBefore": "2026-09-16T00:00:00Z"},
    {"createdFrom": "invalid", "createdBefore": "2026-09-16T00:00:00Z"},
    {"createdFrom": "2026-09-16T00:00:00Z", "createdBefore": "2026-09-15T00:00:00Z"},
])
def test_invalid_explicit_time_ranges_rejected(tmp_path, filters):
    with pytest.raises(ValueError, match="INVALID_HISTORY_TIME_RANGE"):
        ExecutionHistory(tmp_path).list("owner-a", filters)


def test_explicit_time_range_is_bound_to_every_cursor_page(tmp_path):
    store, identities = history_records(tmp_path, 25)
    filters = {"createdFrom": "2026-09-14T00:00:00Z", "createdBefore": "2026-09-15T00:00:00Z", "limit": "10"}
    first = store.list("owner-a", filters)
    second = store.list("owner-a", {**filters, "cursor": first["nextCursor"]})
    third = store.list("owner-a", {**filters, "cursor": second["nextCursor"]})
    assert [row["requestId"] for page in (first, second, third) for row in page["records"]] == list(reversed(identities))
    with pytest.raises(ValueError, match="INVALID_HISTORY_CURSOR"):
        store.list("owner-a", {**filters, "createdBefore": "2026-09-16T00:00:00Z", "cursor": first["nextCursor"]})


def test_unreadable_and_over_limit_records_are_reported_not_silently_omitted(tmp_path):
    store, identities = history_records(tmp_path, 3)
    directory = store.directory("owner-a")
    (directory / (identities[-1] + ".jsonl")).write_bytes(b"x" * (1_048_576 + 1))
    (directory / (identities[-2] + ".jsonl")).write_text("not JSON")
    result = store.list("owner-a", {"limit": "20"})
    assert result["scanned"] == 3 and result["unreadable"] == 2
    assert result["coverage"]["status"] == "PARTIAL"
    assert [r["requestId"] for r in result["records"]] == identities[:1]


def test_changed_filters_between_metadata_and_detail_do_not_leak_results(tmp_path, monkeypatch):
    store, identities = history_records(tmp_path, 3)
    original = store.get
    def get(owner, request_id):
        result = original(owner, request_id)
        if request_id == identities[-1]: result["status"] = "DENIED"
        return result
    monkeypatch.setattr(store, "get", get)
    page = store.list("owner-a", {"limit": "2", "status": "COMPLETED"})
    assert [r["requestId"] for r in page["records"]] == list(reversed(identities[:-1]))
    assert page["coverage"]["changedDuringRead"] == 1
    assert page["coverage"]["status"] == "PARTIAL"


def test_business_results_are_independent_and_only_from_valid_capability_end(tmp_path):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + ".jsonl")
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    template = rows[2]
    rows[2:2] = [dict(template, sequence=3, spanId="cap", component="capability", phase="START",
                      links={"capabilityId": "balance", "businessStatus": "PENDING"}),
                 dict(template, sequence=4, spanId="cap", component="capability",
                      links={"capabilityId": "balance", "businessStatus": "DENIED"}),
                 dict(template, sequence=5, spanId="http", component="http",
                      links={"capabilityId": "balance", "businessStatus": "COMPLETED", "httpStatus": "200"}),
                 dict(template, sequence=6, spanId="forged", component="capability", requestId="other",
                      links={"capabilityId": "balance", "businessStatus": "COMPLETED"})]
    path.write_text("\n".join(map(json.dumps, rows)))
    value = store.get("owner-a", identity)
    assert value["status"] == "COMPLETED"
    assert value["businessResults"] == [{"capabilityId": "balance", "businessStatus": "DENIED"}]
    assert store.list("owner-a", {"limit": "20"})["records"][0]["businessResults"] == value["businessResults"]


def test_span_end_index_preserves_first_end_semantics(tmp_path):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + ".jsonl")
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    rows.insert(-1, dict(rows[2], sequence=3, durationNanos=999, outcome="ERROR"))
    path.write_text("\n".join(map(json.dumps, rows)))
    span, = store.get("owner-a", identity)["spans"]
    assert span["durationNanos"] == 100 and span["outcome"] == "COMPLETED"


@pytest.mark.parametrize("parents,duplicate,expected", [
    ({"root": "root"}, False, "INVALID_SPAN_HIERARCHY"),
    ({"root": "child", "child": "root"}, False, "INVALID_SPAN_HIERARCHY"),
    ({"root": "a", "a": "b", "b": "root"}, False, "INVALID_SPAN_HIERARCHY"),
    ({"root": None}, True, "DUPLICATE_SPAN_START"),
])
def test_invalid_span_hierarchy_is_explicit_without_discarding_events(tmp_path, parents, duplicate, expected):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + ".jsonl")
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    events = []
    for span, parent in parents.items():
        events.append(dict(rows[1], spanId=span, parentSpanId=parent, sequence=len(events) + 1))
    if duplicate: events.append(dict(events[0], sequence=len(events) + 1))
    for span in reversed(parents):
        events.append(dict(rows[2], spanId=span, parentSpanId=parents[span], sequence=len(events) + 1))
    path.write_text("\n".join(map(json.dumps, [rows[0], *events, rows[-1]])))
    value = store.get("owner-a", identity)
    assert expected in value["gaps"] and not value["complete"]
    assert value["events"] == events
    assert all(span["durationNanos"] == 100 for span in value["spans"])


def test_deep_valid_span_chain_does_not_require_recursion(tmp_path):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + ".jsonl")
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    events = []
    for index in range(1050):
        events.append(dict(rows[1], spanId=str(index), parentSpanId=str(index + 1) if index < 1049 else None,
                           sequence=len(events) + 1))
    for index in range(1049, -1, -1):
        events.append(dict(rows[2], spanId=str(index), parentSpanId=str(index + 1) if index < 1049 else None,
                           sequence=len(events) + 1))
    path.write_text("\n".join(map(json.dumps, [rows[0], *events, rows[-1]])))
    assert path.stat().st_size <= 1_048_576
    value = store.get("owner-a", identity)
    assert value["complete"] is True
    assert len(value["spans"]) == 1050
