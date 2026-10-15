package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.foundation.llm.schema.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelSnapshotClientTest {
    private record Captured(String boundary, String state, Map<String,Object> value) { }
    @Test void terminationDiagnosticsAreRedactedAndDoNotInventHttpEvidence() {
        var timeout=ModelSnapshotClient.streamTermination(new IllegalStateException("CUSTOMER_TEXT secret-token",new SocketTimeoutException("read timed out")),"ITERATOR_HAS_NEXT",1);
        assertEquals("READ_TIMEOUT",timeout.get("terminationReason"));
        var unspecifiedTimeout=ModelSnapshotClient.streamTermination(new java.util.concurrent.TimeoutException("secret"),"ITERATOR_HAS_NEXT",1);
        assertEquals("TIMEOUT_SOURCE_UNSPECIFIED",unspecifiedTimeout.get("terminationReason"));
        assertEquals(1,timeout.get("chunksObserved"));
        assertEquals(false,timeout.get("httpStatusObserved"));
        assertEquals(false,timeout.get("httpSendCountObserved"));
        String rendered=timeout.toString();
        assertFalse(rendered.contains("CUSTOMER_TEXT"));assertFalse(rendered.contains("secret-token"));

        var reset=ModelSnapshotClient.streamTermination(new RuntimeException(new SocketException("Connection reset by peer")),"ITERATOR_NEXT",2);
        assertEquals("SOCKET_RESET_OR_CLOSED",reset.get("terminationReason"));
        var eof=ModelSnapshotClient.streamTermination(new RuntimeException(new java.io.EOFException("partial")),"ITERATOR_HAS_NEXT",1);
        assertEquals("UNEXPECTED_EOF",eof.get("terminationReason"));
        var cancelled=ModelSnapshotClient.streamTermination(new CancellationException("caller cancelled"),"STREAM_OPEN",0);
        assertEquals("EXPLICIT_CANCELLATION",cancelled.get("terminationReason"));
        var interrupted=ModelSnapshotClient.streamTermination(new RuntimeException(new InterruptedException("worker interrupted")),"ITERATOR_HAS_NEXT",1);
        assertEquals("THREAD_INTERRUPTED",interrupted.get("terminationReason"));
        assertEquals(true,interrupted.get("threadInterruptedObserved"));
        var malformed=ModelSnapshotClient.streamTermination(new RuntimeException(
                new com.fasterxml.jackson.core.JsonParseException(null,"PRIVATE_STREAM_BODY")),"ITERATOR_NEXT",1);
        assertEquals("MALFORMED_STREAM_PAYLOAD",malformed.get("terminationReason"));
        assertFalse(malformed.toString().contains("PRIVATE_STREAM_BODY"));
        assertEquals("ITERATOR_EXHAUSTED",ModelSnapshotClient.streamTermination(null,"ITERATOR_HAS_NEXT",3).get("terminationReason"));
    }
    @Test void concurrentScopesAreIsolatedAndResponseExcludesReasoning() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer\",\"reasoning_content\":\"HIDDEN_SYNTHETIC\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<List<String>>>();
            for (String marker : List.of("OWNER_A_MARKER", "OWNER_B_MARKER")) futures.add(pool.submit(() -> {
                var records = new ArrayList<String>();
                var client = client(server.getAddress().getPort());
                try (var scope = ModelSnapshotClient.scope((call, boundary, state, value) -> {
                    try { records.add(boundary + HarnessJson.mapper().writeValueAsString(value)); }
                    catch (Exception failed) { throw new IllegalStateException(); }
                })) { invoke(client, marker); }
                assertEquals(2, records.size());
                invoke(client, "NO_SCOPE");
                assertEquals(2, records.size());
                return records;
            }));
            var a = futures.get(0).get(10, TimeUnit.SECONDS).toString();
            var b = futures.get(1).get(10, TimeUnit.SECONDS).toString();
            assertTrue(a.contains("OWNER_A_MARKER")); assertFalse(a.contains("OWNER_B_MARKER"));
            assertTrue(b.contains("OWNER_B_MARKER")); assertFalse(b.contains("OWNER_A_MARKER"));
            assertFalse(a.contains("HIDDEN_SYNTHETIC")); assertFalse(b.contains("HIDDEN_SYNTHETIC"));
            try (var scope = ModelSnapshotClient.scope((c, boundary, state, value) -> { throw new IllegalStateException("PRIVATE_ERROR"); })) {
                assertNotNull(invoke(client(server.getAddress().getPort()), "OBSERVER_FAIL"));
            }
        } finally { server.stop(0); }
    }
    @Test void actualSdkStreamDistinguishesCompletionTruncationAndMalformedPayload() throws Exception {
        assertEquals("ITERATOR_EXHAUSTED", streamProbe(StreamCase.COMPLETE).stream()
                .filter(row -> row.boundary().equals("MODEL_STREAM_TERMINATION")).findFirst().orElseThrow()
                .value().get("terminationReason"));
        var truncated=streamProbe(StreamCase.TRUNCATED);
        assertTrue(truncated.stream().anyMatch(row -> row.boundary().equals("MODEL_STREAM_TERMINATION")));
        assertNotEquals("ITERATOR_EXHAUSTED",truncated.stream()
                .filter(row -> row.boundary().equals("MODEL_STREAM_TERMINATION")).findFirst().orElseThrow()
                .value().get("terminationReason"));
        var malformed=streamProbe(StreamCase.MALFORMED);
        assertEquals("MALFORMED_STREAM_PAYLOAD",malformed.stream()
                .filter(row -> row.boundary().equals("MODEL_STREAM_TERMINATION")).findFirst().orElseThrow()
                .value().get("terminationReason"));
    }
    @Test void downstreamAbandonmentWithoutAnotherIteratorCallIsNotInventedAsCancellation() throws Exception {
        var records=streamProbe(StreamCase.ABANDON_AFTER_FIRST_CHUNK);
        assertTrue(records.stream().anyMatch(row -> row.boundary().equals("CLIENT_REQUEST_PREPARED")));
        assertFalse(records.stream().anyMatch(row -> row.boundary().equals("MODEL_STREAM_TERMINATION")));
    }
    private enum StreamCase { COMPLETE, TRUNCATED, MALFORMED, ABANDON_AFTER_FIRST_CHUNK }
    private List<Captured> streamProbe(StreamCase streamCase) throws Exception {
        var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            String delta="data: {\"id\":\"s\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"SYNTHETIC_DELTA\"},\"finish_reason\":null}]}\n\n";
            String body=switch(streamCase){
                case COMPLETE -> delta+"data: {\"id\":\"s\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                case MALFORMED -> "data: {not-json}\n\n";
                default -> delta;
            };
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,streamCase==StreamCase.TRUNCATED?bytes.length+1000:bytes.length);
            exchange.getResponseBody().write(bytes);exchange.getResponseBody().flush();exchange.close();
        });server.start();
        var records=Collections.synchronizedList(new ArrayList<Captured>());
        try {
            var client=client(server.getAddress().getPort());
            try(var scope=ModelSnapshotClient.scope((call,boundary,state,value)->records.add(new Captured(boundary,state,Map.copyOf(value))))){
                Iterator<AssistantMessageChunk> stream=client.stream(List.of(Map.of("role","user","content","synthetic")),List.of(),
                        null,null,null,null,null,null,null,Map.of());
                if(streamCase==StreamCase.ABANDON_AFTER_FIRST_CHUNK){assertTrue(stream.hasNext());stream.next();}
                else assertThrowsOrConsumes(streamCase,stream);
            }
            assertEquals(1,requests.get());
            return List.copyOf(records);
        } finally {server.stop(0);}
    }
    private void assertThrowsOrConsumes(StreamCase streamCase,Iterator<AssistantMessageChunk> stream){
        if(streamCase==StreamCase.COMPLETE){while(stream.hasNext())stream.next();return;}
        assertThrows(RuntimeException.class,()->{while(stream.hasNext())stream.next();});
    }
    private ModelSnapshotClient client(int port) {
        return new ModelSnapshotClient(ModelRequestConfig.builder().modelName("probe").build(),
                ModelClientConfig.builder().clientProvider("OpenAI").apiKey("synthetic-secret")
                        .apiBase("http://127.0.0.1:" + port + "/v1").timeout(5).maxRetries(0).build());
    }
    private AssistantMessage invoke(ModelSnapshotClient client, String marker) throws Exception {
        return client.invoke(List.of(Map.of("role", "user", "content", marker)), List.of(), null, null,
                null, null, null, null, null, Map.of());
    }
}
