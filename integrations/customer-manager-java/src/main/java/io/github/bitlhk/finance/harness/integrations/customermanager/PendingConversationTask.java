package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.time.Instant;
import java.util.*;

/** Unexecuted user inputs; dispatch requires complete inputs and the installed action policy. */
record PendingConversationTask(ConversationConfirmation.Binding binding, ConversationIntent.Kind kind,
        String query, String object, Map<String,Object> parameters, Instant expiresAt) {
    PendingConversationTask {
        Objects.requireNonNull(binding);
        if(kind!=ConversationIntent.Kind.PREPARE && kind!=ConversationIntent.Kind.PRODUCT_REVIEW)
            throw new IllegalArgumentException("CONVERSATION_PENDING_ACTION_INVALID");
        parameters=Map.copyOf(parameters);
    }
    boolean valid(ConversationConfirmation.Binding current, Instant now) {
        return binding.equals(current) && now.isBefore(expiresAt);
    }
    static PendingConversationTask start(ConversationConfirmation.Binding binding, ConversationIntent.Kind kind,
            String query, String object, Instant now) {
        return new PendingConversationTask(binding,kind,query,object,
                kind==ConversationIntent.Kind.PRODUCT_REVIEW ? CustomerManagerScenarioExtension.productArguments(query) : Map.of(),
                now.plusSeconds(300));
    }
    PendingConversationTask supplement(String text, String selected) {
        String next=selected==null?object:selected;
        boolean changed=object!=null && !object.equals(next);
        var arguments=new LinkedHashMap<String,Object>(changed?Map.of():parameters);
        if(kind==ConversationIntent.Kind.PRODUCT_REVIEW)
            arguments.putAll(CustomerManagerScenarioExtension.productArguments(text));
        return new PendingConversationTask(binding,kind,changed?text:query+"\n本轮补充："+text,next,arguments,expiresAt);
    }
    List<String> missing() {
        var missing=new ArrayList<String>();
        if(object==null) missing.add("customerAlias");
        if(kind==ConversationIntent.Kind.PRODUCT_REVIEW)
            for(String key:List.of("amount","horizonMonths","channel"))
                if(!parameters.containsKey(key)) missing.add(key);
        return List.copyOf(missing);
    }
    Map<String,Object> projection() {
        var result=new LinkedHashMap<String,Object>();
        result.put("kind",kind.name());result.put("query",query);result.put("objectRef",object);
        result.put("parameters",parameters);result.put("missingInputs",missing());result.put("authority","NONE");
        return Collections.unmodifiableMap(result);
    }
}
