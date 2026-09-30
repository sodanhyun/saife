package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TsQueryBuilderTest {
    @Test
    void 토큰을_OR로_묶는다() {
        assertThat(TsQueryBuilder.build("사다리 천장 페인트")).isEqualTo("'사다리' | '천장' | '페인트'");
    }

    @Test
    void 조사를_벗긴_변형을_함께() {
        String q = TsQueryBuilder.build("사다리에서 작업을");
        assertThat(q).contains("'사다리에서'").contains("'사다리'").contains("'작업을'").contains("'작업'");
    }

    @Test
    void 한_글자_토큰과_구두점은_버리고_8개까지만() {
        String q = TsQueryBuilder.build("a, 가 나다 라마 바사 아자 차카 타파 하가 거너 더러");
        assertThat(q).doesNotContain("'a'").doesNotContain("'가'");
        assertThat(TsQueryBuilder.tokens("나다 라마 바사 아자 차카 타파 하가 거너 더러")).hasSize(SearchPolicy.KEYWORD_MAX_TOKENS);
    }

    @Test
    void 비어_있으면_빈_문자열() {
        assertThat(TsQueryBuilder.build("")).isEmpty();
        assertThat(TsQueryBuilder.build(null)).isEmpty();
        assertThat(TsQueryBuilder.build("' | &")).isEmpty();
    }

    @Test
    void 가운뎃점으로_이어붙은_단어를_분리한다() {
        assertThat(TsQueryBuilder.tokens("안전대·안전모 착용")).containsExactly("안전대", "안전모", "착용");
        assertThat(TsQueryBuilder.build("안전대·안전모 착용")).isEqualTo("'안전대' | '안전모' | '착용'");
    }

    @Test
    void 전각_괄호로_감싼_단어를_분리한다() {
        assertThat(TsQueryBuilder.tokens("（협착）")).containsExactly("협착");
        assertThat(TsQueryBuilder.build("（협착）")).isEqualTo("'협착'");
    }
}
