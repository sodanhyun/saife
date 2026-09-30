package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ContextualPrefixTest {
    @Test
    void 붙이고_벗기면_원문() {
        String withPrefix = ContextualPrefix.of("사다리 지침 — 작업 전 확인", "원문 본문");
        assertThat(withPrefix).isEqualTo("[사다리 지침 — 작업 전 확인]\n원문 본문");
        assertThat(ContextualPrefix.strip(withPrefix)).isEqualTo("원문 본문");
    }

    @Test
    void 프리픽스가_없거나_200자를_넘으면_그대로() {
        assertThat(ContextualPrefix.strip("원문")).isEqualTo("원문");
        String longPrefix = "[" + "가".repeat(250) + "]\n원문";
        assertThat(ContextualPrefix.strip(longPrefix)).isEqualTo(longPrefix);
        assertThat(ContextualPrefix.strip(null)).isEmpty();
    }
}
