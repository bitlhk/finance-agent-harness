package io.github.bitlhk.finance.harness.providers.customercrm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Minimal read-only Twenty CRM client. The bearer supplier remains deployment-owned. */
public final class CustomerCrmClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI base;
    private final Duration timeout;
    private final HttpClient http;
    private final java.util.function.Supplier<String> bearer;

    public CustomerCrmClient(URI base, Duration timeout, java.util.function.Supplier<String> bearer) {
        this(base, timeout, bearer, HttpClient.newBuilder().connectTimeout(timeout).build());
    }
    CustomerCrmClient(URI base, Duration timeout, java.util.function.Supplier<String> bearer, HttpClient http) {
        this.base = requireBase(base); this.timeout = Objects.requireNonNull(timeout);
        this.bearer = Objects.requireNonNull(bearer); this.http = Objects.requireNonNull(http);
    }
    public List<JsonNode> find(String plural, String filter, int limit) {
        if (!Set.of("enterpriseCustomers", "customerAssignments", "customerDomainProfiles").contains(plural))
            throw new IllegalArgumentException("CRM_OBJECT_NOT_ALLOWED");
        String query = "filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&limit=" + Math.min(2000, Math.max(1, limit));
        URI uri = base.resolve("/rest/" + plural + "?" + query);
        String token = bearer.get();
        if (token == null || token.isBlank()) throw new SecurityException("CRM_SERVICE_CREDENTIAL_MISSING");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("CRM_READ_FAILED:" + response.statusCode());
            JsonNode rows = JSON.readTree(response.body()).path("data").path(plural);
            if (!rows.isArray()) throw new IllegalStateException("CRM_RESPONSE_SHAPE_INVALID");
            List<JsonNode> result = new ArrayList<>(); rows.forEach(value -> result.add(value.deepCopy()));
            return List.copyOf(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("CRM_READ_INTERRUPTED", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("CRM_READ_FAILED", failure);
        }
    }
    private static URI requireBase(URI value) {
        Objects.requireNonNull(value); String host = value.getHost();
        boolean loopback = Set.of("127.0.0.1", "localhost", "::1").contains(host);
        if (!("https".equals(value.getScheme()) || ("http".equals(value.getScheme()) && loopback))
                || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null)
            throw new IllegalArgumentException("CRM_HTTPS_REQUIRED");
        return URI.create(value.toString().replaceAll("/+$", "") + "/");
    }
}
