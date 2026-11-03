package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerServiceTaskTest {
    private class Access implements PreparationAccess {
        boolean revoked;
        int calls;
        public Snapshot authorize(String customer) {
            if(revoked || !"CUST-1".equals(customer)) throw new SecurityException("CRM_SCOPE_REJECTED");
            return new Snapshot("authority-1","employee-1", FinanceContextV1.AuthenticationLevel.STRONG);
        }
        public String providerId() { return "customer-crm-readonly"; }
        public CapabilityResultV1 query(String request, String trace, String customer, String capability, Map<String,Object> args) {
            calls++;
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,request,trace,
                    CapabilityResultV1.Status.COMPLETED,"fixture",Map.of("previsitContext",Map.of(
                    "profiles",List.of(Map.of("profileType","wealth","detail",Map.of(
                            "riskAssessment",Map.of("riskLevel","C3","validUntil","2027-01-01"),
                            "portfolioSummary",Map.of("currency","CNY","totalAssets",320000,"availableCash",80000),
                            "holdings",List.of(Map.of("productName","稳健组合","balance",120000,"currency","CNY",
                                    "dataAsOf","2026-11-03")),
                            "recentEvents",List.of(Map.of("type","MATURITY_DUE","date","2026-12-01"))))))),
                    List.of(),List.of(),"fixture", Instant.now(),null,null);
        }
        public void close() {}
    }

    @Test void returnsAuthorizedCustomerFactsWithoutCreatingPreparationReport() {
        var access=new Access();
        var task=new CustomerServiceTask(access,alias -> "周明远".equals(alias)?"CUST-1":null);
        var result=task.run("看看周明远的持仓和到期事项","周明远","run-1");
        String response=(String)result.get("response");
        assertTrue(response.contains("稳健组合"));
        assertTrue(response.contains("MATURITY_DUE"));
        assertTrue(response.contains("来源快照"));
        assertEquals(1,access.calls);
    }

    @Test void explicitPreparationIntentIsNotCapturedByFactsRoute() {
        assertTrue(CustomerServiceTask.handles("查看周明远的持仓和到期事项"));
        assertFalse(CustomerServiceTask.handles("为周明远准备回访，核对持仓和到期事项并给出沟通话术"));
    }

    @Test void authorityChangeBlocksRelease() {
        var access=new Access() {
            @Override public CapabilityResultV1 query(String request,String trace,String customer,String capability,Map<String,Object> args) {
                CapabilityResultV1 result=super.query(request,trace,customer,capability,args);revoked=true;return result;
            }
        };
        var task=new CustomerServiceTask(access,alias -> "CUST-1");
        assertThrows(SecurityException.class,()->task.run("查看持仓","周明远","run-1"));
    }

    @Test void requiresExplicitCustomer() {
        var access=new Access();
        var result=new CustomerServiceTask(access,alias -> "CUST-1").run("查看持仓",null,"run-1");
        assertEquals("NEED_CLARIFICATION",result.get("status"));
        assertEquals(0,access.calls);
    }
}
