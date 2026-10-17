package io.github.bitlhk.finance.harness.cashflow;

import io.github.bitlhk.finance.harness.core.*;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Per invocation evidence, not a grant, signature, or reusable authorization decision. */
public final class CashflowExecutionBinding {
    private final Map<String,Object> fields;
    public CashflowExecutionBinding(CapabilityRequestV1 request, Map<String,String> host, String installation) {
        var values = new TreeMap<String,Object>();
        values.put("executionBindingVersion", "cashflow-execution.v1");
        values.put("executionBindingId", UUID.randomUUID().toString());
        values.put("requestId", request.context().requestId());
        values.put("traceId", request.context().traceId());
        values.put("capabilityId", request.capabilityId());
        values.put("operation", request.operation().name());
        values.put("principalDigest", hash(request.context().principalId()));
        values.put("subjectDigest", hash(request.context().subjectRef()));
        values.put("sessionDigest", hash(request.context().sessionRef()));
        values.put("purpose", request.context().purpose());
        values.put("bindingDigest", installation);
        var safe = new TreeMap<String,Object>();
        Object period = request.parameters().get("period");
        String normalized = "MISSING";
        if (period != null && !"".equals(period)) {
            try { if (!(period instanceof String s)) throw new IllegalArgumentException(); CashflowFactsV1.month(s); normalized = s; }
            catch (IllegalArgumentException invalid) { normalized = "INVALID"; }
        }
        safe.put("period", normalized);
        values.put("period", normalized);
        values.put("parametersDigest", digest(safe));
        values.put("parameterContract", "PERIOD_YYYY_MM_ONLY.v1");
        // Host supplies these values out of band; no invocation parameter can set them.
        if (!host.isEmpty()) {
            for (String key : List.of("hostRequestId", "ownerRef", "buildId", "instanceId", "serviceLockDigest")) {
                String value = host.get(key);
                if (value == null || value.isBlank() || value.length() > 160)
                    throw new IllegalArgumentException("EXECUTION_HOST_BINDING_REQUIRED");
                values.put(key, value);
            }
            values.put("hostBindingStatus", "TRUSTED_HOST");
        } else values.put("hostBindingStatus", "UNOBSERVED_LEGACY_CALLER");
        fields = Collections.unmodifiableMap(values);
    }
    public Map<String,Object> fields() { return fields; }
    public static String hash(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("DIGEST_UNAVAILABLE"); }
    }
    public static String digest(Object value) {
        try { return hash(HarnessJson.mapper().copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value)); }
        catch (Exception invalid) { throw new IllegalArgumentException("EVIDENCE_ENCODING_INVALID"); }
    }
}
