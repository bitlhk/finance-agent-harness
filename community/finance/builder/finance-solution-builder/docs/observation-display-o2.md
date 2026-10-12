# O2 component descriptions and qualification

The finance-specific Host template consumes Harness `PreparedSolution.displayMetadata()`.
It does not add a parallel Base contract or a business-name catalog to Builder. Java and
the owned Java bridge of Python use the same accessor, snapshot writer and history reader.
Generic stage explanations remain a presentation concern. Source operation names are
owned by Provider; capabilities reuse Pack names. Match by provider ID + method + route
template, never fuzzy path guessing. Adding a source requires no UI dictionary edits.

Each request preserves `<id>.labels.json`, `<id>.runtime-lock.json` and an
`installation-display` record linking both digests. Neither file is overwritten by later
sessions or component versions. The reader verifies both digests before showing FROZEN.
Legacy records are marked compatibility interpretations; broken new records are marked
UNAVAILABLE. The UI escapes every label. Names never prove authorization or success.
64 KiB label limits and existing archive admission/quota apply; label write failure
keeps the business outcome and exposes a missing label receipt. Existing snapshot receipt
projection is not body verification: opening bodies still needs separate owner access and
digest validation. Client snapshots are not asserted to be raw model HTTP requests.

## Issuing a collector qualification

Service/runtime manifests have an independent bounded limit; see
`execution-history-v01.md`. Archive identity includes the `ExecutionArchive.Request`
inner-class bytecode as well as the outer collector. This coverage correction changes
both Java and Python identities and requires separate new semantic evidence for each.
The qualification V0.1 schema, source field rules, authorization semantics and REQUIRED
obligations are unchanged. Preserve still-needed original identities as separate
entries; an old Java entry cannot qualify a new Java or Python collector.

1. Generate an unqualified controlled Candidate from clean fixed Harness/Builder commits.
2. Run controlled traces for the intended requirement semantics, inspect the real fields,
   negative cases, artifact hashes and non-observed boundaries. A passing report alone is
   not a semantic review. Record the reviewer/operator and evidence scope out of band.
3. Run `java -cp 'target/<candidate>.jar:target/dependency/*'
   io.github.bitlhk.finance.builder.local.CollectorQualification <candidate-directory>`
   in that Candidate to obtain identities. They bind runtime/collector source and actual
   dependency hashes, not current user business grants.
4. A trusted operator writes the versioned qualification record with reviewed semantics,
   the exact identity and relative evidence paths/hashes. Place it in protected host
   configuration; inject `FH_OBSERVATION_QUALIFICATION_FILE` and its SHA-256 via
   `FH_OBSERVATION_QUALIFICATION_SHA256`. Never accept this file from Skill/Pack upload,
   browser parameters or a business customer to grant trust.
5. Regenerate admitted Candidates and test startup, actual per-request capture and denial.
   Changed collector/runtime/dependency identity requires new tests and qualification.
   Reusing a previous signed-off identity requires exact matching artifacts. Hash checks
   do not prove that arbitrary evidence is adequate; this is trusted operator assertion,
   not automated quality attestation or a cryptographic certificate authority.

No qualification grants customer access or diagnostic-body capture/view permission.
Unknown SDK support stays UNKNOWN; metadata collector verification is separate. These
are candidate contracts and controlled acceptance; no stable deployment, human independent
onboarding or production IAM is claimed by the O1/O2 branch.
