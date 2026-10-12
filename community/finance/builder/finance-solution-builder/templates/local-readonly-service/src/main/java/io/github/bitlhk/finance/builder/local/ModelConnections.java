package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Private host connections are resolved at every dispatch; the artifact contains only a frozen allowlist. */
final class ModelConnections {
    record Connection(String ref, String version, String provider, String name, String url, String key) {
        @Override public String toString() { return ref + "@" + version; }
    }
    final JsonNode selection;
    record Generation(double temperature, double topP, int maxTokens) {}
    record GenerationBinding(String modelRef, Generation parameters, JsonNode wire, String digest) {}
    private final GenerationBinding generation;
    private final Map<String, JsonNode> allowed = new HashMap<>();
    ModelConnections(Path root, JsonNode manifest) throws Exception {
        Path file = root.resolve("model-selection.json");
        if (!manifest.has("modelSelection")) {
            if (Files.exists(file)) throw new IllegalArgumentException("MODEL_SELECTION_UNLOCKED");
            selection = null;
            generation = loadGeneration(null);
            current("configured-model");
            return;
        }
        selection = HarnessJson.mapper().readTree(file.toFile());
        if (!selection.equals(manifest.get("modelSelection")) || !manifest.path("files").has("model-selection.json")
                || !"finance-model-selection.v1".equals(selection.path("schemaVersion").asText()))
            throw new IllegalArgumentException("MODEL_SELECTION_LOCK_MISMATCH");
        generation = loadGeneration(selection);
        Set<String> refs = new HashSet<>();
        for (JsonNode ref : selection.path("selectedModelRefs")) {
            if (!ref.isTextual() || !ref.asText().matches("model-[a-z0-9-]{1,80}") || !refs.add(ref.asText()))
                throw new IllegalArgumentException("MODEL_SELECTION_INVALID");
        }
        for (JsonNode item : selection.path("connections")) {
            String ref = item.path("modelRef").asText();
            if (!refs.contains(ref) || allowed.put(ref, item) != null) throw new IllegalArgumentException("MODEL_SELECTION_INVALID");
        }
        if (refs.isEmpty() || !refs.equals(allowed.keySet()) || !refs.contains(defaultRef()))
            throw new IllegalArgumentException("MODEL_SELECTION_INVALID");
        for (String ref : refs) current(ref);
    }
    private static GenerationBinding loadGeneration(JsonNode selection) throws Exception {
        JsonNode locked = selection == null ? null : selection.get("modelGeneration");
        String raw = System.getenv("FH_MODEL_GENERATION_JSON");
        if (locked == null && raw == null) return null;
        if (locked == null || raw == null || raw.length() > 2048)
            throw new SecurityException("MODEL_GENERATION_LOCK_MISMATCH");
        JsonNode supplied = HarnessJson.mapper().readTree(raw);
        if (!locked.equals(supplied) || !locked.isObject() || locked.size() != 2
                || !locked.path("modelRef").isTextual() || !locked.has("parameters"))
            throw new SecurityException("MODEL_GENERATION_LOCK_MISMATCH");
        String ref = locked.path("modelRef").asText();
        boolean selected = false;
        for (JsonNode item : selection.path("selectedModelRefs")) selected |= ref.equals(item.asText());
        JsonNode p = locked.path("parameters");
        if (!selected || !p.isObject() || p.size() != 4
                || !"finance.eval-generation.v1".equals(p.path("schemaVersion").asText())
                || !p.path("temperature").isNumber() || !p.path("top_p").isNumber()
                || !p.path("max_tokens").isIntegralNumber() || !p.path("max_tokens").canConvertToInt())
            throw new SecurityException("MODEL_GENERATION_INVALID");
        double temperature = p.path("temperature").asDouble(), topP = p.path("top_p").asDouble();
        int maximum = p.path("max_tokens").asInt();
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || !Double.isFinite(topP) || topP <= 0 || topP > 1 || maximum < 1 || maximum > 32768)
            throw new SecurityException("MODEL_GENERATION_INVALID");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        return new GenerationBinding(ref, new Generation(temperature, topP, maximum), locked.deepCopy(), digest);
    }
    Generation generation(String ref) {
        if (generation == null) return null;
        if (!generation.modelRef().equals(ref)) throw new SecurityException("MODEL_GENERATION_MODEL_MISMATCH");
        return generation.parameters();
    }
    void generationEvidence(String ref, Map<String, Object> output) {
        if (generation(ref) == null) return;
        output.put("modelGeneration", generation.wire().deepCopy());
        output.put("modelGenerationSha256", generation.digest());
    }
    String defaultRef() { return selection == null ? "configured-model" : selection.path("defaultModelRef").asText(); }

    Connection current(String ref) {
        try {
            if (selection == null) {
                if (!"configured-model".equals(ref)) throw new SecurityException("MODEL_NOT_SELECTED");
                return checked(ref, "LEGACY_HOST_CONFIGURATION", "OpenAI", BankAccess.env("FH_MODEL_NAME"),
                        BankAccess.env("FH_MODEL_URL"), BankAccess.env("FH_MODEL_KEY"));
            }
            JsonNode frozen = allowed.get(ref);
            if (frozen == null) throw new SecurityException("MODEL_NOT_SELECTED");
            JsonNode registry = HarnessJson.mapper().readTree(Path.of(BankAccess.env("FH_MODEL_REGISTRY")).toFile());
            if (!"finance-model-bindings.v1".equals(registry.path("schemaVersion").asText()))
                throw new SecurityException("MODEL_CONNECTION_UNAVAILABLE");
            JsonNode found = null;
            for (JsonNode binding : registry.path("bindings")) if (ref.equals(binding.path("modelRef").asText())) {
                if (found != null) throw new SecurityException("MODEL_CONNECTION_UNAVAILABLE");
                found = binding;
            }
            if (found == null || !found.path("enabled").isBoolean() || !found.path("enabled").asBoolean()
                    || !"OPENAI_CHAT_COMPLETIONS".equals(found.path("protocol").asText())
                    || !Set.of("OpenAI", "OpenRouter").contains(found.path("provider").asText()))
                throw new SecurityException("MODEL_CONNECTION_UNAVAILABLE");
            String urlEnv = found.path("urlEnvironment").asText(), keyEnv = found.path("keyEnvironment").asText();
            if (!urlEnv.matches("FH_CONN_[A-Z0-9_]{1,100}") || !keyEnv.matches("FH_CONN_[A-Z0-9_]{1,100}"))
                throw new SecurityException("MODEL_CONNECTION_UNAVAILABLE");
            String url = BankAccess.env(urlEnv), key = BankAccess.env(keyEnv);
            List<String> values = new ArrayList<>();
            for (String field : List.of("modelRef", "revision", "protocol", "provider", "modelName", "urlEnvironment", "keyEnvironment"))
                values.add(found.path(field).asText());
            values.add(url);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8)); digest.update(bytes);
            }
            String version = "sha256:" + HexFormat.of().formatHex(digest.digest());
            if (!version.equals(frozen.path("configVersion").asText())
                    || !found.path("modelName").equals(frozen.path("modelName"))
                    || !found.path("provider").equals(frozen.path("provider")))
                throw new SecurityException("MODEL_CONFIGURATION_CHANGED");
            return checked(ref, version, found.path("provider").asText(), found.path("modelName").asText(), url, key);
        } catch (SecurityException denied) { throw denied; }
        catch (Exception failure) { throw new SecurityException("MODEL_CONNECTION_UNAVAILABLE"); }
    }
    private Connection checked(String ref, String version, String provider, String name, String url, String key) {
        URI uri = URI.create(url);
        if (name.isBlank() || key.isBlank() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !("https".equals(uri.getScheme())
                    || "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost").contains(uri.getHost())))
            throw new SecurityException("MODEL_CONNECTION_UNAVAILABLE");
        return new Connection(ref, version, provider, name, url, key);
    }
}
