package io.github.bitlhk.finance.harness.integrations.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Private local Host authority for the isolated integration, not bank IAM.
 * It can issue scoped sessions only; it cannot grant Core permissions or publish. */
final class KnowledgeAuthority implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    final Path policy, leaseFile;
    final JsonNode frozen;
    final String digest, token, session, leaseDigest;
    final URI sessionEndpoint;
    final Instant expires;
    volatile boolean closed;

    KnowledgeAuthority(Path root, String owner, String instance, String session) throws Exception {
        this(root, owner, instance, session, Path.of(Objects.requireNonNull(
            System.getenv("FH_KNOWLEDGE_AUTHORITY"), "KNOWLEDGE_AUTHORITY_REQUIRED")));
    }
    KnowledgeAuthority(Path root, String owner, String instance, String session, Path policyFile) throws Exception {
        this.session = session;
        policy = policyFile.toAbsolutePath().normalize();
        if (policy.toRealPath().startsWith(root.toRealPath())) throw new SecurityException("PRIVATE_AUTHORITY_REQUIRED");
        byte[] raw = privateBytes(policy);
        digest = hash(raw); frozen = JSON.readTree(raw);
        if (!"knowledge-host-authority.v0.1".equals(frozen.path("schemaVersion").asText())
                || !frozen.path("enabled").asBoolean(false) || !owner.equals(text("ownerRef")))
            throw new SecurityException("KNOWLEDGE_AUTHORITY_REJECTED");
        var binding = JSON.readTree(root.resolve("provider-configuration/knowledge-service/knowledge-binding.json").toFile());
        var connections = JSON.readTree(root.resolve("provider-bindings.json").toFile());
        var endpoint = URI.create(connections.path("providers").path("knowledge-service").path("endpoint").asText());
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || !"/query".equals(endpoint.getPath()) || endpoint.getPort() < 1
                || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null)
            throw new SecurityException("KNOWLEDGE_ENDPOINT_REJECTED");
        sessionEndpoint = endpoint.resolve("/session");
        if (!binding.path("productId").equals(frozen.path("productId"))
                || !binding.path("manifest").equals(frozen.path("manifest"))
                || !binding.path("businessDate").equals(frozen.path("context").path("business_date")))
            throw new SecurityException("KNOWLEDGE_AUTHORITY_BINDING_MISMATCH");
        var end = Instant.parse(text("expiresAt"));
        expires = end.isBefore(Instant.now().plusSeconds(900)) ? end : Instant.now().plusSeconds(900);
        if (!expires.isAfter(Instant.now())) throw new SecurityException("KNOWLEDGE_AUTHORITY_EXPIRED");
        Path leases = Path.of(text("leaseDirectory"));
        if (!leases.isAbsolute() || leases.toRealPath().startsWith(root.toRealPath())
                || Files.isSymbolicLink(leases)
                || !Files.getPosixFilePermissions(leases).equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.getOwner(leases).equals(Files.getOwner(policy)))
            throw new SecurityException("PRIVATE_LEASE_DIRECTORY_REQUIRED");
        byte[] random = new byte[48]; new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        leaseFile = leases.resolve(hash(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");
        var lease = Map.of("schemaVersion", "knowledge-host-lease.v0.1", "policyDigest", digest,
            "ownerRef", owner, "instanceId", instance, "sessionRef", session, "expiresAt", expires.toString());
        byte[] leaseBytes = JSON.writeValueAsBytes(lease);
        leaseDigest = hash(leaseBytes);
        Files.createFile(leaseFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try { Files.write(leaseFile, leaseBytes); current(); }
        catch (Exception failure) { Files.deleteIfExists(leaseFile); throw failure; }
    }
    String text(String key) {
        var value = frozen.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw new SecurityException("KNOWLEDGE_AUTHORITY_INVALID");
        return value.asText();
    }
    void current() {
        try {
            if (closed || !expires.isAfter(Instant.now()) || !hash(privateBytes(policy)).equals(digest)
                    || !hash(privateBytes(leaseFile)).equals(leaseDigest))
                throw new SecurityException("KNOWLEDGE_AUTHORITY_REJECTED");
        } catch (Exception failure) { throw new SecurityException("KNOWLEDGE_AUTHORITY_REJECTED"); }
    }
    void currentKnowledgeAccess() {
        current();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(sessionEndpoint).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try (var stream=response.body()) { raw=stream.readNBytes(8193); }
            if(response.statusCode()!=200 || raw.length>8192)throw new SecurityException();
            var body=JSON.readTree(raw);
            if(!body.path("code").isIntegralNumber() || body.path("code").asInt()!=0
                    || !"knowledge-session-check.v0.1".equals(body.path("schemaVersion").asText())
                    || !session.equals(body.path("sessionRef").asText())
                    || !text("manifest").equals(body.path("manifest").asText())
                    || !text("productId").equals(body.path("productId").asText()))throw new SecurityException();
            current();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new SecurityException("KNOWLEDGE_AUTHORITY_REJECTED");
        } catch (Exception failure) { throw new SecurityException("KNOWLEDGE_AUTHORITY_REJECTED"); }
    }
    BoundFinanceProvider.Grant grant() {
        current(); return new BoundFinanceProvider.Grant(text("principalId"), FinanceContextV1.PrincipalType.EMPLOYEE,
            Set.of(text("subjectRef")), Set.of(frozen.path("context").path("purpose").asText()),
            Set.of("knowledge:read"), session, text("authority"), expires);
    }
    ManagedProviderFactoryV01.SourceSession source() {
        current(); return new ManagedProviderFactoryV01.SourceSession(token,text("subjectRef"),text("principalId"),session,expires);
    }
    @Override public void close() {
        closed = true;
        try { Files.deleteIfExists(leaseFile); }
        catch (Exception failure) { throw new IllegalStateException("KNOWLEDGE_LEASE_RELEASE_UNKNOWN"); }
    }
    static byte[] privateBytes(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path)>16384
                || !Files.getOwner(path).getName().equals(System.getProperty("user.name"))
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")))
            throw new SecurityException("PRIVATE_AUTHORITY_REQUIRED");
        return Files.readAllBytes(path);
    }
    static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
