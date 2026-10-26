# Optional observation assembly v0.1

This sidecar packages the native SDK metadata bridge and independent financial
analyzer without adding either module to the default SDK 0.1.15 reactor.
The supported assembly is Java/JDK21, openJiuwen SDK 0.1.16 and rule
`finance-single-run.v1`. Other runtimes are not qualified by this declaration.

## Source and artifact boundaries

`observation-packs/native-analysis/assembly.v0.1.json` locks source trees,
contracts, requirements, conformance cases and development support by SHA256.
File hashes use exact repository blob bytes. Tree hashes concatenate sorted
relative POSIX paths, NUL, file bytes, NUL. Use a byte-preserving Git checkout
or export (LF for these text assets); a CRLF-converted checkout is different
material and is correctly rejected. The validator never normalizes inputs.
Build outputs are outside the declared source trees. JARs and actual deployed
configuration require their own Host artifact locks; source validation does
not qualify installed artifacts.

`policyContract` references the assembly schema's `$defs.policy`: off/sample/full,
metadata only, at most 16KiB per record and 256KiB per request. The deployment
selects and locks actual policy values. The Host must disable raw SDK tracing
before initialization, recheck current authorization and storage, and supply
request/instance/process-boot/runtime-lock bindings. The manifest cannot grant
these rights. Required financial receipts and business controls are not disabled
by turning optional native tracing off.

## Independent offline verification

Run these fixed commands from a byte-preserving repository export:

```sh
python engineering/verify_observation_assembly.py
python -m unittest discover -s engineering -p test_observation_assembly.py -v
mvn -B -ntp -f integrations/openjiuwen-java-otel/pom.xml test
mvn -B -ntp -f packages/java/observation-analysis/pom.xml test
```

The CLI checks assembly positive/negative cases, 16 native structural cases,
18 analysis structural cases and development support source/index consistency.
Three invalid analysis cases (foreign evidence and duplicate step/evidence IDs)
are structurally valid: their rejection belongs to the separate Java semantic
tests, not to JSON Schema. All four checks are required; structural success is
not runtime execution or business authorization evidence. CI invokes the two
optional modules explicitly, retaining existing root gates. A CI job that did
not start (including account/billing restrictions) is not a passing check.

Development support reuses the existing schema/index. Its subject is the native
bridge integration; raw case references retain their native formats. Listed
commands describe original core tests at a fixed source commit and are display
only. No consumer executes them based on manifest contents.

## Role evidence

`financial-evidence-context.v0.1` contains only finite identifiers, source
digests, business outcome, technical outcome and record coverage. Evaluator
uses evaluation/comparison purposes; Developer uses development-context. The
Host checks owner, purpose, parent lock, report, sample and actual invocation
association before every read and before returning prepared results.
Schema validation is not association proof or permission. No timestamps or
display names may be used to guess a match. Missing links remain unavailable.
No raw body, protected expectations, Holdout, credentials, score or arbitrary
command is accepted. Opaque references must not embed customer identity.

One document is limited to 256KiB, 128 samples and 256 total evidence references.
Overflow is rejected as a whole, not silently truncated. Financial outcome,
technical outcome, coverage and original Evaluator conclusion remain separate;
reading these records never changes the original report or starts a model,
bank operation, construction task, rebuild, optimization or deployment.
