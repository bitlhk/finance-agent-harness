package io.github.bitlhk.finance.harness.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native tool input projection only; confirmation text is never an authorization event. */
public final class TaskToolSchema {
    private TaskToolSchema() { }
    public static Map<String, Object> withControls(Map<String, Object> source) {
        Map<String, Object> schema = new LinkedHashMap<>(source);
        Map<String, Object> properties = new LinkedHashMap<>();
        if (source.get("properties") instanceof Map<?, ?> map)
            map.forEach((key, value) -> properties.put(String.valueOf(key), value));
        properties.put(TaskCoordination.HANDLE_ARGUMENT, Map.of("type", "string",
                "description", "工具要求确认后返回的续办句柄；首次调用不要提供。"));
        properties.put("confirmationText", Map.of("type", "string",
                "description", "兼容旧调用的文本字段，不构成授权；办理确认须通过宿主确认界面。"));
        schema.put("properties", Map.copyOf(properties));
        if (schema.remove("required") instanceof List<?> required && !required.isEmpty())
            schema.put("anyOf", List.of(Map.of("required", List.copyOf(required)),
                    Map.of("required", List.of(TaskCoordination.HANDLE_ARGUMENT))));
        return Map.copyOf(schema);
    }
}
