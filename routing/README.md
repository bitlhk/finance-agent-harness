# Capability descriptions and terminology

These candidate V0.1 assets improve Runtime-native tool selection without adding
an intent engine.

- `capability-descriptions/` supplements an existing Capability Pack. It does not
  duplicate input schemas, operation types or required scopes.
- `terminology/` explains institution terms. A term never grants permission or
  creates a tool. `KNOWN_UNSUPPORTED` explicitly has no capability binding.
- `CapabilityDescriptionCatalogV01` compiles only installed capabilities and
  produces a digest-locked snapshot. Installation rejects a different capability
  set before tool registration.
- Existing solutions that do not opt into these assets keep their original ToolCard
  descriptions and Lock shape.

The current compiler is exposed through the OpenJiuwen Java Integrator because
that is the only verified Runtime. The JSON assets are Runtime neutral. This is
not a Router SPI, classifier, retrieval service or authorization mechanism.

Java hosts opt in after ordinary package resolution and before installation:

```java
var prepared = assembler.prepare(repositoryRoot, solutionFile, providers);
var enhanced = assembler.withCapabilityDescriptions(
        repositoryRoot,
        prepared,
        descriptionFiles,
        terminologyFiles);
var assembled = assembler.install(enhanced, agent, defaults, simulated, checkpoints, hostSessionRef);
```

`descriptionFiles` and `terminologyFiles` are generic asset references selected by
the host or Builder. Builder code must not branch on finance capability IDs. The
resulting installation plan and Solution Lock contain the catalog digest and source
asset digests. V0.1 does not yet make these assets a production Builder default.
