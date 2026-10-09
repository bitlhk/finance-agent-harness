# Evaluation Case Data V1

This opt-in host resource is separate from financial Capability Packs. It is not
a filesystem gateway, an uploaded-code runner, or authorization to call a bank.

`CaseFileWorkspaceV1` in `finance-harness-execution` holds immutable UTF-8 case
data. Both Java integrations expose `read_case_file({path})` through their native
tool registries. Only exact declared relative paths can be read. Absolute paths,
traversal, duplicate paths, file/directory collisions, invalid Unicode, oversize
content and mismatched digests fail before installation. Limits are 32 files,
262144 UTF-16 units per file, 1 MiB total UTF-8, and 128 reads per case.

The workspace digest sorts paths by UTF-16 code units and hashes each UTF-8 path,
NUL, content, NUL. It agrees with Finance Eval's `workspaceFilesDigest`.
Each read records path, byte count and SHA-256. Neither a declared digest nor an
installed tool proves a read happened. The contents do not enter the initial
model prompt; the native tool returns them. Closing the workspace denies further
reads. This is access revocation, not a claim of secure memory erasure.

## Integration

- OpenJiuwen: `OpenJiuwenCaseFileTool` installs into the existing Agent and global
  registry. Its caller must retain the existing native lock and close the binding.
- AgentScope: `AgentScopeQueryAssembler.openSession(..., caseFiles)` installs one
  auxiliary native tool. Financial tool-set verification is unchanged; a separate
  `caseWorkspace` manifest records the auxiliary resource in the installation Lock.
- The Host supplies the existing authorization check before and after a read and
  retains checks before model requests and final delivery. The model cannot choose
  owner, identity, bank grants or case contents.
- The Launcher creates a fresh session for each case and closes the resource on
  success, failure, cancellation and session destruction. The next case cannot
  reuse the old workspace. Provider code is unchanged.

Existing overloads and Agents without a workspace remain compatible. This does
not expand Python support or permit TASK in the Eval integration. New generated
Hosts require new observation qualification; old Agents are not upgraded.

## Verification

```bash
mvn -o -B -pl integrations/openjiuwen-java,integrations/agentscope-java -am test \
  -Dtest=CaseFileWorkspaceV1Test,OpenJiuwenCaseFileToolTest,AgentScopeQueryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

These are controlled SDK/HTTP tests, not real-model or browser acceptance. Builder
adds Host admission, actual Launcher/file evidence, lifecycle and cross-repository
contract checks. End-to-end and live-model results must be reported separately.
# Text framing restriction

Case file content must not contain NUL. The V1 workspace digest separates paths
and text with NUL, so accepting it inside content would allow distinct manifests
to share the same framing. Normal UTF-8 text keeps the existing digest. This is
enforced by the Java workspace, Builder intake and Eval contract. Binary data is
not a supported case-file format.
