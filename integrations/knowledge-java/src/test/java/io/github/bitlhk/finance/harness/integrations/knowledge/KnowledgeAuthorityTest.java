package io.github.bitlhk.finance.harness.integrations.knowledge;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAuthorityTest {
    @TempDir Path temp;
    Path root, policy, leases;
    Map<String,Object> config;

    @BeforeEach void setup() throws Exception {
        root=Files.createDirectory(temp.resolve("candidate"));
        var binding=Files.createDirectories(root.resolve("provider-configuration/knowledge-service"));
        KnowledgeAuthority.JSON.writeValue(binding.resolve("knowledge-binding.json").toFile(),Map.of(
            "productId","product", "manifest","a".repeat(64),"businessDate","2026-10-30"));
        endpoint(61357);
        leases=Files.createDirectory(temp.resolve("leases"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        policy=temp.resolve("policy.json");
        config=new LinkedHashMap<>(Map.of("schemaVersion","knowledge-host-authority.v0.1","enabled",true,
            "ownerRef","owner","principalId","principal","authority","isolated-host","tenant","test",
            "subjectRef","subject","productId","product","manifest","a".repeat(64),
            "context",Map.of("business_date","2026-10-30","purpose","evidence-query")));
        config.put("expiresAt",Instant.now().plusSeconds(3600).toString());
        config.put("leaseDirectory",leases.toString());
        writePolicy();
    }
    void endpoint(int port) throws Exception {
        KnowledgeAuthority.JSON.writeValue(root.resolve("provider-bindings.json").toFile(),Map.of("providers",
            Map.of("knowledge-service",Map.of("endpoint","http://127.0.0.1:"+port+"/query"))));
    }
    void writePolicy() throws Exception {
        KnowledgeAuthority.JSON.writeValue(policy.toFile(),config);
        Files.setPosixFilePermissions(policy,PosixFilePermissions.fromString("rw-------"));
    }
    KnowledgeAuthority open() throws Exception {return new KnowledgeAuthority(root,"owner","instance","session",policy);}

    @Test void createsScopedLeaseAndClosesOnlyItsOwnFile() throws Exception {
        try(var auth=open()) {
            assertEquals("principal",auth.grant().principalId());
            assertEquals(Set.of("knowledge:read"),auth.grant().scopes());
            assertEquals("session",auth.source().hostSessionRef());
            assertFalse(Files.readString(auth.leaseFile).contains(auth.token));
            assertTrue(auth.expires.isBefore(Instant.now().plusSeconds(901)));
            var unrelated=leases.resolve("other.json"); Files.writeString(unrelated,"{}");
            auth.close();
            assertFalse(Files.exists(auth.leaseFile));assertTrue(Files.exists(unrelated));
            assertThrows(SecurityException.class,auth::grant);
        }
    }
    @Test void policyRevocationInvalidatesSession() throws Exception {
        try(var auth=open()) {config.put("enabled",false);writePolicy();assertThrows(SecurityException.class,auth::source);}
    }
    @Test void changedInstanceCannotReuseLease() throws Exception {
        try(var auth=open()) {
            var lease=KnowledgeAuthority.JSON.readTree(auth.leaseFile.toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode)lease).put("instanceId","other");
            Files.write(auth.leaseFile,KnowledgeAuthority.JSON.writeValueAsBytes(lease));
            assertThrows(SecurityException.class,auth::current);
        }
    }
    @Test void ownerManifestAndExpiryAreRequired() throws Exception {
        assertThrows(SecurityException.class,()->new KnowledgeAuthority(root,"other","instance","session",policy));
        config.put("manifest","b".repeat(64));writePolicy();assertThrows(SecurityException.class,this::open);
        config.put("manifest","a".repeat(64));config.put("expiresAt","2000-01-01T00:00:00Z");writePolicy();
        assertThrows(SecurityException.class,this::open);
    }
    @Test void worldReadablePolicyAndSymlinkAreRejected() throws Exception {
        Files.setPosixFilePermissions(policy,PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(SecurityException.class,this::open);writePolicy();
        var link=temp.resolve("link");Files.createSymbolicLink(link,policy);
        assertThrows(SecurityException.class,()->new KnowledgeAuthority(root,"owner","instance","session",link));
    }
    @Test void coreAdmissionIsCheckedWithoutQueryAndBusinessErrorsDeny() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var accepted=new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/session",exchange->{
            calls.incrementAndGet();
            var raw=KnowledgeAuthority.JSON.writeValueAsBytes(Map.of("schemaVersion","knowledge-session-check.v0.1",
                "code",accepted.get()?0:403,"sessionRef","session","productId","product","manifest","a".repeat(64)));
            exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);exchange.close();
        });
        server.start();endpoint(server.getAddress().getPort());
        try(var auth=open()) {
            assertEquals(0,calls.get());auth.currentKnowledgeAccess();assertEquals(1,calls.get());
            accepted.set(false);assertThrows(SecurityException.class,auth::currentKnowledgeAccess);
        } finally {server.stop(0);}
    }
}
