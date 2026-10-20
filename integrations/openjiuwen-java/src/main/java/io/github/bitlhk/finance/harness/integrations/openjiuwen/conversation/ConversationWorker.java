package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;

/** One-shot child of the authenticated workbench; no Host, tools, archive discovery or business access. */
public final class ConversationWorker {
    private ConversationWorker() {}
    public static void main(String[] args) {
        int exit=0;
        try {
            if(args.length!=1)throw new IllegalArgumentException();
            byte[] input=System.in.readNBytes(200_001);
            if(input.length>200_000)throw new IllegalArgumentException();
            JsonNode request;
            try(var parser=HarnessJson.mapper().getFactory().createParser(input)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                request=HarnessJson.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
            }
            var fields=new HashSet<String>();request.fieldNames().forEachRemaining(fields::add);
            if(!request.isObject()||!(fields.equals(Set.of("schema","question","projection"))
                    ||fields.equals(Set.of("schema","question","projection","operation")))
                    ||!"finance-conversation-worker.v1".equals(request.path("schema").textValue())
                    ||!request.path("question").isTextual()||!request.path("projection").isObject())
                throw new IllegalArgumentException();
            var connection=new ManagedScenarioExtensionV01.ModelConnection(required("FH_CONVERSATION_MODEL_PROVIDER"),
                    required("FH_MODEL_KEY"),required("FH_MODEL_URL"),required("FH_MODEL_NAME"));
            String operation=request.has("operation")?request.path("operation").asText():"EXPLAIN";
            JsonNode answer=switch(operation) {
                case "EXPLAIN" -> new SavedReportExplainer(connection).explain(request.path("question").asText(),request.path("projection"),()->false);
                case "PROPOSE" -> new TaskIntentProposer(connection).propose(request.path("question").asText(),request.path("projection"));
                case "RESPOND" -> {
                    var context=responseContext(request.path("projection"));
                    Path stream=Path.of(args[0]+".stream");
                    try(var writer=Files.newBufferedWriter(stream,StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                        int[] sequence={0};
                        String text=new ConversationResponder(connection).respond(request.path("question").asText(),
                                context,()->false,()->{},snapshot->{
                                    // Private IPC only. Parent must authorize/audit before client release.
                                    writer.write(HarnessJson.mapper().writeValueAsString(Map.of(
                                            "schema","finance-conversation-worker-progress.v1",
                                            "sequence",++sequence[0],"text",snapshot)));
                                    writer.newLine();writer.flush();
                                });
                        yield HarnessJson.mapper().createObjectNode()
                                .put("schema","finance-conversation-response.v1").put("answer",text);
                    }
                }
                default -> throw new IllegalArgumentException();
            };
            Path output=Path.of(args[0]);
            Files.write(output,HarnessJson.mapper().writeValueAsString(answer).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        }catch(Exception failure) {
            System.err.println("CONVERSATION_WORKER_FAILED");
            exit=2;
        }finally {
            com.openjiuwen.core.runner.Runner.stop();
        }
        if(exit!=0)System.exit(exit);
    }
    static ConversationResponder.Context responseContext(JsonNode value) {
        var names=new HashSet<String>();value.fieldNames().forEachRemaining(names::add);
        if(!value.isObject()||!names.equals(Set.of("purpose","capabilityDescription","missingInputs","decisionDescription"))
                ||!value.path("purpose").isTextual()||!value.path("capabilityDescription").isTextual()
                ||!value.path("decisionDescription").isTextual()||!value.path("missingInputs").isArray())
            throw new IllegalArgumentException("CONVERSATION_RESPONSE_INPUT_INVALID");
        var missing=new ArrayList<String>();
        for(JsonNode field:value.path("missingInputs")) {
            if(!field.isTextual())throw new IllegalArgumentException("CONVERSATION_RESPONSE_INPUT_INVALID");
            missing.add(field.asText());
        }
        return new ConversationResponder.Context(ConversationResponder.Purpose.valueOf(value.path("purpose").asText()),
                value.path("capabilityDescription").asText(),missing,value.path("decisionDescription").asText());
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalArgumentException();
        return value;
    }
}
