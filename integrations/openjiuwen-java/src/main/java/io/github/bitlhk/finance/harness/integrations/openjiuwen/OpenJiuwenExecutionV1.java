package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime-neutral result that an OpenJiuwen host adapter maps to its native execution type. */
public record OpenJiuwenExecutionV1(
        Status status,
        String taskId,
        String content,
        Map<String, Object> data,
        List<Map<String, Object>> evidence,
        String errorCode,
        String question,
        Map<String, Object> checkpoint) {

    public OpenJiuwenExecutionV1 {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        content = content == null ? "" : content;
        data = immutableMap(data);
        evidence = immutableEvidence(evidence);
        errorCode = errorCode == null ? "" : errorCode;
        question = question == null ? "" : question;
        checkpoint = immutableMap(checkpoint);
    }

    public enum Status {
        COMPLETED,
        NEED_USER,
        REJECTED
    }

    private static List<Map<String, Object>> immutableEvidence(List<Map<String, Object>> source) {
        if (source == null || source.isEmpty()) return List.of();
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> item : source) copy.add(immutableMap(item));
        return List.copyOf(copy);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
