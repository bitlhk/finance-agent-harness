package io.github.bitlhk.finance.harness.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

class ObservationAnalyzerTest {
    private static Path fixturePath() {
        Path root = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5; depth++, root = root.getParent()) {
            Path fixture = root.resolve("contracts/fixtures/observation/analysis-cases.v1.json");
            if (Files.isRegularFile(fixture)) return fixture;
        }
        throw new AssertionError("Registered conformance fixture missing");
    }
    private static JsonNode fixture() throws Exception { return ObservationAnalyzer.parse(Files.readAllBytes(fixturePath())); }
    private static JsonNode control() throws Exception { return fixture().get("cases").get(0).get("input").deepCopy(); }

    @TestFactory Stream<DynamicTest> conformance() throws Exception {
        java.util.List<DynamicTest> tests = new java.util.ArrayList<>();
        for (JsonNode row : fixture().get("cases")) tests.add(DynamicTest.dynamicTest(row.get("name").asText(), () -> {
            JsonNode input = row.get("input"), before = input.deepCopy();
            if (!row.get("valid").booleanValue()) {
                assertThrows(IllegalArgumentException.class, () -> ObservationAnalyzer.analyze(input)); return;
            }
            JsonNode result = ObservationAnalyzer.analyze(input), expected = row.get("expected");
            for (String key : new String[]{"business", "technical", "integrity"}) assertEquals(expected.get(key), result.get(key));
            Set<String> reasons = new HashSet<>(), required = new HashSet<>(), allowed = new HashSet<>();
            input.get("evidence").forEach(value -> allowed.add(value.get("id").asText()));
            expected.get("reasons").forEach(value -> required.add(value.asText()));
            for (JsonNode item : result.get("findings")) {
                reasons.add(item.get("reasonCode").asText());
                assertNotEquals("INFERENCE", item.get("classification").asText());
                if (!item.get("classification").asText().equals("UNKNOWN")) assertFalse(item.get("evidenceIds").isEmpty());
                item.get("evidenceIds").forEach(value -> assertTrue(allowed.contains(value.asText())));
            }
            assertEquals(required, reasons); assertEquals(before, input);
            assertArrayEquals(ObservationAnalyzer.encode(result), ObservationAnalyzer.encode(ObservationAnalyzer.analyze(input)));
            for (String key : new String[]{"requestId", "instanceId", "processBootId", "runtimeLockDigest", "snapshotDigest", "ruleVersion"})
                assertEquals(input.get(key), result.get(key));
        }));
        return tests.stream();
    }

    @Test void rejectsDuplicateJsonKeysAndTrailingDocuments() throws Exception {
        String json = new String(ObservationAnalyzer.encode(control()), StandardCharsets.UTF_8);
        String duplicate = json.replaceFirst("\\{", "{\"ruleVersion\":\"private\",");
        assertThrows(Exception.class, () -> ObservationAnalyzer.parse(duplicate.getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> ObservationAnalyzer.parse((json + " {}").getBytes(StandardCharsets.UTF_8)));
    }
    @Test void rejectsOversizeAndExcessiveDepth() {
        assertThrows(Exception.class, () -> ObservationAnalyzer.parse(new byte[ObservationAnalyzer.MAX_INPUT + 1]));
        assertThrows(Exception.class, () -> ObservationAnalyzer.parse(("[".repeat(30) + "0" + "]".repeat(30)).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void numbersAreExactAndCannotBeBooleansOrFloats() throws Exception {
        for (JsonNode number : new JsonNode[]{ObservationAnalyzer.parse("true".getBytes()), ObservationAnalyzer.parse("1.5".getBytes()), ObservationAnalyzer.parse("-1".getBytes()), ObservationAnalyzer.parse("9007199254740992".getBytes())}) {
            JsonNode input = control(); ObjectNode step = (ObjectNode) input.get("steps").get(0);
            step.put("clockGroup", "host:boot-1"); step.set("durationNanos", number);
            assertThrows(IllegalArgumentException.class, () -> ObservationAnalyzer.analyze(input));
        }
    }
    @Test void cyclesAndMissingParentsNeverLookComplete() throws Exception {
        JsonNode input = control(); ((ObjectNode) input.get("steps").get(0)).put("parentId", "cap-1");
        assertEquals("GAPPED", ObservationAnalyzer.analyze(input).get("integrity").asText());
    }
    @Test void elapsedIntervalsAreNotSummedOrCalledRootCause() throws Exception {
        JsonNode input = control(); ObjectNode step = (ObjectNode) input.get("steps").get(0);
        step.put("clockGroup", "host:boot-1").put("durationNanos", 100);
        ObjectNode second = step.deepCopy().put("id", "child").put("parentId", "cap-1").put("durationNanos", 80);
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.get("steps")).add(second);
        JsonNode result = ObservationAnalyzer.analyze(input);
        assertEquals(1, result.get("findings").size());
        assertEquals(100, result.get("findings").get(0).get("durationNanos").longValue());
        assertEquals("STATISTIC", result.get("findings").get(0).get("classification").asText());
    }
    @Test void emptySnapshotStaysUnknownAndDoesNotInventEvidence() throws Exception {
        JsonNode input = control(); ((ObjectNode) input).putNull("terminal");
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.get("steps")).removeAll();
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.get("evidence")).removeAll();
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.get("coverage").get("evidenceIds")).removeAll();
        JsonNode result = ObservationAnalyzer.analyze(input);
        assertEquals("UNKNOWN", result.get("business").asText()); assertEquals("UNKNOWN", result.get("technical").asText());
        for (JsonNode item : result.get("findings")) assertEquals("UNKNOWN", item.get("classification").asText());
    }
    @Test void cliRejectsArgumentsAndDoesNotExposeSourceText() throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-cp", System.getProperty("java.class.path"), AnalysisMain.class.getName(), "/private/path")
                .redirectErrorStream(true).start();
        process.getOutputStream().close(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, process.exitValue()); assertFalse(output.contains("/private/path"));
        assertEquals("ANALYSIS_INPUT_INVALID", ObservationAnalyzer.parse(output.getBytes()).get("code").asText());
    }
}
