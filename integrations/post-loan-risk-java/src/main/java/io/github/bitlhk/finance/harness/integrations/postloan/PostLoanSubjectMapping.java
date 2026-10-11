package io.github.bitlhk.finance.harness.integrations.postloan;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

record PostLoanSubjectMapping(String ownerRef, String revision, Map<String,String> subjects) {
    PostLoanSubjectMapping {
        if (ownerRef == null || ownerRef.isBlank() || revision == null || revision.isBlank() || subjects.isEmpty())
            throw new IllegalArgumentException("POSTLOAN_SUBJECT_MAPPING_INVALID");
        subjects = Map.copyOf(subjects);
    }

    static PostLoanSubjectMapping load(Path file, String owner) throws Exception {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file))
            throw new SecurityException("POSTLOAN_SUBJECT_MAPPING_MISSING");
        JsonNode root=HarnessJson.mapper().readTree(Files.readAllBytes(file));
        JsonNode entry=root.path("owners").path(owner);
        if (!entry.isObject()) throw new SecurityException("POSTLOAN_SUBJECT_MAPPING_MISSING");
        var values=new LinkedHashMap<String,String>();
        entry.path("subjects").fields().forEachRemaining(value->{
            if (value.getValue().isTextual() && !value.getValue().asText().isBlank()) values.put(value.getKey(),value.getValue().asText());
        });
        return new PostLoanSubjectMapping(owner,entry.path("revision").asText(),values);
    }
}
