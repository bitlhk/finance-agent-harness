package io.github.bitlhk.finance.harness.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Additive material evidence domain. Original values are never an audit approval. */
public final class MaterialExtractionV1 {
    private MaterialExtractionV1() {}
    public static final String REVISION="0e158eee225c698774aa75671c944624cd36550b";
    public enum Status { REJECTED, BLOCKED, COMPLETED, PARTIAL, FAILED, CANCELED }
    public enum Mode { PDF_TABLES, PDF_DIRECTORY, CREDENTIAL, LOCATE }
    public record Document(String schemaVersion,String documentRef,int version,String sha256,
            String mediaType,long sizeBytes,int pageCount,String ownerRef,String principalId,
            String subjectRef,String taskRef,String sessionRef) {
        public Document {
            if(!"financial-material-document.v1".equals(schemaVersion)||version<1||sizeBytes<1
                    ||sizeBytes>10*1024*1024||pageCount<1||pageCount>100
                    ||!Set.of("application/pdf","image/png","image/jpeg").contains(mediaType)) invalid();
            id(documentRef);digest(sha256);id(ownerRef);id(principalId);id(subjectRef);id(taskRef);id(sessionRef);
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Location(String kind,int pageIndex,int endPageIndex,int sourcePageBase,
            String unit,String origin,List<Double> box) {
        public Location {
            if(!Set.of("PAGE","REGION","TABLE").contains(kind)||pageIndex<0||endPageIndex<pageIndex
                    ||!Set.of(0,1).contains(sourcePageBase)) invalid();
            box=box==null?List.of():List.copyOf(box);
            if(box.isEmpty()) { if("REGION".equals(kind)||unit!=null||origin!=null) invalid(); }
            else {
                if(!"PERMILLE".equals(unit)||!"TOP_LEFT".equals(origin)||box.size()!=4
                        ||box.stream().anyMatch(v->v==null||!Double.isFinite(v)||v<0||v>1000)
                        ||box.get(0)>=box.get(2)||box.get(1)>=box.get(3)||pageIndex!=endPageIndex) invalid();
            }
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Field(String fieldId,JsonNode originalValue,Location location) {
        public Field { id(fieldId);originalValue=originalValue==null?null:originalValue.deepCopy(); }
        @Override public JsonNode originalValue() { return originalValue==null?null:originalValue.deepCopy(); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Unit(int pageIndex,String state,String code) {
        public Unit { if(pageIndex<0||!Set.of("OBSERVED","UNCONFIRMED","FAILED","MISSING").contains(state)) invalid(); }
    }
    public record Source(String upstreamRevision,String toolReleaseDigest,String entry) {
        public Source { if(!REVISION.equals(upstreamRevision))invalid();digest(toolReleaseDigest);id(entry); }
    }
    public record Result(String schemaVersion,String runRef,String requestId,Document document,Mode mode,
            Status status,List<Field> fields,JsonNode tables,List<Unit> units,Source source,
            String rawRef,Instant createdAt,int revision,List<String> warningCodes) {
        public Result {
            if(!"financial-material-result.v1".equals(schemaVersion)||document==null||mode==null||status==null
                    ||source==null||createdAt==null||revision<0) invalid();
            id(runRef);id(requestId);id(rawRef);
            fields=List.copyOf(fields);units=List.copyOf(units);warningCodes=List.copyOf(warningCodes);
            tables=tables==null?null:tables.deepCopy();
            if(fields.stream().map(Field::fieldId).distinct().count()!=fields.size()) invalid();
            for(Field field:fields) if(field.location()!=null&&field.location().endPageIndex()>=document.pageCount())invalid();
            if(units.stream().anyMatch(u->u.pageIndex()>=document.pageCount()))invalid();
            if(status==Status.COMPLETED&&(units.isEmpty()||units.stream().anyMatch(u->!"OBSERVED".equals(u.state()))))invalid();
        }
        @Override public JsonNode tables() { return tables==null?null:tables.deepCopy(); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Correction(String schemaVersion,String correctionRef,String runRef,String documentRef,
            int documentVersion,String fieldId,JsonNode originalValue,JsonNode newValue,String reason,
            String principalId,Instant createdAt,int expectedRevision,int revision) {
        public Correction {
            if(!"financial-material-correction.v1".equals(schemaVersion)||documentVersion<1||expectedRevision<0
                    ||revision!=expectedRevision+1||createdAt==null||reason==null||reason.isBlank()||reason.length()>1000)invalid();
            id(correctionRef);id(runRef);id(documentRef);id(fieldId);id(principalId);
            originalValue=originalValue==null?null:originalValue.deepCopy();newValue=newValue==null?null:newValue.deepCopy();
        }
        @Override public JsonNode originalValue(){return originalValue==null?null:originalValue.deepCopy();}
        @Override public JsonNode newValue(){return newValue==null?null:newValue.deepCopy();}
    }
    private static void id(String value) { if(value==null||value.isBlank()||value.length()>256)invalid(); }
    private static void digest(String value) { if(value==null||!value.matches("sha256:[a-f0-9]{64}"))invalid(); }
    private static void invalid() { throw new IllegalArgumentException("MATERIAL_CONTRACT_INVALID"); }
}
