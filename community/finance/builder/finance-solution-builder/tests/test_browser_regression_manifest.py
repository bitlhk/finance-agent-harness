from generate_browser_regression import CAPABILITIES, SUPPORT, manifest

def test_smoke_covers_each_card_and_runtime_maximal_selection():
    value = manifest("smoke")
    assert len(value["cases"]) == 16
    for runtime, cards in SUPPORT.items():
        selected = [case["componentIds"] for case in value["cases"] if case["runtimeId"] == runtime]
        assert list(cards) in selected
        for card in cards:
            assert [card] in selected

def test_full_has_all_77_legal_subsets_and_no_write_or_retry():
    value = manifest("full")
    assert len(value["cases"]) == 77
    assert len({(c["runtimeId"], tuple(c["componentIds"])) for c in value["cases"]}) == 77
    assert value["allowBusinessCommit"] is False
    assert value["automaticRetries"] == 0
    assert value["deleteOnlyAgentsCreatedByThisRun"] is True
    assert value["stopBatchOnCleanupFailure"] is True
    for case in value["cases"]:
        assert case["status"] == "NOT_RUN"
        assert set(case["componentIds"]) <= set(SUPPORT[case["runtimeId"]])
        assert case["expectedCapabilityIds"] == sorted(c for card in case["componentIds"] for c in CAPABILITIES[card])
