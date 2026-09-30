package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RerankScoreParserTest {
    @Test
    void 정수_배열() { assertThat(RerankScoreParser.parse("[8, 3, 9]", 3)).containsExactly(8, 3, 9); }

    @Test
    void 앞뒤_텍스트와_코드펜스를_무시() {
        assertThat(RerankScoreParser.parse("```json\n[1,2]\n```", 2)).containsExactly(1, 2);
    }

    @Test
    void 객체_배열은_첫_숫자_필드() {
        assertThat(RerankScoreParser.parse("[{\"score\":7},{\"relevance\":2}]", 2)).containsExactly(7, 2);
    }

    @Test
    void 길이_불일치는_null() {
        assertThat(RerankScoreParser.parse("[8, 3]", 3)).isNull();
        assertThat(RerankScoreParser.parse("[8, 3, 9, 1]", 3)).isNull();
    }

    @Test
    void 깨진_입력은_null() {
        assertThat(RerankScoreParser.parse("no json", 2)).isNull();
        assertThat(RerankScoreParser.parse(null, 2)).isNull();
    }

    // 최종 리뷰 F2: 범위 밖 값은 [0,10]으로 자르고, 숫자 문자열도 받는다
    @Test
    void 범위_밖_점수는_0에서_10으로_자른다() {
        assertThat(RerankScoreParser.parse("[12, -3, 10]", 3)).containsExactly(10, 0, 10);
        assertThat(RerankScoreParser.parse("[{\"score\":15}]", 1)).containsExactly(10);
    }

    @Test
    void 숫자_문자열과_소수를_받는다() {
        assertThat(RerankScoreParser.parse("[\"7\", \" 3 \", \"8.6\"]", 3)).containsExactly(7, 3, 9);
        assertThat(RerankScoreParser.parse("[{\"score\":\"6\"}]", 1)).containsExactly(6);
        assertThat(RerankScoreParser.parse("[\"높음\"]", 1)).isNull();
    }
}
