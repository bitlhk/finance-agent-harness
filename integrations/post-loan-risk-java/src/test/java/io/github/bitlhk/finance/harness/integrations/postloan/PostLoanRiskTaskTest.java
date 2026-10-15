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
 @Test void authorityLanguageUnderstandsNegationAttributionAndContradiction(){
  for(String safe:List.of("禁止自动调整授信","不得由 Agent 自动调整授信","不应自动调整授信","无需自动调整授信",
      "不得自动调低额度","有人建议自动调整授信，但本报告不采纳",
      "客户经理说应自动调整授信，但本报告不采纳","YELLOW预警不构成监管五级分类结论",
      "内部预警不等于监管五级分类结论",
      "确保不依据内部预警自动调整授信或分类，仅人工复核。",
      "不应因为ORANGE预警自动变更分类","不能基于单一信号自动降低额度"))assertFalse(PostLoanRiskTask.unsafeAuthorityClaim(safe),safe);
  for(String unsafe:List.of("建议自动调整授信","客户经理说应自动调整授信","不得不自动调整授信",
      "建议系统自动调低额度","建议自动变更分类",
      "不应忽略风险，应自动调整授信",
      "不得自动调整授信，但建议系统自动调整授信",
      "不得自动调整授信，但应根据内部预警自动调低额度",
      "有人说应自动调整授信，尚未表明拒绝该建议",
      "并非不构成监管五级分类结论"))assertTrue(PostLoanRiskTask.unsafeAuthorityClaim(unsafe),unsafe);
 }
 @Test void collateralConclusionRequiresMatchedVersionedRuleEvidence()throws Exception{
  var file=temp.resolve("COLLATERAL.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("德信","91440100MA5C00000B")),
    (m,p,r,f)->{var out=valid("ORANGE");var item=(com.fasterxml.jackson.databind.node.ObjectNode)out.path("evidenceInterpretation").path(0);
      item.put("text","担保覆盖率不足，需人工复核。");item.putArray("factRefs").add("/domains/collateralCoverageRatio");return out;},method,temp.resolve("coverage"),Map.of())){
   var report=task.verify("owner",task.select("owner","德信","核验"),PostLoanRiskTask.Listener.NONE);
   assertEquals("PARTIAL",report.path("status").asText());assertEquals("REPORT_MODEL_RULE_EVIDENCE_REJECTED",report.path("failureCode").asText());
   assertEquals("COLLATERAL_RULE_EVIDENCE_REQUIRED",report.at("/failureDetail/rule").asText());
   assertEquals(1.1,report.at("/facts/deterministicWarning/collateralCoverageRule/actual").asDouble());
  }
 }
 @Test void collateralValueWithoutUnsupportedConclusionRemainsReviewable()throws Exception{
  var file=temp.resolve("COLLATERAL-VALUE.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("德信","91440100MA5C00000B")),
    (m,p,r,f)->{var out=valid("ORANGE");var item=(com.fasterxml.jackson.databind.node.ObjectNode)out.path("evidenceInterpretation").path(0);
      item.put("text","担保覆盖率为1.1；当前缺少可据此判断充足或不足的适用依据。");item.putArray("factRefs").add("/domains/collateralCoverageRatio");return out;},method,temp.resolve("coverage-value"),Map.of())){
   var report=task.verify("owner",task.select("owner","德信","核验"),PostLoanRiskTask.Listener.NONE);
   assertEquals("READY_FOR_REVIEW",report.path("status").asText());
  }
 }
 @Test void matchingNegatedCollateralConclusionIsCanonicalizedWithCompleteRuleEvidence()throws Exception{
  var file=temp.resolve("COLLATERAL-NEGATED.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("鸿远","91370000726200000X")),
    (m,p,r,f)->{var out=valid("YELLOW");var item=(com.fasterxml.jackson.databind.node.ObjectNode)out.path("evidenceInterpretation").path(0);
      item.put("text","担保覆盖率1.2高于阈值1.0，规则未匹配，不构成担保不足。");
      item.putArray("factRefs").add("/domains/collateralCoverageRatio");return out;},method,temp.resolve("coverage-negated"),Map.of())){
   var report=task.verify("owner",task.select("owner","鸿远","核验"),PostLoanRiskTask.Listener.NONE);
   assertEquals("READY_FOR_REVIEW",report.path("status").asText());
   assertTrue(report.at("/modelExplanation/evidenceInterpretation/0/text").asText().contains("规则2.0"));
   assertEquals(5,report.at("/modelExplanation/evidenceInterpretation/0/factRefs").size());
  }
 }
 @Test void frozenR50ThresholdNegationIsAcceptedButContradictoryAssertionIsRejected()throws Exception{
  var file=temp.resolve("R50.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  for(String text:List.of(
      "担保覆盖率1.2，未触发阈值1.0的不足规则，但matched=false不代表担保充足。",
      "担保覆盖率1.2，不构成该规则下的担保不足，也不能据此认定担保充足。")){
   try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("鸿远","91370000726200000X")),
     (m,p,r,f)->{var out=valid("YELLOW");var item=(com.fasterxml.jackson.databind.node.ObjectNode)out.path("evidenceInterpretation").path(0);
       item.put("text",text);item.putArray("factRefs").add("/domains/collateralCoverageRatio");return out;},method,temp.resolve("safe-"+Math.abs(text.hashCode())),Map.of())){
    var report=task.verify("owner",task.select("owner","鸿远","核验"),PostLoanRiskTask.Listener.NONE);
    assertEquals("READY_FOR_REVIEW",report.path("status").asText(),text);
   }
  }
  String contradiction="担保覆盖率1.2，未触发阈值1.0的不足规则，但仍认为担保不足。";
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("鸿远","91370000726200000X")),
    (m,p,r,f)->{var out=valid("YELLOW");var item=(com.fasterxml.jackson.databind.node.ObjectNode)out.path("evidenceInterpretation").path(0);
      item.put("text",contradiction);item.putArray("factRefs").add("/domains/collateralCoverageRatio");return out;},method,temp.resolve("contradiction"),Map.of())){
   var report=task.verify("owner",task.select("owner","鸿远","核验"),PostLoanRiskTask.Listener.NONE);
   assertEquals("PARTIAL",report.path("status").asText());
   assertEquals("COLLATERAL_RULE_EVIDENCE_REQUIRED",report.at("/failureDetail/rule").asText());
  }
 }
 @Test void frozenR47OutputPassesNegationThenFailsMissingCollateralBasis()throws Exception{
  var file=temp.resolve("R47.md");Files.writeString(file,"方法");var method=InstalledPreparationMethod.load("m","1",file,digest(file),"sha256:l");
  try(var task=new PostLoanRiskTask("owner",new PostLoanRiskReferenceProvider(),()->new PostLoanSubjectMapping("owner","r",Map.of("德信","91440100MA5C00000B")),
    (m,p,r,f)->{var out=valid("ORANGE");
      ((com.fasterxml.jackson.databind.node.ArrayNode)out.path("reviewChecklist")).removeAll().add("确认预警等级仅供内部演示，且不触发自动处置，禁止自动调整授信或分类。");
      var item=(com.fasterxml.jackson.databind.node.ObjectNode)out.path("evidenceInterpretation").path(0);
      item.put("text","贷款余额10亿，经营现金流为负，担保覆盖率1.1，存在资产冻结，需评估担保覆盖率不足的风险影响。");
      item.putArray("factRefs").add("/domains/collateralCoverageRatio");return out;},method,temp.resolve("r47"),Map.of())){
   var report=task.verify("owner",task.select("owner","德信","核验"),PostLoanRiskTask.Listener.NONE);
   assertEquals("PARTIAL",report.path("status").asText());
   assertEquals("REPORT_MODEL_RULE_EVIDENCE_REJECTED",report.path("failureCode").asText());
   assertEquals("COLLATERAL_RULE_EVIDENCE_REQUIRED",report.at("/failureDetail/rule").asText());
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
   assertEquals("PARTIAL",report.path("status").asText());assertTrue(report.has("facts"));assertEquals("REPORT_MODEL_FACT_REF_REJECTED",report.path("failureCode").asText());assertEquals("MODEL_RESPONSE_VALIDATION",report.path("failureStage").asText());
   }
  }
 @Test void latestCompleteStreamingSnapshotWinsOverEarlierDeltas()throws Exception{
  String complete="""
    {"summary":[{"text":"企业主体与快照来源一致。","factRefs":["/subjectRef"]}],
     "evidenceInterpretation":[{"text":"逾期35天。","factRefs":["/domains/overdueDays"]}],
     "pendingItems":["核实最新快照"],"reviewChecklist":["人工复核"]}
    """;
  var parsed=OpenJiuwenPostLoanReportGenerator.parseRenderedChunks(List.of("{\"summary\":",complete));
  assertEquals("企业主体与快照来源一致。",parsed.at("/summary/0/text").asText());
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
