package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.*;

/** Host storage admission. Uploaded assets express need, never collector support or access rights. */
final class ObservationAdmission {
    static com.fasterxml.jackson.databind.JsonNode stablePlan(com.fasterxml.jackson.databind.JsonNode source) {
        var copy = source.deepCopy();
        var assessment = copy.path("observationAssessment");
        if (assessment.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) assessment).remove("status");
            for (var item : assessment.path("items")) {
                if (item.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) item).remove(List.of("readiness", "capture"));
                if (item.path("descriptor").isObject())
                    ((com.fasterxml.jackson.databind.node.ObjectNode) item.path("descriptor")).remove(List.of("availability", "authorization"));
            }
        }
        return copy;
    }

    private static io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler.PreparedSolution
            withRouting(Path candidate,
                        io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler assembler,
                        io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler.PreparedSolution prepared)
            throws Exception {
        Path selectionFile = candidate.resolve("routing-selection.json");
        if (!Files.exists(selectionFile)) return prepared;
        var mapper = HarnessJson.mapper();
        var lock = mapper.readTree(candidate.resolve("service-lock.json").toFile());
        verifyLocked(candidate, selectionFile, lock);
        var selection = mapper.readTree(selectionFile.toFile());
        if (!"finance-routing-selection.v0.1".equals(selection.path("schemaVersion").asText()))
            throw new SecurityException("ROUTING_SELECTION_INVALID");
        List<Path> descriptions = routingFiles(candidate, lock, selection.path("descriptionRefs"));
        List<Path> terminology = routingFiles(candidate, lock, selection.path("terminologyRefs"));
        return assembler.withCapabilityDescriptions(candidate.resolve("assets"), prepared, descriptions, terminology);
    }

    private static List<Path> routingFiles(Path candidate, com.fasterxml.jackson.databind.JsonNode lock,
                                           com.fasterxml.jackson.databind.JsonNode refs) throws Exception {
        if (!refs.isArray()) throw new SecurityException("ROUTING_SELECTION_INVALID");
        List<Path> files = new ArrayList<>();
        for (var ref : refs) {
            String source = ref.asText();
            if (!ref.isTextual() || !source.startsWith("repo://"))
                throw new SecurityException("ROUTING_SELECTION_INVALID");
            Path file = candidate.resolve("assets").resolve(source.substring("repo://".length())).normalize();
            if (!file.startsWith(candidate.resolve("assets")))
                throw new SecurityException("ROUTING_SELECTION_INVALID");
            verifyLocked(candidate, file, lock);
            files.add(file);
        }
        return List.copyOf(files);
    }
    static io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler.PreparedSolution prepare(
            Path candidate, Path solution, Map<String, io.github.bitlhk.finance.harness.core.spi.FinanceProvider> providers) throws Exception {
        var assembler = new io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler();
        if (!Files.exists(candidate.resolve("observation-reference.json"))) {
            var prepared = withRouting(candidate, assembler,
                    assembler.prepare(candidate.resolve("assets"), solution, providers));
            ComponentAdmission.validate(candidate, prepared.snapshot(), prepared.capabilityPacks().stream().flatMap(p -> p.capabilities().stream()).toList());
            return prepared;
        }
        var assessment = check(candidate, Path.of(System.getenv().getOrDefault("FH_HISTORY_DIR",
                candidate.resolveSibling("execution-history").toString())), !"false".equals(System.getenv("FH_HISTORY_ENABLED")));
        var grouped = new LinkedHashMap<String, List<Capability>>();
        var versions = new HashMap<String, String>();
        for (var item : assessment.items()) if (item.descriptor() != null) {
            grouped.computeIfAbsent(item.collectorId(), ignored -> new ArrayList<>()).add(item.descriptor());
            String previous = versions.putIfAbsent(item.collectorId(), item.collectorVersion());
            if (previous != null && !previous.equals(item.collectorVersion()))
                throw new IllegalStateException("COLLECTOR_VERSION_CONFLICT");
        }
        var collectors = grouped.entrySet().stream().map(entry -> new ObservationCapabilitiesV01(
                ObservationCapabilitiesV01.VERSION, entry.getKey(), versions.get(entry.getKey()), entry.getValue())).toList();
        var reference = HarnessJson.mapper().readValue(candidate.resolve("observation-reference.json").toFile(), Reference.class);
        var prepared = withRouting(candidate, assembler,
                assembler.prepare(candidate.resolve("assets"), solution, providers, reference, collectors));
        ComponentAdmission.validate(candidate, prepared.snapshot(), prepared.capabilityPacks().stream().flatMap(p -> p.capabilities().stream()).toList());
        return prepared;
    }

    public static void main(String[] args) throws Exception {
        Path candidate = Path.of(args[0]).toAbsolutePath().normalize();
        try {
            var prepared = prepare(candidate, candidate.resolve("solution.json"), ReadOnlyProviders.forPlan(candidate));
            System.out.println(HarnessJson.mapper().writeValueAsString(prepared.observationAssessment()));
        } catch (ObservationAssessmentV01.UnmetRequirements rejected) {
            System.out.println(HarnessJson.mapper().writeValueAsString(rejected.assessment()));
            throw rejected;
        }
    }

    static ObservationAssessmentV01 check(Path candidate, Path directory, boolean enabled) {
        return HostObservationAdmission.check(candidate, directory, enabled);
    }
    static ObservationAssessmentV01 check(Path candidate, Path directory, boolean enabled, Map<String,String> env) {
        return HostObservationAdmission.check(candidate, directory, enabled, env);
    }

    private static void verifyLocked(Path candidate, Path file, com.fasterxml.jackson.databind.JsonNode lock) throws Exception {
        String relative = candidate.relativize(file).toString().replace('\\', '/');
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 65_536
                || !file.toRealPath().startsWith(candidate.toRealPath())
                || !ExecutionArchive.hash(Files.readAllBytes(file)).equals(lock.path("files").path(relative).asText()))
            throw new SecurityException("OBSERVATION_ASSET_NOT_LOCKED");
    }
}
