package io.github.bitlhk.finance.harness.integrations.postloan;
import io.github.bitlhk.finance.harness.core.HarnessJson;import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;import io.github.bitlhk.finance.harness.providers.postloan.PostLoanRiskReferenceProvider;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class PostLoanRiskTaskTest{
 @TempDir Path temp;
 @Test void snapshotWarningReviewAndZeroExecutionReplay()throws Exception{
  var file=temp.resolve("SKILL.md");Files.writeString(file,"只读贷后核验方法");var method=InstalledPreparationMethod.load("post-loan-risk-verification","0.1.0",file,digest(file),"sha256:lock");
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","rev-1",Map.of("德信商业地产","91440100MA5C00000B")),
    (m,p,refs,facts)->{assertEquals("ORANGE",facts.path("deterministicWarning").path("level").asText());var out=HarnessJson.mapper().createObjectNode();var summary=out.putArray("summary").addObject();summary.put("text","内部确定性预警为橙色，需人工复核。");summary.putArray("factRefs").add("/deterministicWarning/level");var evidence=out.putArray("evidenceInterpretation").addObject();evidence.put("text","存在30天以上逾期信号。");evidence.putArray("factRefs").add("/domains/overdueDays");out.putArray("pendingItems").add("核实最新还款状态");out.putArray("reviewChecklist").add("不得自动调整授信");return out;},method,temp.resolve("reports"),Map.of())){
    long generation=task.select("owner","德信商业地产","核验贷后风险并准备人工复核");var report=task.verify("owner",generation,PostLoanRiskTask.Listener.NONE);
    assertEquals("READY_FOR_REVIEW",report.path("status").asText());assertEquals("ORANGE",report.path("facts").path("deterministicWarning").path("level").asText());
    int revision=report.path("reportRevision").asInt();var reviewed=task.review("owner",report.path("reportId").asText(),revision,"已核实，转人工跟进");assertEquals(revision+1,reviewed.path("reportRevision").asInt());
    var replay=task.replay("owner",report.path("reportId").asText(),revision);assertEquals(0,replay.path("replayEvidence").path("businessProviderCalls").asInt());assertEquals(0,replay.path("replayEvidence").path("modelGenerationCalls").asInt());
  }
 }
 @Test void modelFailurePreservesDeterministicFacts()throws Exception{
  var file=temp.resolve("M.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("鸿远建设","91370000726200000X")),(m,p,r,f)->{throw new IllegalStateException("model");},method,temp.resolve("failed"),Map.of())){
    var report=task.verify("owner",task.select("owner","鸿远建设","核验"),PostLoanRiskTask.Listener.NONE);assertEquals("PARTIAL",report.path("status").asText());assertEquals("YELLOW",report.path("facts").path("deterministicWarning").path("level").asText());
  }
 }
 @Test void rejectsUnauthorizedSubjectAndRevalidatesAuthorityOnRead()throws Exception{
  var file=temp.resolve("AUTH.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  var mapping=new java.util.concurrent.atomic.AtomicReference<>(new PostLoanSubjectMapping("owner","r1",Map.of("鸿远建设","91370000726200000X")));
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),mapping::get,(m,p,r,f)->valid("YELLOW"),method,temp.resolve("auth"),Map.of())){
   assertThrows(SecurityException.class,()->task.select("owner","未知企业","核验"));
   var report=task.verify("owner",task.select("owner","鸿远建设","核验"),PostLoanRiskTask.Listener.NONE);
   mapping.set(new PostLoanSubjectMapping("owner","r2",Map.of("鸿远建设","91370000726200000X")));
   assertThrows(SecurityException.class,()->task.read("owner",report.path("reportId").asText()));
  }
 }
 @Test void rejectsUnknownFactReferenceButKeepsSnapshot()throws Exception{
  var file=temp.resolve("REF.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("鸿远建设","91370000726200000X")),
    (m,p,r,f)->{var out=valid("YELLOW");out.path("summary").path(0).path("factRefs").forEach(n->{});((com.fasterxml.jackson.databind.node.ArrayNode)out.path("summary").path(0).path("factRefs")).removeAll().add("/unknown");return out;},method,temp.resolve("refs"),Map.of())){
   var report=task.verify("owner",task.select("owner","鸿远建设","核验"),PostLoanRiskTask.Listener.NONE);
   assertEquals("PARTIAL",report.path("status").asText());assertTrue(report.has("facts"));assertEquals("REPORT_MODEL_FAILED",report.path("failureCode").asText());
  }
 }
 @Test void authorityChangeDuringGenerationDiscardsLateResult()throws Exception{
  var file=temp.resolve("LATE.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  var mapping=new java.util.concurrent.atomic.AtomicReference<>(new PostLoanSubjectMapping("owner","r1",Map.of("鸿远建设","91370000726200000X")));
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),mapping::get,
    (m,p,r,f)->{mapping.set(new PostLoanSubjectMapping("owner","r2",Map.of("鸿远建设","91370000726200000X")));return valid("YELLOW");},method,temp.resolve("late"),Map.of())){
   assertThrows(SecurityException.class,()->task.verify("owner",task.select("owner","鸿远建设","核验"),PostLoanRiskTask.Listener.NONE));
   assertTrue(task.latest("owner").isEmpty());
  }
 }
 private static com.fasterxml.jackson.databind.node.ObjectNode valid(String level){var out=HarnessJson.mapper().createObjectNode();var summary=out.putArray("summary").addObject();summary.put("text","内部确定性预警为"+level+"，需人工复核。");summary.putArray("factRefs").add("/deterministicWarning/level");var evidence=out.putArray("evidenceInterpretation").addObject();evidence.put("text","存在来源事实。");evidence.putArray("factRefs").add("/domains/overdueDays");out.putArray("pendingItems").add("核实最新状态");out.putArray("reviewChecklist").add("不得自动调整授信");return out;}
 private static String digest(Path file)throws Exception{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
