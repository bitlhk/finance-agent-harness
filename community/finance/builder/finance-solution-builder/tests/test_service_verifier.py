"""Negative controls for the actual acceptance predicates, not only valid fixtures."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("local_verifier", Path(__file__).resolve().parents[1] / "engineering/verify_local_service.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def test_403_cannot_hide_business_read_in_full_simulator():
    fixture = SimpleNamespace(reads=[])
    recorder = SimpleNamespace(calls=[])
    before = verifier.business_calls(fixture, recorder)
    recorder.calls.append({"identity": False, "method": "GET", "route": "/api/v1/accounts", "permitted": True})
    code = 403
    assert not (code == 403 and verifier.business_calls(fixture, recorder) == before)


def test_identity_introspection_is_not_business_read():
    fixture = SimpleNamespace(reads=[])
    recorder = SimpleNamespace(calls=[{"identity": True, "method": "GET", "route": "/api/v1/sessions/current"}])
    assert verifier.business_calls(fixture, recorder) == []


def messages(marker):
    return [{"role": "system", "content": "readonly"}, {"role": "user", "content": marker},
            {"role": "assistant", "tool_calls": [{"id": "first-call"}]},
            {"role": "tool", "tool_call_id": "first-call", "content": "customer-a-fact-123.45"}]


def test_two_independent_tool_turns_are_not_multiturn_context():
    assert not verifier.has_multiturn_context(
        [{"messages": messages("first-unique")}], [{"messages": messages("second")}], "first-unique")


def test_marker_without_previous_tool_result_is_insufficient():
    assert not verifier.has_multiturn_context(
        [{"messages": messages("first-unique")}],
        [{"messages": messages("first-unique")[:2] + messages("second")[:2]}], "first-unique")


def test_second_turn_first_request_contains_history():
    first = messages("first-unique")
    assert verifier.has_multiturn_context([{"messages": first}],
        [{"messages": first + [{"role": "assistant", "content": "done"}, {"role": "user", "content": "second"}]}], "first-unique")


def test_history_in_second_turn_later_request_is_not_enough():
    first = [{"messages": messages("first-unique")}]
    assert not verifier.has_multiturn_context(first, [{"messages": messages("second")}]+first, "first-unique")


def test_other_customer_must_not_receive_marker_or_tool_result():
    first = [{"messages": messages("first-unique")}]
    assert not verifier.isolated_context(first, first, "first-unique")
    assert verifier.isolated_context(first, [{"messages": [{"role": "user", "content": "customer-b"}]}], "first-unique")
