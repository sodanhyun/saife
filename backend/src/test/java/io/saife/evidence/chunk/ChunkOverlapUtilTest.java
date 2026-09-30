package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkOverlapUtilTest {

    @Test
    void 이전_청크_꼬리를_문장_경계에서_잘라_앞에_붙인다() {
        String prev = "가".repeat(300) + ". 마지막 문장입니다. 진짜 마지막이다";
        List<String> out = ChunkOverlapUtil.addOverlap(List.of(prev, "다음 청크"));
        assertThat(out.get(0)).isEqualTo(prev);
        assertThat(out.get(1)).startsWith("마지막 문장입니다. 진짜 마지막이다\n다음 청크");
    }

    @Test
    void 문장_경계가_없으면_꼬리_전체() {
        String prev = "가".repeat(500);
        List<String> out = ChunkOverlapUtil.addOverlap(List.of(prev, "다음"));
        assertThat(out.get(1)).isEqualTo("가".repeat(ChunkPolicy.OVERLAP_CHARS) + "\n다음");
    }

    @Test
    void 짧은_이전_청크는_오버랩_없음() {
        List<String> out = ChunkOverlapUtil.addOverlap(List.of("짧다", "다음"));
        assertThat(out.get(1)).isEqualTo("다음");
    }

    @Test
    void 청크가_하나면_그대로() {
        assertThat(ChunkOverlapUtil.addOverlap(List.of("하나"))).containsExactly("하나");
    }

    @Test
    void 경계_문자가_꼬리_마지막_위치면_오버랩_없음() {
        // prev 길이 301 > 200 → 꼬리(200자) = "가"*199 + "다". 유일한 경계 문자가 꼬리의
        // 마지막 위치라 경계 뒤에 남는 글자가 없다 → 오버랩 없음
        List<String> out = ChunkOverlapUtil.addOverlap(List.of("가".repeat(300) + "다", "다음"));
        assertThat(out.get(1)).isEqualTo("다음");
    }
}
