package io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost;

import io.github.bitlhk.finance.harness.assembly.AssetDigests;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01;
import io.github.bitlhk.finance.harness.integrations.agentscope.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;

/** Offline operator tool: verifies test reports and tested class bytes before emitting Host qualification. */
public final class QualificationWorker {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath(), raw = Path.of(args[1]).toRealPath(), out = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(out);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var xml = factory.newDocumentBuilder().parse(root.resolve("integrations/agentscope-java/target/surefire-reports/TEST-io.github.bitlhk.finance.harness.integrations.agentscope.AgentScopeQueryTest.xml").toFile());
        var suite = xml.getDocumentElement();
        if (!"0".equals(suite.getAttribute("failures")) || !"0".equals(suite.getAttribute("errors")) || !"0".equals(suite.getAttribute("skipped"))) throw new IllegalStateException("JUNIT_NOT_PASS");
        int testCount = Integer.parseInt(suite.getAttribute("tests"));
        var provider = IsolatedHost.provider(URI.create("http://127.0.0.1:9"), "inventory", "inventory", "inventory");
        var actual = RuntimeInventory.inspect(List.of(provider));
        Map<String, List<RuntimeQualification.Evidence>> proofs = new TreeMap<>();
        int rows = 0; long modelHttp = 0, bankHttp = 0;
        var verified = new HashSet<String>();
        try (var files = Files.list(raw)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var row = HarnessJson.mapper().readTree(file.toFile());
                if (!"PASS".equals(row.path("status").asText())) throw new IllegalStateException("RAW_TEST_FAILED:" + file);
                var tested = HarnessJson.mapper().readerForListOf(RuntimeInventory.Artifact.class).<List<RuntimeInventory.Artifact>>readValue(row.path("runtimeArtifacts"));
                for (var artifact : actual) {
                    var old = tested.stream().filter(a -> a.role().equals(artifact.role())).findFirst().orElseThrow();
                    String pair = old.source() + old.digest() + artifact.source() + artifact.digest();
                    if (verified.add(pair)) verifyTestedArtifact(old, artifact);
                }
                Path destination = out.resolve("raw").resolve(file.getFileName()); Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
                var ref = new RuntimeQualification.Evidence("raw/" + file.getFileName(), AssetDigests.sha256(destination).substring(7));
                for (var id : row.path("checkIds")) proofs.computeIfAbsent(id.asText(), ignored -> new ArrayList<>()).add(ref);
                for (var session : row.path("sessions")) { modelHttp += session.path("modelHttp").size(); bankHttp += session.path("bankHttp").size(); }
                rows++;
            }
        }
        if (rows != testCount || !proofs.keySet().containsAll(RuntimeQualification.REQUIRED_CHECKS)) throw new IllegalStateException("TEST_EVIDENCE_INCOMPLETE");
        var declaration = AgentScopeObservationCollector.declaration(true);
        var checks = proofs.entrySet().stream().map(e -> new RuntimeQualification.Check(e.getKey(), true, e.getValue())).toList();
        Path proofFile = out.resolve("agentscope-query-evidence.json");
        IsolatedHost.write(proofFile, new RuntimeQualification.Proof("agentscope-query-qualification-evidence.v0.1", "agentscope", "2.0.3", "PASS", declaration, actual, checks));
        var collector = new RuntimeQualification.Collector(declaration.collectorId(), RuntimeQualification.identity(actual, declaration.identityDigest()),
                declaration.capabilities().stream().map(ObservationCapabilitiesV01.Capability::semanticId).collect(java.util.stream.Collectors.toSet()), true,
                List.of(new RuntimeQualification.Evidence(proofFile.getFileName().toString(), AssetDigests.sha256(proofFile).substring(7))));
        Path qualification = out.resolve("agentscope-observation-qualification.json");
        IsolatedHost.write(qualification, new RuntimeQualification.Report(RuntimeQualification.VERSION, List.of(collector)));
        String digest = AssetDigests.sha256(qualification);
        RuntimeQualification.load(qualification, digest).requireCurrent(actual, declaration.identityDigest());
        Files.writeString(out.resolve("qualification.sha256"), digest + "\n");
        IsolatedHost.write(out.resolve("summary.json"), Map.of("status", "PASS", "tests", rows, "requiredChecks", RuntimeQualification.REQUIRED_CHECKS,
                "modelHttpRequests", modelHttp, "bankHttpRequestsIncludingDirectComparison", bankHttp,
                "qualificationDigest", digest, "realModelGate", "NOT_RUN", "qualificationNature", "TRUSTED_OPERATOR_ASSERTION_NOT_CRYPTOGRAPHIC_TEST_ATTESTATION"));
        System.out.println("Qualified controlled HTTP: tests=" + rows + " modelHttp=" + modelHttp + " bankHttp=" + bankHttp + " sha256=" + digest);
    }
    private static void verifyTestedArtifact(RuntimeInventory.Artifact tested, RuntimeInventory.Artifact packaged) throws Exception {
        if (!tested.coordinate().equals(packaged.coordinate()) || !tested.version().equals(packaged.version())) throw new IllegalStateException("TEST_ARTIFACT_VERSION_CHANGED");
        Path before = Path.of(URI.create(tested.source())), after = Path.of(URI.create(packaged.source()));
        if (!Files.isDirectory(before)) {
            if (!tested.digest().equals(packaged.digest()) || !tested.digest().equals(AssetDigests.sha256(before))) throw new IllegalStateException("TEST_DEPENDENCY_CHANGED");
            return;
        }
        if (!tested.digest().equals(AssetDigests.sha256Tree(before)) || Files.isDirectory(after)) throw new IllegalStateException("TESTED_CLASSES_CHANGED_OR_NOT_PACKAGED");
        try (var jar = new JarFile(after.toFile()); var files = Files.walk(before)) {
            var entries = new HashSet<String>();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = before.relativize(file).toString().replace('\\', '/'); entries.add(name);
                var entry = jar.getJarEntry(name); if (entry == null) throw new IllegalStateException("PACKAGED_CLASS_MISSING:" + name);
                try (var bytes = jar.getInputStream(entry)) { if (!Arrays.equals(Files.readAllBytes(file), bytes.readAllBytes())) throw new IllegalStateException("PACKAGED_BYTES_UNTESTED:" + name); }
            }
            var names = jar.entries();
            while (names.hasMoreElements()) {
                var entry = names.nextElement(); String name = entry.getName();
                if (!entry.isDirectory() && !name.startsWith("META-INF/maven/") && !name.equals("META-INF/MANIFEST.MF") && !entries.contains(name))
                    throw new IllegalStateException("EXTRA_UNTESTED_JAR_ENTRY:" + name);
                if (name.contains("/reference/FixtureBank") || name.contains("/reference/ControlledModel")) throw new IllegalStateException("TEST_FIXTURE_IN_PRODUCT_JAR");
            }
        }
    }
}
