package io.github.bitlhk.finance.harness.providers.postloan;
import io.github.bitlhk.finance.harness.core.*;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class PostLoanRiskReferenceProviderTest{
 @Test void returnsSnapshotAndDeterministicWarning(){var p=new PostLoanRiskReferenceProvider();var ctx=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"r","t","employee",FinanceContextV1.PrincipalType.EMPLOYEE,"postloan","READ_ONLY_INVESTIGATION","91440100MA5C00000B","s",FinanceContextV1.AuthenticationLevel.STRONG,Set.of("postloan-risk:read"));
  var snapshot=p.query(new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,PostLoanRiskReferenceProvider.SNAPSHOT,CapabilityRequestV1.Operation.QUERY,ctx,Map.of("subjectType",PostLoanRiskReferenceProvider.SUBJECT_TYPE,"subjectRef","91440100MA5C00000B"),null,null,Map.of(),null));
  assertEquals(CapabilityResultV1.Status.COMPLETED,snapshot.status());assertEquals(true,snapshot.data().get("queryComplete"));
  var decision=p.query(new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,PostLoanRiskReferenceProvider.ESCALATION,CapabilityRequestV1.Operation.QUERY,ctx,Map.of("subjectType",PostLoanRiskReferenceProvider.SUBJECT_TYPE,"subjectRef","91440100MA5C00000B"),null,null,Map.of(),null));
  assertEquals("ORANGE",decision.data().get("level"));assertEquals(false,decision.data().get("formalConclusionAllowed"));
 }
 @Test void rejectsSubjectMismatch(){var p=new PostLoanRiskReferenceProvider();var ctx=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"r","t","e",FinanceContextV1.PrincipalType.EMPLOYEE,"p","READ_ONLY_INVESTIGATION","91370000726200000X","s",FinanceContextV1.AuthenticationLevel.STRONG,Set.of("postloan-risk:read"));var result=p.query(new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,PostLoanRiskReferenceProvider.SNAPSHOT,CapabilityRequestV1.Operation.QUERY,ctx,Map.of("subjectType",PostLoanRiskReferenceProvider.SUBJECT_TYPE,"subjectRef","91440100MA5C00000B"),null,null,Map.of(),null));assertEquals(CapabilityResultV1.Status.FAILED,result.status());}
}
