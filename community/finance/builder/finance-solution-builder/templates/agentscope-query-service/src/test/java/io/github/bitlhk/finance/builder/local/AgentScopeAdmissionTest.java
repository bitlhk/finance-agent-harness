package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.ObservationEventV01;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.*;
import java.util.*;
import org.junit.After;
import org.junit.Test;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;
import static org.junit.Assert.*;

public class AgentScopeAdmissionTest {
    private static final String COMPOSED = "sha256:" + "a".repeat(64);
    private static final String INVENTORY = "sha256:" + "b".repeat(64);
    private static final String WRONG = "sha256:" + "c".repeat(64);
    private final List<Path> roots = new ArrayList<>();

    @After public void removeFixtures() throws Exception {
        for (Path root : roots) try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private Path fixture() throws Exception {
        Path root = Files.createTempDirectory("scope-digest-"); roots.add(root);
        var mapper = HarnessJson.mapper();
        var runtime = Map.of("family", "test-runtime", "language", "java");
        mapper.writeValue(root.resolve("host-support.v0.1.json").toFile(), Map.of("runtime", runtime));
        var identity = Map.of("digest", COMPOSED, "supportDigest", "sha256:" +
                ExecutionArchive.hash(Files.readAllBytes(root.resolve("host-support.v0.1.json"))));
        var binding = Map.of("managedTemplate", identity);
        mapper.writeValue(root.resolve("component-plan.json").toFile(), Map.of(
                "schemaVersion", "finance-local-component-plan.v0.1", "runtime", runtime,
                "managedTemplate", identity, "runtimeBinding", binding));
        mapper.writeValue(root.resolve("service-lock.json").toFile(), Map.of(
                "hostTemplateDigest", INVENTORY, "managedTemplateDigest", COMPOSED,
                "runtimeBinding", binding, "files", Map.of()));
        relock(root);
        return root;
    }

    private ObjectNode read(Path root, String file) throws Exception {
        return (ObjectNode) HarnessJson.mapper().readTree(root.resolve(file).toFile());
    }

    private void write(Path root, String file, ObjectNode value) throws Exception {
        HarnessJson.mapper().writeValue(root.resolve(file).toFile(), value);
    }

    private void relock(Path root) throws Exception {
        var lock = read(root, "service-lock.json");
        for (String file : List.of("component-plan.json", "host-support.v0.1.json"))
            ((ObjectNode) lock.path("files")).put(file, ExecutionArchive.hash(Files.readAllBytes(root.resolve(file))));
        write(root, "service-lock.json", lock);
    }

    private void check(Path root) throws Exception {
        var guard = ComponentAdmission.class.getDeclaredMethod("lockedPlan", Path.class);
        guard.setAccessible(true);
        try { guard.invoke(null, root); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    private void rejected(Path root) throws Exception {
        relock(root);
        assertEquals("COMPONENT_HOST_SUPPORT_MISMATCH", assertThrows(SecurityException.class, () -> check(root)).getMessage());
    }

    @Test public void managedComposedIdentityAcceptsIndependentCollectorInventory() throws Exception {
        check(fixture());
    }

    @Test public void collectorInventoryCannotReplaceManagedPlanIdentity() throws Exception {
        Path root = fixture(); var plan = read(root, "component-plan.json");
        ((ObjectNode) plan.path("managedTemplate")).put("digest", INVENTORY);
        write(root, "component-plan.json", plan); rejected(root);
    }

    @Test public void everyManagedIdentityMustAgree() throws Exception {
        for (String field : List.of("lockProjection", "lockBinding", "planBinding", "plan")) {
            Path root = fixture(); String file = field.startsWith("lock") ? "service-lock.json" : "component-plan.json";
            var value = read(root, file);
            if (field.equals("lockProjection")) value.put("managedTemplateDigest", WRONG);
            else if (field.equals("plan")) ((ObjectNode) value.path("managedTemplate")).put("digest", WRONG);
            else ((ObjectNode) value.path("runtimeBinding").path("managedTemplate")).put("digest", WRONG);
            write(root, file, value); rejected(root);
        }
    }

    @Test public void missingManagedIdentityCannotFallBackToLegacyInventory() throws Exception {
        for (String field : List.of("managedTemplateDigest", "runtimeBinding", "planBinding", "allLockFields")) {
            Path root = fixture(); String file = field.equals("planBinding") ? "component-plan.json" : "service-lock.json";
            var value = read(root, file);
            if (field.equals("allLockFields")) { value.remove("runtimeBinding"); value.remove("managedTemplateDigest"); }
            else value.remove(field.equals("planBinding") ? "runtimeBinding" : field);
            write(root, file, value); rejected(root);
        }
    }

    @Test public void equalButMalformedManagedDigestsAreRejected() throws Exception {
        for (String invalid : List.of("", "actual", "sha256:bad")) {
            Path root = fixture(); var lock = read(root, "service-lock.json"); var plan = read(root, "component-plan.json");
            lock.put("managedTemplateDigest", invalid);
            ((ObjectNode) lock.path("runtimeBinding").path("managedTemplate")).put("digest", invalid);
            ((ObjectNode) plan.path("runtimeBinding").path("managedTemplate")).put("digest", invalid);
            ((ObjectNode) plan.path("managedTemplate")).put("digest", invalid);
            write(root, "service-lock.json", lock); write(root, "component-plan.json", plan); rejected(root);
        }
    }

    private void legacy(boolean python) throws Exception {
        Path root = fixture(); var lock = read(root, "service-lock.json"); var plan = read(root, "component-plan.json");
        lock.remove("runtimeBinding"); lock.remove("managedTemplateDigest"); plan.remove("runtimeBinding");
        if (python) lock.putObject("javaBridge").put("hostTemplateDigest", COMPOSED);
        else lock.put("hostTemplateDigest", COMPOSED);
        write(root, "service-lock.json", lock); write(root, "component-plan.json", plan); relock(root); check(root);
        ((ObjectNode) plan.path("managedTemplate")).put("digest", WRONG);
        write(root, "component-plan.json", plan); rejected(root);
    }

    @Test public void legacyJavaKeepsItsTemplateIdentityContract() throws Exception { legacy(false); }
    @Test public void legacyPythonKeepsItsBridgeIdentityContract() throws Exception { legacy(true); }

    @Test public void repeatedPlanPreservesOriginalBytesAndRejectsChangedIdentity() throws Exception {
        Path output = Files.createTempDirectory("scope-plan-").resolve("plan.json");
        try {
            var first = new LinkedHashMap<String, Object>(); first.put("b", "original"); first.put("a", "READY");
            AgentScopeAdmission.publishPlan(output, first);
            byte[] original = Files.readAllBytes(output);
            var reordered = new LinkedHashMap<String, Object>(); reordered.put("a", "READY"); reordered.put("b", "original");
            AgentScopeAdmission.publishPlan(output, reordered);
            assertArrayEquals(original, Files.readAllBytes(output));
            reordered.put("b", "changed");
            assertThrows(SecurityException.class, () -> AgentScopeAdmission.publishPlan(output, reordered));
            assertArrayEquals(original, Files.readAllBytes(output));
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(output.getParent()); }
    }
    private ObservationEventV01 event(long sequence, String span, String component, String operation, Phase phase) {
        return new ObservationEventV01(VERSION, "request", "boot", "instance", sequence, span, null,
                component, operation, phase, phase == Phase.START ? Outcome.PENDING : Outcome.RETURNED,
                Dispatch.NOT_OBSERVED, Instant.now(), sequence, phase == Phase.END ? 1L : null, Map.of());
    }

    @Test public void modelSpanTrackingNeverThrowsIntoTheObservationSink() {
        var active = new AtomicReference<String>();
        NativeConversation.trackModelSpan(active, event(1, "model-1", "model", "client-prepared-call", Phase.START));
        assertEquals("model-1", active.get());
        NativeConversation.trackModelSpan(active, event(2, "task-1", "runtime-tool", "task", Phase.END));
        assertEquals("model-1", active.get());
        NativeConversation.trackModelSpan(active, event(3, "model-1", "model", "client-prepared-call", Phase.END));
        assertNull(active.get());
        NativeConversation.trackModelSpan(active, event(4, "model-2", "model", "client-prepared-call", Phase.START));
        NativeConversation.trackModelSpan(active, event(5, "model-overlap", "model", "client-prepared-call", Phase.START));
        assertNull("ambiguous overlap disables body correlation without dropping the trace event", active.get());
    }
}
