# Capability description A/B pre-experiment V0.1

This experiment compares OpenJiuwen native tool selection with the same model,
Runtime, system prompt, tool schemas and installed capabilities.

- A uses the existing Capability Pack descriptions.
- B adds only versioned use cases, example utterances, negative examples and the
  selected terminology snapshot.
- Paired variants run next to each other with alternating A/B order to reduce
  time-order bias. Repetition count and Runtime iteration limit are explicit
  environment inputs and are recorded in each report.
- Multi-turn cases retain the complete call sequence but score the final user turn;
  a previous valid query is not reclassified as a call made by a later cancellation.
- TASK selection is stopped at `BEFORE_TOOL_CALL`. QUERY cases may execute a
  deterministic in-memory Provider so multi-tool responses can be assessed;
  every bank transport must remain at zero calls.
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
FH_AB_SUITE=evaluation/capability-description-ab-v0.1/routing-policy-cases.json \
FH_AB_REPETITIONS=3 \
FH_AB_MAX_ITERATIONS=2 \
FH_AB_REPORT=/tmp/fh-ab-report.json \
mvn -B -ntp -pl integrations/openjiuwen-java \
  -Dtest=CapabilityDescriptionAbLiveTest test

python3 engineering/summarize_capability_description_ab.py \
  /tmp/fh-ab-report.json --output /tmp/fh-ab-summary.json
```

Do not commit model keys or infer bank accuracy from this synthetic pre-experiment.
The five controlled model-failure cases run locally and assert that task Providers
are not invoked when the model transport fails.

Two suites are intentionally separate from the original fixed set:

- `routing-policy-cases.json` checks provisional trusted-date and account-selector
  rules.
- `readonly-compound-cases.json` checks exact multi-capability calls with
  `FH_AB_MAX_ITERATIONS=8`, matching the Java reference Agent. It is a baseline,
  not evidence for an independent planner or Router SPI.
- `readonly-task-collaboration-cases.json` narrows the experiment to cash-flow
  summary plus spending analysis. It covers single and multiple goals, partial
  installation, trusted dates, ambiguity, negation, cancellation, and multi-turn
  month changes. Expected goals remain evaluator-only and never enter Runtime
  prompts.

Installing the optional description catalog also installs the generic read-only
collaboration instruction. It asks the Runtime to check every user goal and report
unsupported or missing parts; it does not create a plan, grant permission, or
bypass normal tool authorization.
