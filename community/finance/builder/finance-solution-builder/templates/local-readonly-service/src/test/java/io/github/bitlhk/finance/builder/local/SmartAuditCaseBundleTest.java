package io.github.bitlhk.finance.builder.local;

import static org.junit.Assert.*;
import org.junit.Test;

public class SmartAuditCaseBundleTest {
    private static String query(String materials) {
        return "SMART_AUDIT_CASE_BUNDLE_V1\n"
                + "仅供受控测试。\n"
                + "{\"schemaVersion\":\"smart-audit-demo-materials.v1\","
                + "\"scenario\":\"personal-loan-audit\",\"materials\":" + materials + "}";
    }

    @Test public void acceptsTheCompletePersonalLoanBundleAndPreservesOnlyDeclaredMaterials() throws Exception {
        var bundle = SmartAuditCaseBundle.from(query("["
                + "{\"category\":\"身份证\",\"content\":\"材料一\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"户口本\",\"content\":\"材料二\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"结婚证\",\"content\":\"材料三\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"贷款申请表\",\"content\":\"材料四\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"收入证明\",\"content\":\"材料五\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"流水明细\",\"content\":\"材料六\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"个人所得税完税证明\",\"content\":\"材料七\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"社会保险参保证明\",\"content\":\"材料八\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"不动产权证书\",\"content\":\"材料九\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"不动产信息查询结果\",\"content\":\"材料十\",\"declaredFormat\":\"TEXT\"},"
                + "{\"category\":\"贷款结清证明\",\"content\":\"材料十一\",\"declaredFormat\":\"TEXT\"}"
                + "]"));

        assertNotNull(bundle);
        assertEquals(11, bundle.materials().size());
        assertEquals(SmartAuditCaseBundle.PERSONAL_CAPABILITIES, bundle.capabilityIds());
        assertEquals(4, materialCount(bundle, "smartaudit.personal.borrower-qualification.query"));
        assertEquals(4, materialCount(bundle, "smartaudit.personal.income-verification.query"));
        assertEquals(3, materialCount(bundle, "smartaudit.personal.asset-liability.query"));
        assertEquals(3, materialCount(bundle, "smartaudit.personal.repayment-capacity.query"));
        assertEquals("个税", materialCategory(bundle, "smartaudit.personal.income-verification.query", 2));
        assertEquals("社保", materialCategory(bundle, "smartaudit.personal.income-verification.query", 3));
        assertEquals("不动产权证", materialCategory(bundle, "smartaudit.personal.asset-liability.query", 0));
        assertEquals("产权查询", materialCategory(bundle, "smartaudit.personal.asset-liability.query", 1));
    }

    @Test public void rejectsAPartialOrUnmarkedBundle() throws Exception {
        assertNull(SmartAuditCaseBundle.from("普通聊天"));
        assertThrows(IllegalArgumentException.class, () -> SmartAuditCaseBundle.from(query("[]")));
    }

    private static int materialCount(SmartAuditCaseBundle.Bundle bundle, String capabilityId) {
        return ((java.util.List<?>) bundle.argumentsFor(capabilityId).get("materials")).size();
    }

    @SuppressWarnings("unchecked")
    private static String materialCategory(SmartAuditCaseBundle.Bundle bundle, String capabilityId, int index) {
        var materials = (java.util.List<java.util.Map<String, Object>>) bundle.argumentsFor(capabilityId).get("materials");
        return (String) materials.get(index).get("category");
    }
}
