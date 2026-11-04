package io.github.bitlhk.finance.harness.material;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;

/** Reject substituted identities, versions or modes before exposing a domain result. */
public final class MaterialEvidenceMapper {
    private MaterialEvidenceMapper() {}
    private static final ObjectMapper JSON=HarnessJson.mapper().copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public record Expected(String requestId,String principalId,String subjectRef,String sessionRef,
            String taskRef,String documentRef,int documentVersion,String documentDigest,
            MaterialExtractionV1.Mode mode,String toolReleaseDigest) {}
    public static MaterialExtractionV1.Result map(byte[] bytes,Expected expected) {
        try {
            var result=JSON.readValue(bytes,MaterialExtractionV1.Result.class);var doc=result.document();
            if(!expected.requestId().equals(result.requestId())||!expected.principalId().equals(doc.principalId())
                    ||!expected.subjectRef().equals(doc.subjectRef())||!expected.sessionRef().equals(doc.sessionRef())
                    ||!expected.taskRef().equals(doc.taskRef())||!expected.documentRef().equals(doc.documentRef())
                    ||expected.documentVersion()!=doc.version()||!expected.documentDigest().equals(doc.sha256())
                    ||expected.mode()!=result.mode()||!expected.toolReleaseDigest().equals(result.source().toolReleaseDigest()))
                throw new IllegalArgumentException("MATERIAL_RESULT_BINDING_MISMATCH");
            return result;
        } catch(IllegalArgumentException error) { throw error;
        } catch(Exception error) { throw new IllegalArgumentException("MATERIAL_RESULT_INVALID"); }
    }
}
