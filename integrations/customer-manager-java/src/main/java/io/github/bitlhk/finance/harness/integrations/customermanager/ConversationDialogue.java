package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.time.Instant;
import java.util.*;

/** Bounded wording context, never facts, permission, or an executable plan. */
final class ConversationDialogue {
    record Binding(String context, String object, String report, String scope) {}
    private Binding binding;
    private Instant expires = Instant.MIN;
    private final Deque<Map<String,String>> turns = new ArrayDeque<>();

    synchronized List<Map<String,String>> read(Binding next, Instant now) {
        if (!Objects.equals(binding,next) || !now.isBefore(expires)) clear();
        binding=next;
        return List.copyOf(turns);
    }
    synchronized void append(Binding expected, String question, String answer, Instant now) {
        if (!Objects.equals(binding,expected)) return;
        if (question.length()>1000 || answer.length()>2000) { clear(); return; }
        turns.addLast(Map.of("userText",question,"assistantText",answer));
        while (turns.size()>3 || turns.stream().mapToInt(t->t.get("userText").length()+t.get("assistantText").length()).sum()>5000)
            turns.removeFirst();
        expires=now.plusSeconds(300);
    }
    synchronized void clear() { turns.clear(); binding=null; expires=Instant.MIN; }
}
