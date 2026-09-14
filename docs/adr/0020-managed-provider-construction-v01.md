# Managed Provider construction V0.1 (candidate)

## Need and boundary

The local Host previously constructed the bank Provider and the wealth projection,
including source-scope and business mapping choices. Those choices belong to the
Provider artifacts. Adding a capability on an existing Provider must not require
another Host construction branch.

The candidate `ManagedProviderFactoryV01` SPI and its small installation helper
are Runtime-independent. They construct reviewed, preinstalled Providers only.
They do not schedule business tasks, execute workflows, interpret model text or
grant permissions. They supplement, not replace, the existing Provider SPI and
four public request/result/context/evidence contracts.

## Decision

- Discover factories from `META-INF/services` in the packaged, managed Java
  dependencies. Catalog selections identify Providers, never arbitrary class names,
  executable paths or imports. This is not an untrusted plugin upload interface.
- Construct only the explicitly selected Provider identities and their factory
  dependencies. Reject missing/cyclic dependencies, incompatible connections,
  duplicate identities and descriptor/requirement disagreement before installation.
- Factories own their capability requirements and additional source requirements.
  These are requirements, not grants. BoundFinanceProvider consumes the host's
  independent current Grant supplier on every invocation, including dependency calls.
- Construction must not obtain a source identity, Grant or perform business HTTP.
  The source-session supplier remains live; source binding cannot be frozen at build.
- The reference bank factory owns its protocol construction. The wealth factory
  owns its mobile source mapping and dependency. The Host keeps its existing
  simulator IAM adapter and introspection order in this increment.
- Existing Assembler snapshots record actual implementation JAR digests. Factories
  live in those same Core/Provider/wealth artifacts, not separate untracked files.
  A factory's availability does not mean all its capabilities were selected or authorized.

## Scope and compatibility

Only the managed Java dependency set and synthetic-bank-http.v1 connection are
qualified here. Arbitrary external JAR resolution, heterogeneous connections and
untrusted factory code are not supported. A missing packaged implementation must
fail; the Builder must not download or import a class from selection input.

Readonly Host installation remains QUERY-only, including the Python delegated
Java bridge. Existing generated Agents retain their original JARs and Lock.
The bank factory describes existing TASK implementations, but does not register
them into a readonly Agent or qualify the shared TASK lifecycle.

For a real additional selection, qualify the existing customer-context Pack for
local-read-only and install `wealth.customer-context.query`. This is the original
customer source-context capability, not a rename of `wealth.facts.describe` and
not the distinct second TASK business. Preserve original response semantics.

## Required evidence

Factory identity/dependency/connection negatives; zero identity/HTTP during
construction; revoked and insufficient grants block business calls; actual
additional tool registration and source reads; affected legacy Java/Python
compositions; installed artifact/plan equality. Controlled model checks do not
constitute natural-language quality or independent human onboarding acceptance.
