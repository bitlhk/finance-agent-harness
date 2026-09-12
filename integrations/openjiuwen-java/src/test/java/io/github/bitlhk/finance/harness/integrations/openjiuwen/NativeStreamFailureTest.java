package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.foundation.llm.model_clients.OpenAiCompatibleModelClient;
import com.openjiuwen.core.foundation.llm.schema.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Actual local transport counts; explicit caller retries are not SDK callback attempts. */
class NativeStreamFailureTest {
    @Test @Timeout(15) void failureBeforeFirstChunkDoesNotRetryWhenDisabled() throws Exception {
        probe(false);
    }
    @Test @Timeout(15) void brokenPartialStreamIsNotACompleteResponse() throws Exception {
        probe(true);
    }
    @Test @Timeout(15) void explicitCallerRetryHasTwoActualRequestsWithSdkRetryDisabled() throws Exception {
        probe(false, 2);
    }
    private void probe(boolean partial) throws Exception {
        probe(partial, 1);
    }
    private void probe(boolean partial, int attempts) throws Exception {
        var requests=new AtomicInteger();var chunks=new AtomicInteger();var prepared=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            if(!partial){exchange.sendResponseHeaders(500,-1);exchange.close();return;}
            byte[] body="data: {\"id\":\"s\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"PARTIAL_SYNTHETIC\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,body.length+1000);
            exchange.getResponseBody().write(body);exchange.getResponseBody().flush();
            exchange.close();
        });server.start();
        try {
            var client=new OpenAiCompatibleModelClient(ModelRequestConfig.builder().modelName("probe").build(),
                    ModelClientConfig.builder().clientProvider("OpenAI").apiKey("synthetic")
                            .apiBase("http://127.0.0.1:"+server.getAddress().getPort()+"/v1").timeout(2).maxRetries(0).build());
            for (int attempt=0;attempt<attempts;attempt++) assertThrows(Exception.class,()->client.withRequestTraceCallback(value->prepared.incrementAndGet(),()->{
                var response=client.stream(List.of(Map.of("role","user","content","synthetic")),List.of(),
                        null,null,null,null,null,null,null,Map.of());
                while(response.hasNext()){response.next();chunks.incrementAndGet();}
                return null;
            }));
            assertEquals(attempts,requests.get());assertEquals(attempts,prepared.get());
            if(partial)assertTrue(chunks.get()>0);else assertEquals(0,chunks.get());
            System.out.printf("STREAM_FAILURE partial=%s chunks=%d http=%d prepared=%d%n",partial,chunks.get(),requests.get(),prepared.get());
        } finally {server.stop(0);}
    }
}
