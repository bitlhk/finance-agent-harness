package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.time.Instant;
import java.util.*;

/** Bounded wording context, never facts, permission, or an executable plan. */
final class ConversationDialogue {
    record Binding(String context, String object, String report, String scope) {}
    private Binding binding;
    private Instant expires = Instant.MIN;
    private final Deque<Map<String,String>> turns = new ArrayDeque<>();
    private String object;
    private Set<String> disclosedObjects = Set.of();
    private boolean directoryReturned;
    private int returnedReports;

    synchronized List<Map<String,String>> read(Binding next, Instant now) {
        if (!Objects.equals(binding,next) || !now.isBefore(expires)) clear();
        binding=next;
        return List.copyOf(turns);
    }
    synchronized void append(Binding expected, String question, String answer, Instant now) {
        if (!Objects.equals(binding,expected)) return;
        expires=now.plusSeconds(300);
        // Text budget does not own the pending object or the record of completed work.
        if (question.length()>1000 || answer.length()>2000) return;
        turns.addLast(Map.of("userText",question,"assistantText",answer));
        while (turns.size()>3 || turns.stream().mapToInt(t->t.get("userText").length()+t.get("assistantText").length()).sum()>5000)
            turns.removeFirst();
    }
    synchronized String object() { return object; }
    synchronized Set<String> disclosedObjects() { return disclosedObjects; }
    synchronized void select(String value) {
        object=value;
        var all=new HashSet<>(disclosedObjects);all.add(value);disclosedObjects=Set.copyOf(all);
    }
    synchronized void listed(Set<String> values) {
        var all=new HashSet<>(disclosedObjects);all.addAll(values);disclosedObjects=Set.copyOf(all);
        directoryReturned=true;
    }
    synchronized void returnedReport() { returnedReports++; object=null; }
    synchronized void forgetObject() { object=null; }
    synchronized void cancelTask() {
        // Retain the record of prior work, not wording that can resurrect its cancelled target.
        object=null; turns.clear();
    }
    synchronized Map<String,Object> state() {
        var state=new LinkedHashMap<String,Object>();
        state.put("schema","conversation-continuity.v1");
        state.put("authority","NONE");
        state.put("directoryPreviouslyReturned",directoryReturned);
        state.put("returnedReports",returnedReports);
        state.put("selectedObject",object);
        return Collections.unmodifiableMap(state);
    }
    synchronized void clear() {
        turns.clear(); binding=null; expires=Instant.MIN;
        object=null; disclosedObjects=Set.of(); returnedReports=0;directoryReturned=false;
    }
}
