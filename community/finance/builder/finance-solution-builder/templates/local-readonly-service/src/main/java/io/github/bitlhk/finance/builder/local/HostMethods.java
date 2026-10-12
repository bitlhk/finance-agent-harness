package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.util.*;

/** Default host methods are locked files, selected identically for either Runtime. */
final class HostMethods {
    static List<Map<String, String>> load(Path root, Set<String> capabilities) throws java.io.IOException {
        Set<String> custom = new HashSet<>();
        if (Files.isRegularFile(root.resolve("method-selection.json")))
            HarnessJson.mapper().readTree(root.resolve("method-selection.json").toFile())
                    .path("capabilityIds").forEach(c -> custom.add(c.asText()));
        var catalog = HarnessJson.mapper().readTree(root.resolve("methods/catalog.json").toFile());
        if (!"finance-host-methods.v0.1".equals(catalog.path("schemaVersion").asText()))
            throw new IllegalArgumentException("HOST_METHOD_CATALOG_INVALID");
        var result = new ArrayList<Map<String, String>>();
        for (var item : catalog.path("methods")) {
            String capability = item.path("capabilityId").asText();
            if (!capabilities.contains(capability) || custom.contains(capability)) continue;
            Path path = root.resolve("methods").resolve(item.path("file").asText()).normalize();
            if (!path.startsWith(root.resolve("methods")) || Files.isSymbolicLink(path))
                throw new IllegalArgumentException("HOST_METHOD_PATH_INVALID");
            byte[] bytes = Files.readAllBytes(path);
            result.add(Map.of("capabilityId", capability, "instructions", new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                    "digest", ExecutionArchive.hash(bytes)));
        }
        return List.copyOf(result);
    }
}
