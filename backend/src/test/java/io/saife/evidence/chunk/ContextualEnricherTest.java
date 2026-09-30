package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.common.config.DemoModeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class ContextualEnricherTest {

    private ContextualEnricher enricherReturning(String answer, boolean demo) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(client);
        if (answer == null) {
            when(client.prompt().user(any(String.class)).options(any()).call().content()).thenThrow(new RuntimeException("down"));
        } else {
            when(client.prompt().user(any(String.class)).options(any()).call().content()).thenReturn(answer);
        }
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        when(cfg.isDemoMode()).thenReturn(demo);
        return new ContextualEnricher(builder, cfg, "<system-prompt>{fileName}{document}{chunk}</system-prompt>");
    }

    @Test
    void 정상_답은_프리픽스로_붙는다() {
        String out = enricherReturning("사다리 지침 — 작업 전 확인 절", false).enrich("전체 문서", "청크", 0, "G-1");
        assertThat(out).isEqualTo("[사다리 지침 — 작업 전 확인 절]\n청크");
    }

    @Test
    void 실패하면_원문() {
        assertThat(enricherReturning(null, false).enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
    }

    @Test
    void 대괄호가_있는_답은_버린다() {
        assertThat(enricherReturning("[이미 대괄호] 설명", false).enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
        assertThat(enricherReturning("   ", false).enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
    }

    @Test
    void 데모_모드는_호출하지_않는다() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        when(cfg.isDemoMode()).thenReturn(true);
        ContextualEnricher e = new ContextualEnricher(builder, cfg, "x");
        assertThat(e.enrich("전체", "청크", 0, "G-1")).isEqualTo("청크");
        verifyNoInteractions(builder);
    }

    @Test
    void 긴_문서는_앞_3000자와_주변_2500자만_보낸다() {
        String doc = "앞".repeat(3000) + "중".repeat(6000) + "뒤".repeat(3000);
        ContextualEnricher e = enricherReturning("맥락", false);
        String ctx = e.buildDocumentContext(doc, 7000);
        assertThat(ctx).startsWith("앞".repeat(3000) + "\n...\n");
        assertThat(ctx.length()).isLessThanOrEqualTo(3000 + 5 + 5000);
        assertThat(e.buildDocumentContext("짧은 문서", 0)).isEqualTo("짧은 문서");
    }
}
