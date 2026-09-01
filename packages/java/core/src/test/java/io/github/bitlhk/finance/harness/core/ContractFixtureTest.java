package io.github.bitlhk.finance.harness.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractFixtureTest {
    private static final ObjectMapper JSON = HarnessJson.mapper();
    private static final Path ROOT = findRepositoryRoot();

    @Test
    void validFixturesDeserializeAndRoundTrip() throws IOException {
        assertRoundTrip("finance-context.customer.json", FinanceContextV1.class);
        assertRoundTrip("capability-request.query.json", CapabilityRequestV1.class);
        assertRoundTrip("capability-request.task-confirmed.json", CapabilityRequestV1.class);
        assertRoundTrip("capability-result.completed.json", CapabilityResultV1.class);
        assertRoundTrip("capability-result.failed.json", CapabilityResultV1.class);
        assertRoundTrip("capability-result.need-confirmation.json", CapabilityResultV1.class);
        assertRoundTrip("evidence-receipt.query.json", EvidenceReceiptV1.class);
    }

    @Test
    void unknownContractFieldsFailBeforeProviderInvocation() throws IOException {
        String json = Files.readString(invalidFixture("capability-request.unknown-field.json"));
        assertThrows(JsonProcessingException.class,
                () -> JSON.readValue(json, CapabilityRequestV1.class));
    }

    @Test
    void failedResultRequiresStructuredError() throws IOException {
        String json = Files.readString(invalidFixture("capability-result.failed-without-error.json"));
        assertThrows(JsonProcessingException.class,
                () -> JSON.readValue(json, CapabilityResultV1.class));
    }

    @Test
    void evidenceRejectsImpossibleTiming() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceReceiptV1(
                EvidenceReceiptV1.SCHEMA_VERSION,
                "receipt-1",
                "request-1",
                "trace-1",
                "account.balance.query",
                CapabilityRequestV1.Operation.QUERY,
                "provider-1",
                "account-system",
                CapabilityResultV1.Status.COMPLETED,
                null,
                Instant.parse("2026-09-01T01:00:01Z"),
                Instant.parse("2026-09-01T01:00:00Z")));
    }

    @Test
    void evidenceFactoryBindsRequestResultAndProvider() throws IOException {
        CapabilityRequestV1 request = JSON.readValue(
                validFixture("capability-request.query.json").toFile(), CapabilityRequestV1.class);
        CapabilityResultV1 result = JSON.readValue(
                validFixture("capability-result.completed.json").toFile(), CapabilityResultV1.class);
        Instant startedAt = Instant.parse("2026-09-01T00:59:59Z");
        Instant finishedAt = Instant.parse("2026-09-01T01:00:00Z");

        EvidenceReceiptV1 receipt = EvidenceReceipts.from(
                request, result, "provider-demo", startedAt, finishedAt);

        assertEquals(request.capabilityId(), receipt.capabilityId());
        assertEquals(result.sourceSystem(), receipt.sourceSystem());
        assertEquals(result.status(), receipt.resultStatus());
        assertEquals("provider-demo", receipt.providerId());
        assertTrue(JSON.readTree(JSON.writeValueAsString(receipt)).path("startedAt").isTextual());
    }

    @Test
    void evidenceFactoryRejectsMismatchedRequestIdentity() throws IOException {
        CapabilityRequestV1 request = JSON.readValue(
                validFixture("capability-request.query.json").toFile(), CapabilityRequestV1.class);
        CapabilityResultV1 fixture = JSON.readValue(
                validFixture("capability-result.completed.json").toFile(), CapabilityResultV1.class);
        CapabilityResultV1 mismatched = new CapabilityResultV1(
                fixture.schemaVersion(),
                "different-request",
                fixture.traceId(),
                fixture.status(),
                fixture.content(),
                fixture.data(),
                fixture.missingInputs(),
                fixture.nextActions(),
                fixture.sourceSystem(),
                fixture.asOf(),
                fixture.task(),
                fixture.error());

        assertThrows(IllegalArgumentException.class, () -> EvidenceReceipts.from(
                request,
                mismatched,
                "provider-demo",
                Instant.parse("2026-09-01T00:59:59Z"),
                Instant.parse("2026-09-01T01:00:00Z")));
    }

    @Test
    void schemasAreStrictTopLevelJsonObjects() throws IOException {
        List<Path> schemas;
        try (Stream<Path> files = Files.walk(ROOT.resolve("contracts/json-schema"))) {
            schemas = files.filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .toList();
        }
        assertEquals(5, schemas.size());
        for (Path schema : schemas) {
            JsonNode root = JSON.readTree(schema.toFile());
            assertEquals("https://json-schema.org/draft/2020-12/schema", root.path("$schema").asText());
            assertEquals("object", root.path("type").asText());
            assertFalse(root.path("additionalProperties").asBoolean(true), schema.toString());
            assertTrue(root.path("required").isArray(), schema.toString());
        }
    }

    @Test
    void solutionBuildInputKeepsCompositionReferencesAndSecretsOut() throws IOException {
        JsonNode input = JSON.readTree(ROOT.resolve(
                "examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json").toFile());

        assertEquals("finance-agent-solution-build.v1", input.path("schemaVersion").asText());
        assertEquals("openjiuwen", input.path("runtime").path("family").asText());
        assertEquals("java", input.path("runtime").path("language").asText());
        assertEquals("customer-self-service", input.path("profileRef").path("id").asText());
        assertEquals("wealth-customer-self-service",
                input.path("domainPackRefs").path(0).path("id").asText());
        assertEquals("non-ai-banking-reference",
                input.path("providerRefs").path(0).path("id").asText());
        assertTrue(input.path("modelRef").asText().startsWith("env://"));
        String serialized = JSON.writeValueAsString(input).toLowerCase();
        assertFalse(serialized.contains("apikey"));
        assertFalse(serialized.contains("password"));
        assertFalse(serialized.contains("secret"));
    }

    @Test
    void providerSpiKeepsQueryAndTaskAsSeparateEntrypoints() throws IOException {
        CapabilityRequestV1 request = JSON.readValue(
                validFixture("capability-request.query.json").toFile(), CapabilityRequestV1.class);
        CapabilityResultV1 expected = JSON.readValue(
                validFixture("capability-result.completed.json").toFile(), CapabilityResultV1.class);
        assertTrue(expected.data().containsKey("nextPageToken"));
        FinanceProvider provider = new FinanceProvider() {
            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 actual) {
                assertEquals(CapabilityRequestV1.Operation.QUERY, actual.operation());
                return expected;
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 actual) {
                throw new AssertionError("query must not enter task execution");
            }
        };

        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request).status());
    }

    private static <T> void assertRoundTrip(String name, Class<T> type) throws IOException {
        T value = JSON.readValue(validFixture(name).toFile(), type);
        String serialized = JSON.writeValueAsString(value);
        T reparsed = JSON.readValue(serialized, type);
        assertEquals(value, reparsed);
    }

    private static Path validFixture(String name) {
        return ROOT.resolve("conformance/fixtures/v1/valid").resolve(name);
    }

    private static Path invalidFixture(String name) {
        return ROOT.resolve("conformance/fixtures/v1/invalid").resolve(name);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts/json-schema"))
                    && Files.isDirectory(current.resolve("packages/java/core"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("finance-agent-harness repository root not found");
    }
}
