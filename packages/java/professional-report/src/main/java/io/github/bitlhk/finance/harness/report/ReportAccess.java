package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Predicate;

/** The Host supplies current authority; neither a saved report nor a model can grant it. */
public final class ReportAccess {
    private final String owner;
    private final Predicate<JsonNode> currentAuthority;
    public ReportAccess(String owner,Predicate<JsonNode> currentAuthority) {
        ReportMapper.id(owner);this.owner=owner;this.currentAuthority=Objects.requireNonNull(currentAuthority);
    }
    public void caller(String caller) {
        if(!owner.equals(caller))throw new SecurityException("REPORT_OWNER_REJECTED");
    }
    public void require(String caller,JsonNode scope) {
        caller(caller);ReportMapper.fields(scope,ReportMapper.SCOPE);
        if(!owner.equals(scope.path("ownerRef").asText()))throw new SecurityException("REPORT_OWNER_REJECTED");
        try {if(!currentAuthority.test(scope.deepCopy()))throw new SecurityException("REPORT_CURRENT_AUTHORITY_REQUIRED");}
        catch(SecurityException denied){throw denied;}
        catch(Exception failure){throw new SecurityException("REPORT_AUTHORITY_UNAVAILABLE");}
    }
}
