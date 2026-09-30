package io.saife.evidence.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationSanitizerTest {
    @Test
    void 없는_번호는_제거_있는_번호는_유지() {
        assertThat(CitationSanitizer.sanitize("사례가 있습니다 [#1]. 지침 [#7]도 참고.", Set.of(1)))
                .isEqualTo("사례가 있습니다 [#1]. 지침도 참고.");
    }

    @Test
    void 붙어있는_번호() {
        assertThat(CitationSanitizer.sanitize("근거 [#3][#7][#2].", Set.of(2, 3))).isEqualTo("근거 [#3][#2].");
    }

    @Test
    void 공백_변형도_정규화() {
        assertThat(CitationSanitizer.sanitize("A [ # 4 ] B", Set.of(4))).isEqualTo("A [#4] B");
        assertThat(CitationSanitizer.citedNumbers("x [#2] y [#9] [#2]")).containsExactly(2, 9, 2);
    }

    @Test
    void null과_빈문자열() {
        assertThat(CitationSanitizer.sanitize(null, Set.of())).isEmpty();
        assertThat(CitationSanitizer.sanitize("", Set.of(1))).isEmpty();
    }

    @Test
    void known이_비면_모든_인용을_지운다() {
        assertThat(CitationSanitizer.sanitize("위험 [#1]과 [#2][#3] 끝", Set.of()))
                .isEqualTo("위험과 끝");
    }

    // 최종 리뷰 F15: 공백 정리는 지운 인용 자리에서만 — 들여쓴 하위 목록은 그대로
    @Test
    void 들여쓴_하위_목록은_평평해지지_않는다() {
        String in = "대책:\n- 안전대 착용 [#9]\n  - 부착설비  확인 [#1]\n    - 세부 항목";
        assertThat(CitationSanitizer.sanitize(in, Set.of(1)))
                .isEqualTo("대책:\n- 안전대 착용\n  - 부착설비  확인 [#1]\n    - 세부 항목");
    }

    @Test
    void 지운_자리의_겹친_공백과_문장부호_앞_공백만_정리한다() {
        assertThat(CitationSanitizer.sanitize("사례  [#9] 참고", Set.of())).isEqualTo("사례 참고");
        assertThat(CitationSanitizer.sanitize("사례 [#9] .", Set.of())).isEqualTo("사례 .");
        assertThat(CitationSanitizer.sanitize("사례 [#9], 지침", Set.of())).isEqualTo("사례, 지침");
    }
}
