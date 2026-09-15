# Capability description A/B pre-experiment V0.1

This experiment compares OpenJiuwen native tool selection with the same model,
Runtime, system prompt, tool schemas and installed capabilities.

- A uses the existing Capability Pack descriptions.
- B adds only versioned use cases, example utterances, negative examples and the
  selected terminology snapshot.
- Paired variants run next to each other with alternating A/B order to reduce
  time-order bias. V0.1 still uses one stochastic execution per variant and case.
- Multi-turn cases retain the complete call sequence but score the final user turn;
  a previous valid query is not reclassified as a call made by a later cancellation.
- The Runtime is stopped at `BEFORE_TOOL_CALL`. The recorder Provider and every
  bank transport must remain at zero calls.
- Transfer cases distinguish complete bank identifiers from requests that still
  need required parameters. For the latter, V0.1 proves only that no tool was
  called; it does not score the natural-language clarification quality.
- A domain match is not an execution decision. `转账前要核对什么` is consultation
  and must not invoke `transfer.internal.create`.

Run deterministic contracts first:

```bash
mvn -B -ntp -pl integrations/openjiuwen-java -am test
/path/to/venv/bin/python engineering/verify_contracts.py
```

The live comparison is explicit and skipped by normal CI:

```bash
FH_AB_LIVE=true \
FH_MODEL_URL=https://configured.example/v1 \
FH_MODEL_NAME=configured-model \
FH_MODEL_KEY=server-side-secret \
FH_AB_REPORT=/tmp/fh-ab-report.json \
mvn -B -ntp -pl integrations/openjiuwen-java \
  -Dtest=CapabilityDescriptionAbLiveTest test

python3 engineering/summarize_capability_description_ab.py \
  /tmp/fh-ab-report.json --output /tmp/fh-ab-summary.json
```

Do not commit model keys or infer bank accuracy from this synthetic pre-experiment.
The five controlled model-failure cases run locally and assert that task Providers
are not invoked when the model transport fails.
