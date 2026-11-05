package io.github.bitlhk.finance.harness.integrations.report;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.report.ReportMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportAuthorizationIntegrationTest {
    @TempDir Path temp;
    @Test void historyVersionsNotesAndExportsAllRequireCurrentAuthority() throws Exception {
        try(var f=new ReportNativeMethodTest.Fixture(temp);var s=new ReportSession(f.context)) {
            var result=s.call("generate",ReportMapper.json().valueToTree(Map.of("query","画像")));
            assertEquals("SUCCEEDED",result.get("status"));String id=result.get("reportId").toString();
            var report=ReportMapper.json().valueToTree(result.get("report"));String artifact=report.path("artifacts").get(0).path("id").asText();
            f.revoke();
            for(String action:List.of("history","status","versions","result","note","artifact")) {
                Map<String,Object> request=switch(action) {
                    case "history","status"->Map.of();case "versions"->Map.of("reportId",id);
                    case "note"->Map.of("reportId",id,"revision",1,"text","未授权备注");
                    case "artifact"->Map.of("reportId",id,"revision",1,"artifactId",artifact);
                    default->Map.of("reportId",id,"revision",1);
                };
                assertThrows(SecurityException.class,()->s.call(action,ReportMapper.json().valueToTree(request)),action);
            }
        }
    }
    @Test void changedCustomerTaskOrOwnerCannotUseOriginalBinding() throws Exception {
        try(var f=new ReportNativeMethodTest.Fixture(temp)) {
            byte[] original=Files.readAllBytes(f.authorityFile);
            for(String field:List.of("principalId","subjectRef","taskRef")) {
                ObjectNode authority=(ObjectNode)ReportMapper.parse(original);
                ((ObjectNode)authority.path("leases").get(0)).put(field,"other");Files.write(f.authorityFile,ReportMapper.bytes(authority));
                assertThrows(SecurityException.class,()->{try(var scope=new ReportSourceScope(f.context)){scope.current();}},field);
            }
            assertEquals(0,f.calls.get());
        }
    }
}
