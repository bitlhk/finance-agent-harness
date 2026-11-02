package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Business envelopes; credentials are deliberately absent from persisted request state. */
public final class CreditContracts {
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private CreditContracts() {}
    public static String id(String value) {
        if(value==null || !value.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("INVALID_ID");
        return value;
    }
    public static ObjectNode object(JsonNode value) {
        if(value==null || !value.isObject()) throw new IllegalArgumentException("INVALID_OBJECT");
        return ((ObjectNode)value).deepCopy();
    }
    public static String required(JsonNode value,String field) {
        if(!value.path(field).isTextual() || value.path(field).asText().isBlank())
            throw new IllegalArgumentException("INVALID_"+field);
        return value.path(field).asText();
    }
    public static void fields(JsonNode value,Set<String> allowed) {
        object(value).fieldNames().forEachRemaining(key->{if(!allowed.contains(key))
            throw new IllegalArgumentException("UNKNOWN_FIELD");});
    }
    public static String hash(JsonNode value) {
        try {
            StringBuilder canonical=new StringBuilder();canonical(value,canonical);
            byte[] bytes=canonical.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(Exception failure){throw new IllegalArgumentException("INVALID_CANONICAL_JSON");}
    }
    /** Match the existing Python json.dumps(sort_keys=True, ensure_ascii=False) receipt contract. */
    private static void canonical(JsonNode value,StringBuilder out) throws java.io.IOException {
        if(value.isObject()){
            out.append('{');var names=new ArrayList<String>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);
            boolean first=true;for(String name:names){if(!first)out.append(',');first=false;
                out.append(JSON.writeValueAsString(name)).append(':');canonical(value.get(name),out);}
            out.append('}');
        }else if(value.isArray()){
            out.append('[');boolean first=true;for(var item:value){if(!first)out.append(',');first=false;canonical(item,out);}out.append(']');
        }else if(value.isFloatingPointNumber()){
            double n=value.doubleValue();if(!Double.isFinite(n))throw new IllegalArgumentException("NON_FINITE_NUMBER");
            if(n==0){out.append(Double.doubleToRawLongBits(n)<0?"-0.0":"0.0");return;}
            double magnitude=Math.abs(n);String shortest=Double.toString(n);
            if(magnitude>=0.0001&&magnitude<1e16){
                String plain=new java.math.BigDecimal(shortest).stripTrailingZeros().toPlainString();
                out.append(plain);if(!plain.contains("."))out.append(".0");
            }else{
                var decimal=new java.math.BigDecimal(shortest).stripTrailingZeros();
                int exponent=decimal.precision()-decimal.scale()-1;
                String coefficient=decimal.movePointLeft(exponent).stripTrailingZeros().toPlainString();
                out.append(coefficient).append('e').append(exponent<0?'-':'+')
                    .append(String.format(java.util.Locale.ROOT,"%02d",Math.abs(exponent)));
            }
        }else out.append(JSON.writeValueAsString(value));
    }
    public record Binding(ObjectNode value) {
        public Binding {
            value=object(value);
            fields(value,Set.of("tenant_id","principal_id","customer_id","case_id","observed_at",
                    "visible_source_ids","snapshot_digest"));
            for(String key:List.of("tenant_id","principal_id","customer_id","case_id"))id(required(value,key));
            Instant.parse(required(value,"observed_at"));
            if(!required(value,"snapshot_digest").matches("[a-f0-9]{64}")
                    ||!value.path("visible_source_ids").isArray())throw new IllegalArgumentException("INVALID_BINDING");
            for(JsonNode source:value.path("visible_source_ids")) {
                if(!source.isTextual())throw new IllegalArgumentException("INVALID_SOURCE");id(source.asText());
            }
        }
        @Override public ObjectNode value(){return value.deepCopy();}
        public String owner(){return value.path("tenant_id").asText()+":"+value.path("principal_id").asText();}
        public String customer(){return value.path("customer_id").asText();}
    }
    public record Turn(String operationKey,String sessionRef,String scene,String question,
                       Binding binding,Instant deadline,int topK,List<String> engines) {
        public Turn(String operationKey,String sessionRef,String scene,String question,Binding binding,Instant deadline){
            this(operationKey,sessionRef,scene,question,binding,deadline,3,List.of("llm"));
        }
        public Turn {
            if(topK<1||topK>5||engines==null||engines.isEmpty()||engines.size()>2
                    ||new HashSet<>(engines).size()!=engines.size()||!Set.of("llm","onerec").containsAll(engines))
                throw new IllegalArgumentException("INVALID_RECOMMENDATION_OPTIONS");
            engines=List.copyOf(engines);
            id(operationKey);id(sessionRef);Objects.requireNonNull(binding);Objects.requireNonNull(deadline);
            if(!Set.of("limit-risk","benefits").contains(scene)||question==null||question.isBlank()
                    ||question.length()>1000)throw new IllegalArgumentException("INVALID_TURN");
        }
        public long remainingMillis(){return Math.max(0,java.time.Duration.between(Instant.now(),deadline).toMillis());}
        public void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            if(remainingMillis()==0)throw new IllegalStateException("DEADLINE_EXCEEDED");}
        public ObjectNode fingerprint() {
            ObjectNode v=JSON.createObjectNode();v.put("session",sessionRef).put("scene",scene).put("question",question);
            v.set("binding",binding.value());v.put("top_k",topK);v.set("engines",JSON.valueToTree(engines));return v;
        }
    }
}
