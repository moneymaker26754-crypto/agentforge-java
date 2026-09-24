package io.github.moneymaker26754.agentforge.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextManagerTest {

    @Test
    void compactsOldMessagesWithoutMutatingRawHistory() {
        var raw = new ArrayList<ChatMessage>();
        raw.add(ChatMessage.system("system"));
        raw.add(ChatMessage.user("goal"));
        for (int index = 0; index < 12; index++) {
            raw.add(ChatMessage.assistant("old-message-" + index + "-" + "x".repeat(30)));
        }
        var manager = new ContextManager(text -> Math.max(1, text.length() / 4), 100, 0.75, 4);

        ContextPreparation prepared = manager.prepare(raw);

        assertThat(prepared.compressed()).isTrue();
        assertThat(prepared.messages()).first().isEqualTo(ChatMessage.system("system"));
        assertThat(prepared.messages()).anyMatch(message -> message.role() == ChatRole.SYSTEM
                && message.content().startsWith("[context-summary]"));
        assertThat(prepared.messages()).contains(ChatMessage.user("goal"));
        assertThat(prepared.messages()).contains(raw.get(raw.size() - 1));
        assertThat(raw).hasSize(14);
        assertThat(prepared.tokensAfter()).isLessThan(prepared.tokensBefore());
    }

    @Test
    void preservesHistoryBelowCompressionThreshold() {
        var raw = List.of(ChatMessage.system("s"), ChatMessage.user("small"));
        var manager = new ContextManager(text -> 1, 100, 0.75, 4);

        ContextPreparation prepared = manager.prepare(raw);

        assertThat(prepared.compressed()).isFalse();
        assertThat(prepared.messages()).containsExactlyElementsOf(raw);
    }
}

