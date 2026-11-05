package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportAccessTest {
    ObjectNode scope(){return ReportMapper.json().createObjectNode().put("ownerRef","owner").put("customerRef","customer").put("taskRef","task").put("authorityRef","authority");}
    @Test void currentOwnerCustomerTaskAndAuthorityAreRequired() {
        var expected=scope();var active=new AtomicBoolean(true);var access=new ReportAccess("owner",s->active.get()&&s.equals(expected));
        access.require("owner",expected);
        assertThrows(SecurityException.class,()->access.require("other",expected));
        for(String key:new String[]{"customerRef","taskRef","authorityRef"}) {
            var changed=scope();changed.put(key,"other");assertThrows(SecurityException.class,()->access.require("owner",changed));
        }
        active.set(false);assertThrows(SecurityException.class,()->access.require("owner",expected));
    }
    @Test void savedScopeCannotGrantItselfAndAuthorityFailuresAreExplicit() {
        var access=new ReportAccess("owner",s->{throw new IllegalStateException("private failure");});
        var failure=assertThrows(SecurityException.class,()->access.require("owner",scope()));assertEquals("REPORT_AUTHORITY_UNAVAILABLE",failure.getMessage());
        assertThrows(NullPointerException.class,()->new ReportAccess("owner",null));
    }
}
