package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.domain.LawArticle;
import java.util.List;
import org.junit.jupiter.api.Test;

class LawChunkBuilderTest {
    private LawArticle para(int no, int p, String text) {
        return LawArticle.builder().lawId("001766").lawName("산업안전보건법").articleNo(no).articleSub(0)
                .paragraphNo(p).title("위험성평가의 실시").text(text).build();
    }

    @Test
    void 항이_여러개면_parent와_child() {
        List<ChunkDraft> out = LawChunkBuilder.build(List.of(para(36, 1, "① 첫째"), para(36, 2, "② 둘째")));
        assertThat(out).hasSize(3);
        ChunkDraft parent = out.get(0);
        assertThat(parent.chunkLevel()).isEqualTo(ChunkPolicy.LEVEL_PARENT);
        assertThat(parent.refKey()).isEqualTo("001766:36:0#p");
        assertThat(parent.title()).isEqualTo("산업안전보건법 제36조(위험성평가의 실시)");
        assertThat(parent.text()).contains("① 첫째").contains("② 둘째");
        assertThat(parent.metadata()).doesNotContainKey("paragraphNo");
        assertThat(out.get(1).refKey()).isEqualTo("001766:36:0:1");
        assertThat(out.get(1).parentRefKey()).isEqualTo("001766:36:0#p");
        assertThat(out.get(1).title()).isEqualTo("산업안전보건법 제36조(위험성평가의 실시) ①");
        assertThat(out.get(1).metadata()).containsEntry("lawId", "001766").containsEntry("lawName", "산업안전보건법")
                .containsEntry("articleNo", 36).containsEntry("articleSub", 0).containsEntry("paragraphNo", 1);
    }

    @Test
    void 항번호가_21_이상이어도_parent_제목은_원문자_없이_조_단위다() {
        List<ChunkDraft> out = LawChunkBuilder.build(List.of(para(36, 21, "㉑ 스물한째"), para(36, 22, "㉒ 스물두째")));
        assertThat(out).hasSize(3);
        assertThat(out.get(0).title()).isEqualTo("산업안전보건법 제36조(위험성평가의 실시)");
    }

    @Test
    void 항이_하나면_parent_없음() {
        List<ChunkDraft> out = LawChunkBuilder.build(List.of(para(36, 0, "본문만")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).parentRefKey()).isNull();
        assertThat(out.get(0).refKey()).isEqualTo("001766:36:0:0");
    }
}
