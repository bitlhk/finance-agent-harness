package io.github.bitlhk.finance.harness.providers.knowledge;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeProviderTest {
    static final List<Map<String,Object>> RECORDS = new ArrayList<>();
    SyntheticKnowledgeService service;
    AtomicReference<ManagedProviderFactoryV01.SourceSession> sessions;
    AtomicReference<BoundFinanceProvider.Grant> grants;
    ManagedProviderFactoryV01.Connection connection;
    KnowledgeFactoryV01 factory;
    ManagedProviderFactoriesV01 factories;
    FinanceProvider provider;
    final AtomicReference<ObservationRun> observation = new AtomicReference<>();
    final List<ObservationEventV01> events = new ArrayList<>();
    final List<ManagedProviderFactoryV01.HttpAttempt> attempts = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        service = new SyntheticKnowledgeService();
        sessions = new AtomicReference<>(new ManagedProviderFactoryV01.SourceSession(service.token,"probe-subject",
            "probe-principal","probe-session",Instant.now().plusSeconds(300)));
        grants = new AtomicReference<>(new BoundFinanceProvider.Grant("probe-principal",FinanceContextV1.PrincipalType.SYSTEM,
            Set.of("probe-subject"),Set.of("knowledge-probe"),Set.of("knowledge:read","knowledge:rule"),
            "probe-session","synthetic-host-authority",Instant.now().plusSeconds(300)));
        connection = new ManagedProviderFactoryV01.Connection(KnowledgeFactoryV01.KIND,service.endpoint(),
            Duration.ofSeconds(2),sessions::get,observation::get,attempts::add);
        factory = new KnowledgeFactoryV01(binding("synthetic-policy"));
        factories = new ManagedProviderFactoriesV01(List.of(factory));
        provider = managed(factory);
    }
    KnowledgeBinding binding(String product) throws Exception {
        Path file=Files.createTempFile("knowledge-binding-",".json");
        try {
            byte[] bytes=KnowledgeBinding.JSON.writeValueAsBytes(Map.of("schemaVersion","knowledge-binding.v0.1",
                "productId",product,"manifest",SyntheticKnowledgeService.MANIFEST,"businessDate",SyntheticKnowledgeService.DATE));
            Files.write(file,bytes);
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return KnowledgeBinding.load(file.toString(),digest);
        } finally { Files.delete(file); }
    }
    FinanceProvider managed(KnowledgeFactoryV01 f) {
        var fs = new ManagedProviderFactoriesV01(List.of(f));
        return fs.createBound(Map.of(KnowledgeProvider.ID,new ManagedProviderFactoriesV01.Binding(connection,grants::get)),
            Set.of(KnowledgeProvider.ID),fs.configurationDigests(Set.of(KnowledgeProvider.ID)),
            Map.of(KnowledgeProvider.ID,ManagedProviderFactoriesV01.connectionDigest(connection))).get(KnowledgeProvider.ID);
    }
    CapabilityRequestV1 request(String capability, Map<String,Object> args) {
        events.clear(); attempts.clear();
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"request-"+UUID.randomUUID(),"trace-"+UUID.randomUUID(),
            "probe-principal",FinanceContextV1.PrincipalType.SYSTEM,"isolated-test","knowledge-probe","probe-subject",
            "probe-session",FinanceContextV1.AuthenticationLevel.STRONG,Set.of("knowledge:read","knowledge:rule"));
        observation.set(new ObservationRun(context.requestId(),"knowledge-probe-boot","knowledge-probe-instance",events::add));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,capability,CapabilityRequestV1.Operation.QUERY,
            context,args,null,null,Map.of(),null);
    }
    CapabilityResultV1 search() { return provider.query(request(KnowledgeProvider.SEARCH,Map.of("question","合成期限上限是什么？"))); }
    void record(String name, CapabilityResultV1 result) {
        RECORDS.add(Map.of("case",name,"result",HarnessJson.mapper().convertValue(result,Map.class),
            "cumulativeServiceCalls",service.calls.get(),"level","H0_MANAGED_PROVIDER_HTTP_NOT_AGENT_INSTALL",
            "observationEvents",HarnessJson.mapper().convertValue(events,List.class),
            "httpAttempts",HarnessJson.mapper().convertValue(attempts,List.class)));
    }
    @AfterEach void stop() { service.close(); }
    @AfterAll static void save() throws Exception {
        Files.createDirectories(Path.of("target"));
        KnowledgeBinding.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/probe-records.json").toFile(),RECORDS);
    }
    @Test void realHttpReturnsCorrelatedReceipt() {
        var r=search(); assertEquals(CapabilityResultV1.Status.COMPLETED,r.status());
        var receipt=(Map<?,?>)r.data().get("knowledgeReceipt");
        assertEquals(r.requestId(),receipt.get("requestId")); assertEquals(r.traceId(),receipt.get("traceId"));
        assertEquals(SyntheticKnowledgeService.MANIFEST,receipt.get("manifest"));
        assertEquals(0,observation.get().droppedEvents());
        assertEquals(r.requestId(),events.getLast().requestId());
        record("evidence-query",r); assertEquals(1,service.calls.get());
    }
    @Test void twoProductsUseSameFactoryWithoutCodeChanges() throws Exception {
        provider=managed(new KnowledgeFactoryV01(binding("synthetic-faq")));
        var r=search(); assertEquals(CapabilityResultV1.Status.COMPLETED,r.status()); record("faq-same-provider",r);
    }
    @Test void ruleProbeCannotBeInstalledOrInvokedEvenWithLegacyScope() {
        assertEquals(Set.of(KnowledgeProvider.SEARCH), factory.requirements().keySet());
        assertThrows(IllegalArgumentException.class, () -> factory.sourceScopes(Set.of("knowledge.rule.evaluate")));
        assertThrows(SecurityException.class, () -> provider.query(request("knowledge.rule.evaluate",Map.of("months",36))));
        var direct = factory.create(connection, Map.of());
        assertFalse(direct.descriptor().supports("knowledge.rule.evaluate", CapabilityRequestV1.Operation.QUERY));
        assertEquals(CapabilityResultV1.Status.FAILED,
            direct.query(request("knowledge.rule.evaluate",Map.of("months",36))).status());
        assertEquals(0, service.calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"revoked","business403","manifest","request","oversize"})
    void rejectsUpstreamBusinessErrorsAndMismatchedReceipts(String fault) {
        service.fault.set(fault);var r=search();assertEquals(CapabilityResultV1.Status.FAILED,r.status());
        assertTrue(r.data().isEmpty());record(fault,r);
    }
    @Test void preservesSafeHttpRejectionWithoutDisclosingEvidenceOrRetrying() {
        service.fault.set("http-retrieval-error");
        var result = search();
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("RAGFLOW_UNAVAILABLE", result.error().sourceCode());
        assertFalse(result.error().retryable());
        assertTrue(result.data().isEmpty());
        assertEquals(1, service.calls.get());
        record("http-retrieval-error", result);
    }
    @ParameterizedTest @ValueSource(strings={"serviceUrl","manifest","productId","principalId","scopes","businessDate"})
    void modelCannotOverrideBinding(String key) {
        var r=provider.query(request(KnowledgeProvider.SEARCH,Map.of("question","test",key,"injected")));
        assertEquals(CapabilityResultV1.Status.FAILED,r.status());assertEquals(0,service.calls.get());record("override-"+key,r);
    }
    @Test void rejectsUninstalledToolAndMissingAuthority() {
        assertThrows(SecurityException.class,()->provider.query(request("uninstalled.tool",Map.of())));
        grants.set(null);assertThrows(SecurityException.class,this::search);assertEquals(0,service.calls.get());
    }
    @Test void rejectsMissingTrustedContextAndLateRevocation() {
        var saved=sessions.get();sessions.set(null);assertThrows(SecurityException.class,this::search);
        sessions.set(saved);service.afterRead=()->sessions.set(null);
        var r=search();assertEquals(CapabilityResultV1.Status.FAILED,r.status());record("late-revocation",r);
    }
    @Test void badInputNotCoerced() {
        var r=provider.query(request(KnowledgeProvider.SEARCH,Map.of("question",true)));
        assertEquals(CapabilityResultV1.Status.FAILED,r.status());assertEquals(0,service.calls.get());record("boolean-question",r);
    }
    @Test void factoryDiscoveredButConfigAndConnectionMustMatch() {
        assertTrue(ServiceLoader.load(ManagedProviderFactoryV01.class).stream().anyMatch(p->p.type()==KnowledgeFactoryV01.class));
        assertThrows(IllegalArgumentException.class,()->factories.create(connection,grants::get,Set.of(KnowledgeProvider.ID),Map.of()));
        assertThrows(IllegalArgumentException.class,()->new KnowledgeFactoryV01(Map.of()).configurationDigests());
        assertThrows(IllegalArgumentException.class,()->factories.createBound(
            Map.of(KnowledgeProvider.ID,new ManagedProviderFactoriesV01.Binding(connection,grants::get)),
            Set.of(KnowledgeProvider.ID),factories.configurationDigests(Set.of(KnowledgeProvider.ID)),Map.of()));
    }
    @Test void missingScopeAndExpiredSessionDeniedBeforeNetwork() {
        var old=grants.get();
        grants.set(new BoundFinanceProvider.Grant(old.principalId(),old.principalType(),old.subjects(),old.purposes(),
            Set.of(),old.sessionRef(),old.authorityRef(),old.expiresAt()));
        assertThrows(SecurityException.class,this::search);grants.set(old);
        sessions.set(new ManagedProviderFactoryV01.SourceSession(service.token,"probe-subject","probe-principal",
            "probe-session",Instant.now().minusSeconds(1)));
        assertThrows(SecurityException.class,this::search);assertEquals(0,service.calls.get());
    }
    @Test void frozenConfigHasStrictDateAndDigest() throws Exception {
        var file=Files.createTempFile("knowledge-binding-",".json");
        try {
            Files.writeString(file,"{\"schemaVersion\":\"knowledge-binding.v0.1\",\"productId\":\"synthetic-policy\",\"manifest\":\""+
                SyntheticKnowledgeService.MANIFEST+"\",\"businessDate\":\"2026-02-30\"}");
            String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            assertThrows(IllegalArgumentException.class,()->KnowledgeBinding.load(file.toString(),sha));
        } finally {Files.delete(file);}
    }
}
