package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryInputValidatorTest {
    @Test void validatesInlineTypesAndLocalPointersWithoutCoercion() {
        var validator = new QueryInputValidator(Map.of("type", "object", "additionalProperties", false,
                "$defs", Map.of("selector", Map.of("type", "string", "pattern", "^[a-z]+$")),
                "properties", Map.of("selector", Map.of("$ref", "#/$defs/selector"))));
        validator.validate(Map.of()); validator.validate(Map.of("selector", "abc"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("selector", 12)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("selector", "bad-1")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(Map.of("principal", "other")));
    }
    @Test void remoteResourceAttemptsNeverReachNetworkIncludingIdsAndNestedRefs() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> { requests.incrementAndGet(); e.sendResponseHeaders(200, 2); e.getResponseBody().write("{}".getBytes()); e.close(); });
        server.start();
        String remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/schema";
        try {
            for (var schema : List.<Map<String, Object>>of(
                    Map.of("type", "object", "$ref", remote),
                    Map.of("type", "object", "$id", remote),
                    Map.of("type", "object", "properties", Map.of("x", Map.of("$ref", remote))),
                    Map.of("type", "object", "$defs", Map.of("x", Map.of("$id", remote, "$ref", "other"))),
                    Map.of("type", "object", "$schema", remote),
                    Map.of("type", "object", "$dynamicRef", remote))) {
                assertThrows(IllegalArgumentException.class, () -> new QueryInputValidator(schema).validate(Map.of("x", 1)));
            }
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }
}
