package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic, versioned user-statement overlay. It never upgrades statements to system-verified facts. */
final class CustomerManagerTaskContext {
    private static final Pattern AGE=Pattern.compile("(\\d{1,3})\\s*岁");
    private static final Pattern MONEY=Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|元)");
    private static final Pattern MONTHS=Pattern.compile("(\\d+)\\s*(年|个月|月)");
    private static final Pattern RISK=Pattern.compile("(?:口述|测评)?\\s*(C[1-5]|R[1-5])",Pattern.CASE_INSENSITIVE);

    static ObjectNode empty() {
        ObjectNode value=HarnessJson.mapper().createObjectNode();
        value.put("schemaVersion","customer-manager-task-context.v0.1");
        value.put("taskId",UUID.randomUUID().toString());value.put("revision",0);
        value.putArray("facts");value.putArray("questions");return value;
    }

    static ObjectNode update(JsonNode previous,String query) {
        ObjectNode value=previous!=null&&previous.isObject()?(ObjectNode)previous.deepCopy():empty();
        value.put("revision",value.path("revision").asInt()+1);value.put("updatedAt",Instant.now().toString());
        Map<String,ObjectNode> facts=new LinkedHashMap<>();
        value.withArray("facts").forEach(item->facts.put(item.path("key").asText(),(ObjectNode)item.deepCopy()));
        var age=AGE.matcher(query);if(age.find())put(facts,"customer.age",Integer.parseInt(age.group(1)),"岁");
        var amounts=new ArrayList<Double>();var money=MONEY.matcher(query);
        while(money.find())amounts.add(Double.parseDouble(money.group(1))*("万".equals(money.group(2))?10000:1));
        boolean correction=query.contains("改成")||query.contains("更正为")||query.contains("不是")&&query.contains("是");
        if(correction&&!amounts.isEmpty())put(facts,"visit.liquidityAmount",amounts.getLast(),"元");
        else {
            if(!amounts.isEmpty())put(facts,"visit.discussionAmount",amounts.getFirst(),"元");
            if(amounts.size()>1)put(facts,"visit.liquidityAmount",amounts.get(1),"元");
        }
        var months=MONTHS.matcher(query);if(months.find())put(facts,"visit.liquidityHorizonMonths",
                Integer.parseInt(months.group(1))*("年".equals(months.group(2))?12:1),"个月");
        var risk=RISK.matcher(query);if(risk.find())put(facts,"riskAssessment.level",risk.group(1).toUpperCase(Locale.ROOT),"");
        if(query.contains("偏稳健")||query.contains("稳健型"))put(facts,"riskPreference","偏稳健","");
        if(query.contains("持仓未知")||query.contains("不知道持仓"))unknown(facts,"portfolio.holdings");
        if(query.contains("风险")&&(query.contains("未知")||query.contains("不知道")))unknown(facts,"riskAssessment.level");
        if(query.contains("有效期未给")||query.contains("有效期未知")||query.contains("不知道有效期"))unknown(facts,"riskAssessment.validUntil");
        ObjectNode total=facts.get("visit.discussionAmount"),liquid=facts.get("visit.liquidityAmount");
        if(total!=null&&liquid!=null&&total.path("value").isNumber()&&liquid.path("value").isNumber()) {
            double remainder=total.path("value").asDouble()-liquid.path("value").asDouble();
            if(remainder>=0)putDerived(facts,"visit.remainingDiscussionAmount",remainder,"元",
                    List.of("visit.discussionAmount","visit.liquidityAmount"));
        }
        var array=value.putArray("facts");facts.values().forEach(array::add);
        var questions=value.putArray("questions");
        question(questions,facts,"riskAssessment.level","客户当前风险测评结果是什么，是否仍在有效期内？");
        question(questions,facts,"riskAssessment.validUntil","风险测评的有效期截至哪一天？");
        question(questions,facts,"portfolio.holdings","客户当前持仓和集中度情况如何？");
        question(questions,facts,"visit.liquidityAmount","本次可能使用的资金金额、用途和具体时点是什么？");
        question(questions,facts,"visit.liquidityHorizonMonths","剩余讨论资金可接受的期限是多少？");
        return value;
    }

    private static void put(Map<String,ObjectNode> facts,String key,Object value,String unit) {
        ObjectNode item=base(key);item.set("value",HarnessJson.mapper().valueToTree(value));if(!unit.isBlank())item.put("unit",unit);
        item.putObject("source").put("kind","USER_STATEMENT").put("ref","CURRENT_CONVERSATION").put("recordedAt",Instant.now().toString());
        item.putObject("verification").put("status","UNVERIFIED");item.putObject("completeness").put("status","PRESENT");
        item.putObject("validity").put("status","UNKNOWN");facts.put(key,item);
    }
    private static void unknown(Map<String,ObjectNode> facts,String key) {
        ObjectNode item=base(key);item.putNull("value");
        item.putObject("source").put("kind","USER_STATEMENT").put("ref","CURRENT_CONVERSATION").put("recordedAt",Instant.now().toString());
        item.putObject("verification").put("status","UNVERIFIED");item.putObject("completeness").put("status","UNKNOWN");
        item.putObject("validity").put("status","UNKNOWN");facts.put(key,item);
    }
    private static void putDerived(Map<String,ObjectNode> facts,String key,Object value,String unit,List<String> refs) {
        ObjectNode item=base(key);item.set("value",HarnessJson.mapper().valueToTree(value));item.put("unit",unit);
        ObjectNode source=item.putObject("source");source.put("kind","DETERMINISTIC_DERIVATION");source.set("refs",HarnessJson.mapper().valueToTree(refs));
        item.putObject("verification").put("status","DERIVED_FROM_UNVERIFIED_INPUT");item.putObject("completeness").put("status","PRESENT");
        item.putObject("validity").put("status","UNKNOWN");facts.put(key,item);
    }
    private static ObjectNode base(String key){ObjectNode value=HarnessJson.mapper().createObjectNode();value.put("key",key);return value;}
    private static void question(com.fasterxml.jackson.databind.node.ArrayNode out,Map<String,ObjectNode> facts,String key,String text) {
        ObjectNode fact=facts.get(key);if(out.size()<3&&(fact==null||"UNKNOWN".equals(fact.path("completeness").path("status").asText())))out.add(text);
    }
    private CustomerManagerTaskContext() {}
}
