package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import com.openjiuwen.core.session.stream.OutputSchema;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationResponderTest {
    private static OutputSchema text(Object value) { return new OutputSchema("llm_output", 0, value); }

    @Test void streamsSnapshotsBeforeTheIteratorFinishes() throws Exception {
        var seen = new ArrayList<String>();
        var checks = new AtomicInteger();
        var items = List.of(text("你好"), text("，我可以说明能力。"));
        var iterator = new Iterator<OutputSchema>() {
            int index;
            public boolean hasNext() {
                if (index == 1) assertEquals(List.of("你好"), seen);
                return index < items.size();
            }
            public OutputSchema next() { return items.get(index++); }
        };
        String answer = ConversationResponder.consume(iterator, () -> false, checks::incrementAndGet, seen::add);
        assertEquals("你好，我可以说明能力。", answer);
        assertEquals(List.of("你好", answer), seen);
        assertEquals(3, checks.get(), "Two releases plus final check");
    }

    @Test void identicalTextChunksAreNotMistakenForDuplicateEvents() throws Exception {
        var seen = new ArrayList<String>();
        String answer = ConversationResponder.consume(List.of(text("你好"), text("你好"), text("。"),
                text(Map.of("content", "请说说你的目标。"))).iterator(),
                () -> false, () -> {}, seen::add);
        assertEquals(List.of("你好", "你好你好", "你好你好。", answer), seen);
        assertEquals("你好你好。请说说你的目标。", answer);
    }

    @Test void revokedReleaseWithholdsNextTextAndDoesNotClaimNoPriorRelease() {
        var seen = new ArrayList<String>();
        var checks = new AtomicInteger();
        assertThrows(SecurityException.class, () -> ConversationResponder.consume(
                List.of(text("已显示的普通回复"), text("不应释放的后续")).iterator(), () -> false,
                () -> { if (checks.incrementAndGet() == 2) throw new SecurityException("BINDING_CHANGED"); },
                seen::add));
        assertEquals(List.of("已显示的普通回复"), seen);
    }

    @Test void cancellationFromReleaseCheckBlocksSink() {
        var cancelled = new AtomicBoolean();
        var seen = new ArrayList<String>();
        assertThrows(CancellationException.class, () -> ConversationResponder.consume(
                List.of(text("不应显示")).iterator(), cancelled::get, () -> cancelled.set(true), seen::add));
        assertTrue(seen.isEmpty());
    }

    @Test void evidenceFailureBlocksRelease() {
        var seen = new ArrayList<String>();
        assertThrows(java.io.IOException.class, () -> ConversationResponder.consume(
                List.of(text("不应显示")).iterator(), () -> false,
                () -> { throw new java.io.IOException("EVIDENCE_UNAVAILABLE"); }, seen::add));
        assertTrue(seen.isEmpty());
    }

    @Test void diagnosticEnvelopeIsNotStringifiedIntoModelText() {
        var seen = new ArrayList<String>();
        var error = assertThrows(IllegalArgumentException.class, () -> ConversationResponder.consume(
                List.of(text(Map.of("diagnostic", "synthetic-secret-marker"))).iterator(),
                () -> false, () -> {}, seen::add));
        assertEquals("CONVERSATION_MODEL_ENVELOPE_INVALID", error.getMessage());
        assertTrue(seen.isEmpty());
    }

    @Test void sdkErrorAfterTextCannotBecomeSuccessfulAnswerOrExposeDiagnostics() {
        var seen = new ArrayList<String>();
        var error = assertThrows(IllegalArgumentException.class, () -> ConversationResponder.consume(
                List.of(text("已显示普通文字"), new OutputSchema("error", 1,
                        Map.of("message", "synthetic-secret-marker"))).iterator(),
                () -> false, () -> {}, seen::add));
        assertEquals("CONVERSATION_MODEL_FAILED", error.getMessage());
        assertEquals(List.of("已显示普通文字"), seen);
    }

    @Test void emptyAndOversizedOutputFailWithoutRelease() {
        var seen = new ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> ConversationResponder.consume(
                List.of(text("")).iterator(), () -> false, () -> {}, seen::add));
        assertThrows(IllegalArgumentException.class, () -> ConversationResponder.consume(
                List.of(text("a".repeat(8001))).iterator(), () -> false, () -> {}, seen::add));
        assertTrue(seen.isEmpty());
    }

    @Test void finalAuthorityChangeDoesNotBecomeSuccessfulCompletion() {
        var checks = new AtomicInteger();
        var seen = new ArrayList<String>();
        assertThrows(SecurityException.class, () -> ConversationResponder.consume(
                List.of(text("仅普通对话")).iterator(), () -> false,
                () -> { if (checks.incrementAndGet() == 2) throw new SecurityException("BINDING_CHANGED"); },
                seen::add));
        assertEquals(List.of("仅普通对话"), seen);
    }

    @Test void contextIsBoundedAndCopied() {
        var inputs = new ArrayList<>(List.of("customerAlias"));
        var context = new ConversationResponder.Context(ConversationResponder.Purpose.CLARIFY,
                "回访准备", inputs, "尚未执行，缺少对象");
        inputs.clear();
        assertEquals(List.of("customerAlias"), context.missingInputs());
        assertThrows(UnsupportedOperationException.class, () -> context.missingInputs().add("period"));
        assertThrows(IllegalArgumentException.class, () -> new ConversationResponder.Context(
                ConversationResponder.Purpose.GREETING, " ", List.of(), "尚未执行"));
        assertThrows(IllegalArgumentException.class, () -> new ConversationResponder.Context(
                ConversationResponder.Purpose.GREETING, "回访", List.of("not a field"), "尚未执行"));
    }
}
