# Tool-free conversation stream boundary

OpenJiuwen Java remains pinned at 0.1.15. Its `ReActAgent.writeAssistantStreamChunk`
discards `String.isBlank()` content, including standalone newline/space deltas.
Two loopback HTTP tests reproduce the loss against the unmodified adapter. This
is transport loss, not a Markdown renderer or prompt problem.

`ConversationResponder` uses the same SDK `Model` stream and unchanged connection
configuration for tool-free wording. It does not create a ReAct session, register
tools, change credentials, or implement a second HTTP client. Pre-call, per-release
and final authority/evidence checks still belong to the existing caller. Every
nonempty text delta, including whitespace, is retained verbatim. Reasoning and
usage are not display text. Unexpected tool calls, incomplete or non-stop finishes,
and content after completion fail closed; no automatic retry repairs a reply.

The optional `ModelSnapshotClient` observer forwards stream closure to its delegate.
A close before exhaustion is recorded as `CALLER_CLOSED`, not successful completion
or proof of a user cancellation. Optional observation failure remains distinct from
the caller's mandatory evidence failure, which blocks release.

Tests cover exact whitespace/repeated chunks, incremental delivery, no tools,
pre-call refusal, cancellation/revocation/evidence failure, malformed/truncated output,
unexpected tool calls, HTTP failure, and idempotent close. Synthetic HTTP replay is
not independent ordinary-SSO acceptance or proof of arbitrary model behavior.

This changes the shared Java integration JAR. Final Host qualification and service
identity checks must bind the resulting JAR; old package identities cannot be reused
just because the UI is unchanged. Structured intent and saved-report generators
retain their separate existing contracts; this change does not claim to migrate
every ReAct consumer or every Runtime.
