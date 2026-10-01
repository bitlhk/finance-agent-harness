package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: runs the actual read-only reference_mcp.py, not a protocol stub. */
@EnabledIfSystemProperty(named = "curated.reference.directory", matches = ".+")
class ReferenceMcpTest {
    @Test void actualReferenceStreamableHttpSixteenToolsAndSourceStates(@TempDir Path temp) throws Exception {
        String python = System.getProperty("curated.reference.python");
        Path root = Path.of(System.getProperty("curated.reference.directory")).toRealPath();
        assertTrue(Files.isRegularFile(root.resolve("reference_mcp.py")));
        var manifest = HarnessJson.mapper().readTree(Path.of("reference-contract.v1.json").toFile());
        var files = manifest.path("files").fields();
        while (files.hasNext()) {
            var file = files.next();
            assertEquals(file.getValue().asText(), CuratedSourceConfig.sha(Files.readAllBytes(root.resolve(file.getKey()))), "Reference source drift: " + file.getKey());
        }
        int port;
        try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        var process = new ProcessBuilder(python, "-c", "import sys;sys.path.insert(0,sys.argv[1]);from reference_mcp import mcp;mcp.run(transport='streamable-http',host='127.0.0.1',port=int(sys.argv[2]))", root.toString(), String.valueOf(port))
                .redirectErrorStream(true).redirectOutput(temp.resolve("reference.log").toFile()).start();
        try {
            boolean listening = false;
            for (int i = 0; i < 150 && process.isAlive(); i++) {
                try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 100);listening = true;break; }
                catch (java.io.IOException wait) { Thread.sleep(100); }
            }
            assertTrue(listening, () -> { try { return Files.readString(temp.resolve("reference.log")); } catch (Exception e) { return "reference did not start"; } });
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
            var pins = new LinkedHashMap<String, String>();
            // Test-only discovery against a process this test owns. Production requires reviewed pins.
            var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                    .endpoint("/mcp").openConnectionOnStartup(false).resumableStreams(false).build();
            try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                client.initialize();var listed = client.listTools();assertEquals(16, listed.tools().size());
                listed.tools().forEach(t -> pins.put(t.name(), CuratedTools.schemaPin(t.inputSchema())));
            }
            var resources = new LinkedHashMap<String, Set<String>>();
            CuratedTools.ALL.forEach(t -> resources.put(t.name(), t.selector().equals("customer_id") ? Set.of("C005") : t.resources()));
            var config = new CuratedSourceConfig("curated-synthetic-source.v1", endpoint, "1.0.0", pins,
                    Map.of("host-customer", new CuratedSourceConfig.Subject("C005", resources)));
            var session = new ManagedProviderFactoryV01.SourceSession("never-forward-bank-token", "host-customer", "actor", "session", Instant.now().plusSeconds(300));
            var provider = new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"), Duration.ofSeconds(5), () -> session, () -> null, ignored -> {}), config);
            for (var tool : CuratedTools.ALL) {
                var args = tool.selector().equals("customer_id") ? Map.<String, Object>of("customer_id", "host-customer")
                        : tool.selector().equals("product_id") ? Map.<String, Object>of("product_id", "P009") : Map.<String, Object>of();
                var result = provider.query(request(tool, args));
                assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), tool.name() + ": " + result);
                assertTrue(((Map<?, ?>) result.data().get("sourceResult")).get("synthetic").equals(true));
            }
            var unknown = provider.query(request(CuratedTools.named("fraud_transfer_risk_assess"), Map.of("reference", "bene-unknown")));
            assertEquals(CapabilityResultV1.Status.COMPLETED, unknown.status());assertNull(unknown.task());
            assertEquals(Map.of("decision", "UNKNOWN", "status", "UNAVAILABLE"), unknown.data().get("sourceState"));
            var degraded = provider.query(request(CuratedTools.named("prepare_wealth_allocation_context"), Map.of("customer_id", "host-customer", "investable_amount", 100)));
            assertEquals(CapabilityResultV1.Status.COMPLETED, degraded.status());
            assertEquals(Map.of("readiness", "DEGRADED"), degraded.data().get("sourceState"));
            assertFalse(((Map<?, ?>) degraded.data().get("sourceResult")).get("missing").toString().isEmpty());
        } finally {
            process.destroy();if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(); }
        }
    }
    static CapabilityRequestV1 request(CuratedTools.Tool tool, Map<String, Object> args) {
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, tool.capabilityId(), CapabilityRequestV1.Operation.QUERY,
                new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, UUID.randomUUID().toString(), "trace", "actor", FinanceContextV1.PrincipalType.CUSTOMER,
                    "test", "synthetic-evaluation", "host-customer", "session", FinanceContextV1.AuthenticationLevel.STRONG, Set.of(tool.scope())), args, null, null, Map.of(), null);
    }
}
