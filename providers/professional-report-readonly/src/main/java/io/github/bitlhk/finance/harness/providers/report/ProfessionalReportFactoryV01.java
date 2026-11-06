package io.github.bitlhk.finance.harness.providers.report;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.report.ReportMapper;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Host-owned immutable binding. Neither the model nor component metadata selects credentials. */
public final class ProfessionalReportFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String KIND="professional-report-http.v0.1";
    private final String path,digest;
    private Config frozen;
    public record Binding(String principalId,String subjectRef,String sessionRef,JsonNode context,JsonNode request) {
        public Binding {
            if(principalId==null||subjectRef==null||sessionRef==null||!principalId.matches("[A-Za-z0-9_-]{1,128}")
                    ||!subjectRef.matches("[A-Za-z0-9_-]{1,128}")||!sessionRef.matches("[A-Za-z0-9_-]{1,128}"))invalid();
            if(context==null||!context.isObject()||context.size()!=4)invalid();
            for(String key:ReportMapper.SCOPE)if(!context.path(key).isTextual()||!context.path(key).asText().matches("[A-Za-z0-9_-]{1,128}"))invalid();
            if(!principalId.equals(context.path("ownerRef").asText())||!subjectRef.equals(context.path("customerRef").asText()))invalid();
            if(request==null||!request.isObject()||request.size()!=6||!request.path("schemaVersion").asText().equals("finance-professional-report-request.v1")
                    ||!Set.of("wealth-kyc","market-risk-report").contains(request.path("assetId").asText())||!request.path("caseId").isTextual()
                    ||!request.path("tool").isTextual()||!request.path("arguments").isObject())invalid();
            var b=request.path("binding");if(!b.isObject()||b.size()!=ReportMapper.BINDING.size())invalid();
            for(String key:ReportMapper.BINDING)if(!b.path(key).isTextual()||b.path(key).asText().isBlank())invalid();
            for(String key:List.of("methodDigest","sourceReleaseDigest","inputDigest"))if(!b.path(key).asText().matches("sha256:[a-f0-9]{64}"))invalid();
            if(!b.path("methodVersion").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"))invalid();
            boolean wealth=request.path("assetId").asText().equals("wealth-kyc");
            if(!b.path("methodKind").asText().equals(wealth?"ORIGINAL_SKILL":"DERIVED_PROCEDURE"))invalid();
            if(!wealth&&(!Set.of("pipeline-default","original-e2e").contains(request.path("caseId").asText())||!request.path("tool").asText().equals("market_risk_report")||!request.path("arguments").isEmpty()))invalid();
            context=context.deepCopy();request=request.deepCopy();
        }
        @Override public JsonNode context(){return context.deepCopy();}
        @Override public JsonNode request(){return request.deepCopy();}
        public String capability(){return ProfessionalReportProvider.capability(request.path("assetId").asText());}
    }
    public record Config(String schemaVersion,List<Binding> bindings) {
        public Config {
            if(!"professional-report-bindings.v1".equals(schemaVersion)||bindings==null||bindings.isEmpty()||bindings.size()>128)invalid();
            bindings=List.copyOf(bindings);Set<List<String>> unique=new HashSet<>();
            for(var b:bindings)if(!unique.add(List.of(b.principalId(),b.subjectRef(),b.sessionRef())))invalid();
        }
        public Binding binding(String principal,String subject,String session) {
            return bindings.stream().filter(b->b.principalId().equals(principal)&&b.subjectRef().equals(subject)&&b.sessionRef().equals(session))
                .findFirst().orElseThrow(()->new SecurityException("REPORT_NOT_BOUND"));
        }
    }
    public ProfessionalReportFactoryV01(){this(System.getenv("FH_REPORT_BINDINGS"),System.getenv("FH_REPORT_BINDINGS_SHA256"));}
    public ProfessionalReportFactoryV01(String path,String digest){this.path=path;this.digest=digest!=null&&digest.startsWith("sha256:")?digest.substring(7):digest;}
    private static void invalid(){throw new IllegalArgumentException("REPORT_CONFIG_INVALID");}
    public synchronized Config config() {
        if(frozen==null)try {
            if(path==null||digest==null||!digest.matches("[a-f0-9]{64}"))invalid();
            Path f=Path.of(path);if(!f.isAbsolute()||!f.toRealPath().equals(f.normalize())||Files.size(f)>262144)invalid();
            byte[] bytes=Files.readAllBytes(f);if(!ReportMapper.sha(bytes).equals("sha256:"+digest))invalid();
            frozen=ReportMapper.json().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(bytes,Config.class);
        }catch(Exception failure){invalid();}
        return frozen;
    }
    @Override public String providerId(){return ProfessionalReportProvider.ID;}
    @Override public String connectionKind(){return KIND;}
    @Override public Map<String,Set<String>> requirements(){return Map.of("professional.report.wealth.query",Set.of(ProfessionalReportProvider.SCOPE),"professional.report.market.query",Set.of(ProfessionalReportProvider.SCOPE));}
    @Override public Map<String,String> configurationDigests(){config();return Map.of("binding-file-sha256",digest);}
    @Override public DescribedFinanceProvider create(Connection c,Map<String,BoundFinanceProvider> deps){
        if(!deps.isEmpty())throw new IllegalArgumentException("REPORT_DEPENDENCIES_INVALID");
        return new ProfessionalReportProvider(c,config(),Clock.systemUTC());
    }
}
