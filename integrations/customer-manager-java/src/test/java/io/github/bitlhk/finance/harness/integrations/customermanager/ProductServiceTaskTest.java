package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProductServiceTaskTest {
    private static CapabilityResultV1 catalog() {
        var bound = new LinkedHashMap<String,Object>();
        bound.put("productId", "132112"); bound.put("name", "招银理财招睿添利日开21号固定收益类理财计划");
        bound.put("riskLevel", 2); bound.put("minimumAmount", 10000); bound.put("termMonths", 1);
        bound.put("salesStatus", "on_sale"); bound.put("salesTrust", "DEMO"); bound.put("channels", List.of("branch"));
        bound.put("businessIdentity", Map.of("managerId","cmb-wealth-management","managerProductCode","132112",
                "registrationCode","Z7001626000062","distributorId","bank-of-nanjing","salesCode","132112N","shareClass","N"));
        var plain = Map.<String,Object>of("productId","SYN-FI-002","name","稳健固收增强A","riskLevel",2,
                "minimumAmount",10000,"termMonths",6,"salesStatus","on_sale","salesTrust","DEMO",
                "channels",List.of("branch","mobile"));
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,"request-a","trace-a",
                CapabilityResultV1.Status.COMPLETED,"ok",Map.of("schema","kyp-product-context.v1",
                "products",List.of(Map.copyOf(bound),plain),"sourceId","fixture","normalizedContentDigest","a".repeat(64)),
                List.of(),List.of(),"fixture", Instant.parse("2026-11-03T00:00:00Z"),null,null);
    }

    @Test void productListDoesNotInvokeKnowledgeOrRequireCustomerInputs() {
        var calls = new AtomicInteger();
        var task = new ProductServiceTask((request, trace, keyword) -> catalog(),
                (question, run, product) -> { calls.incrementAndGet(); return Map.of(); });
        var result = task.run("有哪些产品", "run-a");
        assertEquals("ANSWERED", result.get("status"));
        assertTrue(result.get("response").toString().contains("132112"));
        assertEquals(0, calls.get());
    }

    @Test void uniqueBoundProductMayUseKnowledgeWhileAnotherProductCannotBorrowIt() {
        var calls = new AtomicInteger();
        var task = new ProductServiceTask((request, trace, keyword) -> catalog(),
                (question, run, product) -> {
                    calls.incrementAndGet();
                    if ("132112".equals(product.path("productId").asText()))
                        return Map.of("response","PR2原文依据","manifest","b".repeat(64),"toolResults",List.of());
                    return Map.of("response","当前产品只有产品来源事实，未装配与其身份一致的固定版本产品手册。");
                });
        assertTrue(task.run("132112 是哪个风险等级的产品", "run-a").get("response").toString().contains("PR2原文依据"));
        var other = task.run("SYN-FI-002 是什么产品", "run-b");
        assertFalse(other.get("response").toString().contains("PR2原文依据"));
        assertEquals(2, calls.get());
    }

    @Test void comparisonUsesFactsAndStatesTheSuitabilityBoundary() {
        var task = new ProductServiceTask((request, trace, keyword) -> catalog(), null);
        var result = task.run("比较 132112 和 SYN-FI-002", "run-a");
        assertEquals("ANSWERED", result.get("status"));
        assertTrue(result.get("response").toString().contains("不判断客户适当性"));
    }

    @Test void comparisonUsesRememberedProductAndKeepsKnowledgeCoverageSeparate() {
        var calls=new AtomicInteger();
        var task=new ProductServiceTask((request,trace,keyword)->catalog(),(question,run,product)->{
            calls.incrementAndGet();
            return "132112".equals(product.path("productId").asText())
                    ? Map.of("response","产品手册载明PR2及申赎安排。","toolResults",List.of())
                    : Map.of("response","当前产品只有产品来源事实，未装配与其身份一致的固定版本产品手册。");
        });
        var result=task.run("和 SYN-FI-002 比较一下，重点看风险、流动性和费用","run-a",List.of("132112"));
        assertEquals("ANSWERED",result.get("status"));
        assertEquals(List.of("SYN-FI-002","132112"),result.get("selectedProductRefs"));
        assertTrue(result.get("response").toString().contains("产品资料覆盖"));
        assertTrue(result.get("response").toString().contains("产品手册载明PR2"));
        assertTrue(result.get("response").toString().contains("只有产品来源事实"));
        assertEquals(2,calls.get());
    }

    @Test void detailAcceptsNaturalLookAtWording() {
        var task=new ProductServiceTask((request,trace,keyword)->catalog(),null);
        var result=task.run("帮我看看招银理财招睿添利日开21号","run-a");
        assertEquals("ANSWERED",result.get("status"));
        assertEquals(List.of("132112"),result.get("selectedProductRefs"));
    }

    @Test void uniqueProductCanBeResolvedByNaturalShortName() {
        var task = new ProductServiceTask((request, trace, keyword) -> catalog(), null);
        var result = task.run("招银理财招睿添利日开21号是什么风险等级", "run-a");
        assertEquals("ANSWERED", result.get("status"));
        assertEquals("132112", ((Map<?,?>)result.get("product")).get("productId"));
    }

    @Test void explicitWrongShareCannotBorrowAnotherSharesKnowledge() {
        var calls = new AtomicInteger();
        var task = new ProductServiceTask((request, trace, keyword) -> catalog(),
                (question, run, product) -> { calls.incrementAndGet(); return Map.of("response", "must-not-run"); });
        var result = task.run("招银理财招睿添利日开21号A份额是什么风险等级", "run-a");
        assertEquals("NEED_CLARIFICATION", result.get("status"));
        assertFalse(result.get("response").toString().contains("must-not-run"));
        assertEquals(0, calls.get());

        result = task.run("132112A 是什么风险等级", "run-b");
        assertEquals("NEED_CLARIFICATION", result.get("status"));
        assertEquals(0, calls.get());
    }

    @Test void productPlanAndShareQuestionsCannotBypassProductIdentityRouting() {
        assertTrue(ProductServiceTask.handles("招银理财招睿添利日开21号固定收益类理财计划是什么风险等级"));
        assertTrue(ProductServiceTask.handles("招银理财招睿添利日开21号A份额是什么风险等级"));
    }

    @Test void baseProductNameResolvesBoundShareWhenNoShareWasClaimed() {
        var calls = new AtomicInteger();
        var task = new ProductServiceTask((request, trace, keyword) -> catalog(),
                (question, run, product) -> { calls.incrementAndGet(); return Map.of("response", "PR2原文依据"); });
        var result = task.run("招银理财招睿添利日开21号固定收益类理财计划是什么风险等级", "run-a");
        assertEquals("ANSWERED", result.get("status"));
        assertTrue(result.get("response").toString().contains("PR2原文依据"));
        assertEquals(1, calls.get());
    }

    @Test void explicitCorrectShareStillAllowsComparisonWithUnclassifiedProduct() {
        var task = new ProductServiceTask((request, trace, keyword) -> catalog(), null);
        var result = task.run("比较稳健固收增强A和招银理财招睿添利日开21号N份额", "run-a");
        assertEquals("ANSWERED", result.get("status"));
        assertEquals(2, ((List<?>) result.get("products")).size());
    }

    @Test void boundedRouterDoesNotCaptureCustomerProductScreening() {
        assertTrue(ProductServiceTask.handles("有哪些产品"));
        assertTrue(ProductServiceTask.handles("132112 是哪个风险等级的产品"));
        assertTrue(ProductServiceTask.handles("招银理财招睿添利日开21号是什么风险等级"));
        assertFalse(ProductServiceTask.handles("为周明远筛选适合的产品"));
    }
}
