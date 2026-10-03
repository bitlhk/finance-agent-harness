# Isolated qualification runner

`verify.sh <new-output-directory>` runs the named `AgentScopeQueryTest` suite,
packages its raw evidence and checks the installed artifacts in fresh JVMs.
`verify.sh <new-output-directory> --tasks` additionally selects
`AgentScopeTaskSessionTest` and requests TASK qualification.

The evidence directory must contain only the suites for the requested mode.
Running every integration test into a QUERY evidence directory also writes TASK
and method-only records; the strict worker correctly refuses that mixed set as
`TEST_EVIDENCE_INCOMPLETE`. Do not delete records from a completed run or weaken
the worker to make it pass. Start a new scoped run instead.

This is controlled native qualification, not a replacement for the full Maven
regression, generated Host qualification, real-model evaluation, or production
admission. Generated Hosts must obtain their own proof for their exact artifact
and Provider configuration identity.
