package io.github.bitlhk.finance.harness.wealth;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.wealth.WealthFactsV1.*;

class WealthFactsTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
    static final WealthSourceSemantics BASIS = new WealthSourceSemantics("fixture-bank", "fixture:source-v1",
            "fixture:dictionary-v1", "FIXTURE_BANK_ASSETS", "CNY", "fixture-scale", "assessment-2", ZoneId.of("Asia/Shanghai"), true);
    static final String MOBILE = """
            {"wealthAggregate":{"overview":{"totalAssets":1000000.12345678,"currency":"CNY",
            "asOf":"2026-09-01T00:00:00Z","missingSourceSystems":[]}},
            "riskAssessment":{"riskLevel":"C3","assessmentVersion":"assessment-2","expiresOn":"2027-03-01"}}
            """;
    static final String EA = """
            {"previsitContext":{"schema":"ea.wealth-previsit-context.v1","status":"ready",
            "customer":{"customerId":"customer-001","name":"PRIVATE_NAME","maskedPhone":"PRIVATE_PHONE",
            "confirmedNeeds":"PRIVATE_NOTES","riskLevel":"C3","assessmentExpiresAt":"2027-03-01",
            "portfolio":{"summary":{"totalAssets":1000000.12345678}}},
            "knowledgeBasis":{"private":"PRIVATE_KNOWLEDGE"},
            "evidence":{"customerId":"customer-001","portfolioDataAsOf":"2026-09-01T00:00:00Z"}}}
            """;

    @TestFactory Stream<DynamicTest> sameContractFixturesAreRejectedOrAcceptedByJava() throws Exception {
        Path dir = root().resolve("conformance/fixtures/wealth/v0.1");
        try (var files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream().map(path ->
                    DynamicTest.dynamicTest(path.getParent().getFileName() + "/" + path.getFileName(), () -> {
                        String input = Files.readString(path);
                        if (path.getParent().getFileName().toString().equals("invalid"))
                            assertThrows(Exception.class, () -> WealthJson.read(input));
                        else assertEquals(VERSION, WealthJson.read(input).schemaVersion());
                    }));
        }
    }
    @Test void equalControlledSemanticsUseSameConsumerAndExcludePrivateData() throws Exception {
        var mobile = map(WealthFactsMapper.Format.MOBILE, json(MOBILE), BASIS);
        var ea = map(WealthFactsMapper.Format.EA, json(EA), BASIS);
        assertEquals(mobile.assets(), ea.assets()); assertEquals(mobile.risk(), ea.risk());
        assertEquals(WealthFactsConsumer.describe(mobile), WealthFactsConsumer.describe(ea));
        assertEquals("1000000.12345678", ea.assets().amounts().getFirst().money().value());
        assertTrue(ea.gaps().isEmpty());
        assertFalse(WealthJson.mapper().writeValueAsString(ea).contains("PRIVATE"));
        assertEquals("NOT_EVALUATED", WealthFactsConsumer.describe(ea).suitability());
    }
    @Test void absentEaDictionaryDoesNotInventCurrencyCoverageOrRiskScale() throws Exception {
        var unknown = new WealthSourceSemantics("ea", "upstream:ea.wealth-previsit-context.v1", "project:unknown-metadata",
                null, null, null, null, null, false);
        var facts = map(WealthFactsMapper.Format.EA, json(EA), unknown);
        assertTrue(facts.assets().amounts().isEmpty()); assertNull(facts.risk().scheme());
        assertEquals("C3", facts.risk().level());
        assertEquals("PARTIAL_FOR_DESCRIPTION", WealthFactsConsumer.describe(facts).taskReadiness());
        assertTrue(facts.gaps().stream().anyMatch(g -> g.code().equals("CURRENCY_NOT_ESTABLISHED")));
    }
    @Test void zeroIsNotMissingAndExpiredRiskIsNotExecutionUnknown() throws Exception {
        var zero = json(MOBILE); ((ObjectNode)zero.at("/wealthAggregate/overview")).put("totalAssets", 0);
        ((ObjectNode)zero.get("riskAssessment")).put("expiresOn", "2026-09-04");
        var facts = map(WealthFactsMapper.Format.MOBILE, zero, BASIS);
        assertEquals("0", facts.assets().amounts().getFirst().money().value());
        assertEquals(Availability.EXPIRED, facts.risk().availability());
        ((ObjectNode)zero.at("/wealthAggregate/overview")).remove("totalAssets");
        assertEquals(Availability.MISSING, map(WealthFactsMapper.Format.MOBILE, zero, BASIS).assets().availability());
    }
    @Test void missingUnavailableNotCoveredAndConflictAreDistinct() throws Exception {
        var input = json(MOBILE); input.remove("riskAssessment");
        assertEquals(Availability.MISSING, map(WealthFactsMapper.Format.MOBILE, input, BASIS).risk().availability());
        input.putObject("riskAssessment");
        input.putArray("sourceSnapshots").addObject().put("section", "RISK_ASSESSMENT").put("status", "UNAVAILABLE");
        assertEquals(Availability.UNAVAILABLE, map(WealthFactsMapper.Format.MOBILE, input, BASIS).risk().availability());
        ((ObjectNode)input.at("/wealthAggregate/overview")).putArray("missingSourceSystems").add("other-bank");
        assertTrue(map(WealthFactsMapper.Format.MOBILE, input, BASIS).gaps().stream().anyMatch(g -> g.reason() == Availability.NOT_COVERED));
        ((ObjectNode)input.at("/wealthAggregate/overview")).put("currency", "USD");
        assertEquals(Availability.CONFLICT, map(WealthFactsMapper.Format.MOBILE, input, BASIS).assets().availability());
    }
    @Test void differentCurrenciesScopesAndValuationTimesMustNotBeDeclaredEquivalent() throws Exception {
        var other = new WealthSourceSemantics("fixture-bank", "fixture:source-v1", "fixture:other-dictionary",
                "INVESTMENTS_ONLY", "USD", "different-scale", "assessment-2", ZoneId.of("UTC"), false);
        var input = json(MOBILE); ((ObjectNode)input.at("/wealthAggregate/overview")).put("currency", "USD");
        var facts = map(WealthFactsMapper.Format.MOBILE, input, other);
        assertNotEquals(map(WealthFactsMapper.Format.MOBILE, json(MOBILE), BASIS).assets(), facts.assets());
        assertEquals("USD", facts.assets().amounts().getFirst().money().currency());
        assertEquals("C3", facts.risk().level()); assertEquals("different-scale", facts.risk().scheme());
    }
    @Test void malformedResponsesWrongCustomerAndOverPrecisionFailClosed() throws Exception {
        var input = json(EA); ((ObjectNode)input.at("/previsitContext/customer")).put("customerId", "other");
        assertThrows(SecurityException.class, () -> map(WealthFactsMapper.Format.EA, input, BASIS));
        assertThrows(IllegalArgumentException.class, () -> map(WealthFactsMapper.Format.MOBILE, json("{}"), BASIS));
        var tooPrecise = json(MOBILE); ((ObjectNode)tooPrecise.at("/wealthAggregate/overview")).put("totalAssets", "1.123456789");
        assertThrows(IllegalArgumentException.class, () -> map(WealthFactsMapper.Format.MOBILE, tooPrecise, BASIS));
    }
    @Test void dateOnlyExpiryUsesDeclaredZoneAndExpiresAfterThatDay() throws Exception {
        var input = json(MOBILE); ((ObjectNode)input.get("riskAssessment")).put("expiresOn", "2026-09-05");
        var result = result(input);
        assertEquals(Availability.AVAILABLE, map(WealthFactsMapper.Format.MOBILE, input, BASIS).risk().availability());
        var after = Clock.fixed(Instant.parse("2026-09-05T16:00:00Z"), ZoneOffset.UTC);
        assertEquals(Availability.EXPIRED, WealthFactsMapper.map(WealthFactsMapper.Format.MOBILE, result,
                "customer-001", BASIS, SourceKind.CONTROLLED_FIXTURE, after).risk().availability());
    }
    @Test void malformedStalenessAndExtremeDecimalExponentFailBeforeFormatting() throws Exception {
        var stale = json(MOBILE); ((ObjectNode) stale.at("/wealthAggregate/overview")).put("stale", "maybe");
        assertThrows(IllegalArgumentException.class, () -> map(WealthFactsMapper.Format.MOBILE, stale, BASIS));
        var extreme = json(MOBILE); ((ObjectNode) extreme.at("/wealthAggregate/overview")).put("totalAssets", "1e100000000");
        assertThrows(IllegalArgumentException.class, () -> map(WealthFactsMapper.Format.MOBILE, extreme, BASIS));
    }
    @Test void emptyGapListCannotClaimCompletenessWhenRequiredFactsAreMissing() throws Exception {
        var original = map(WealthFactsMapper.Format.MOBILE, json(MOBILE), BASIS);
        var incomplete = new WealthFactsV1(VERSION, original.subject(), original.assets(),
                new Risk(Availability.MISSING, null, null, null, null, null, null), List.of(), original.source());
        assertEquals("PARTIAL_FOR_DESCRIPTION", WealthFactsConsumer.describe(incomplete).taskReadiness());
        assertEquals("NOT_EVALUATED", WealthFactsConsumer.describe(incomplete).suitability());
    }
    static ObjectNode json(String input) throws Exception { return (ObjectNode)WealthJson.mapper().readTree(input); }
    static WealthFactsV1 map(WealthFactsMapper.Format format, ObjectNode data, WealthSourceSemantics semantics) {
        return WealthFactsMapper.map(format, result(data), "customer-001", semantics, SourceKind.CONTROLLED_FIXTURE, CLOCK);
    }
    static CapabilityResultV1 result(ObjectNode data) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, "fixture-request", "fixture-trace",
                CapabilityResultV1.Status.COMPLETED, "fixture", WealthJson.mapper().convertValue(data,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}), List.of(), List.of(), "fixture-source", CLOCK.instant(), null, null);
    }
    static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("capability-packs"))) root = root.getParent();
        return Objects.requireNonNull(root);
    }
}
