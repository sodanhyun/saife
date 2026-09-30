package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuideChunkBuilderTest {

    private static final String NO = "G-1-2024";

    @Test
    void 표지_청크는_parent_없이_1개() {
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "사다리 안전작업 지침", LocalDate.of(2024, 1, 1),
                List.of("1. 목적\n이 지침은 사다리 작업의 안전을 위한 것이다.", "2. 적용범위\n모든 사업장"));
        ChunkDraft cover = out.get(0);
        assertThat(cover.refKey()).isEqualTo(NO + "#0");
        assertThat(cover.parentRefKey()).isNull();
        assertThat(cover.text()).startsWith("사다리 안전작업 지침");
        assertThat(cover.text().length()).isLessThanOrEqualTo(ChunkPolicy.COVER_MAX + 40);
    }

    // R4: 400자 3문단(=1,200자 미만)은 CHILD_TARGET(1,200) 미만 단일 child가 되어 parent가 생기지 않는다.
    // 500자 3문단 섹션으로 교정해 parent 2개가 실제로 생기게 한다(생산 로직은 그대로).
    @Test
    void 제목줄로_섹션을_나누고_parent는_searchable_false() {
        String p = "가".repeat(500) + ".";
        String body = "제1장 총칙\n" + p + "\n\n" + p + "\n\n" + p + "\n제2장 작업 전 확인\n" + p + "\n\n" + p + "\n\n" + p;
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of(body));
        List<ChunkDraft> parents = out.stream().filter(c -> ChunkPolicy.LEVEL_PARENT.equals(c.chunkLevel())).toList();
        List<ChunkDraft> children = out.stream().filter(c -> ChunkPolicy.LEVEL_CHILD.equals(c.chunkLevel()) && c.parentRefKey() != null).toList();
        assertThat(parents).hasSize(2);
        assertThat(parents).allMatch(pd -> !pd.searchable());
        assertThat(parents.get(1).sectionTitle()).isEqualTo("제2장 작업 전 확인");
        assertThat(children).allMatch(c -> c.searchable());
        assertThat(children).allMatch(c -> c.parentRefKey().startsWith(NO + "#p"));
    }

    @Test
    void 단일_덩어리_텍스트() {
        String blob = "라".repeat(ChunkPolicy.CHILD_MAX + ChunkPolicy.CHILD_MIN - 10);
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of(blob));
        List<ChunkDraft> children = out.stream().filter(c -> ChunkPolicy.LEVEL_CHILD.equals(c.chunkLevel()) && c.parentRefKey() != null).toList();
        // 3290자 → 1200/1200/890 (마지막이 300 이상이라 유지) = 3
        assertThat(children).hasSize(3);
        assertThat(children.get(2).text().length()).isGreaterThanOrEqualTo(ChunkPolicy.CHILD_MIN);
        // 오버랩: 두 번째 child는 첫 child 꼬리로 시작
        assertThat(children.get(1).text()).startsWith("라".repeat(ChunkPolicy.OVERLAP_CHARS));
    }

    // R4: 위와 같은 이유로 500자 3문단 섹션으로 교정. "제1장 총칙"은 본문 없이 바로 다음 제목이 와서 빈 섹션으로 버려진다.
    @Test
    void 빈_섹션은_parent를_만들지_않는다() {
        String p = "가".repeat(500) + ".";
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null,
                List.of("제1장 총칙\n\n제2장 정의\n" + p + "\n\n" + p + "\n\n" + p));
        List<ChunkDraft> parents = out.stream().filter(c -> ChunkPolicy.LEVEL_PARENT.equals(c.chunkLevel())).toList();
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0).sectionTitle()).isEqualTo("제2장 정의");
    }

    @Test
    void child가_하나인_섹션은_parent_없이_child만() {
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of("제1장 총칙\n" + "바".repeat(500)));
        assertThat(out.stream().filter(c -> ChunkPolicy.LEVEL_PARENT.equals(c.chunkLevel()))).isEmpty();
        ChunkDraft only = out.stream().filter(c -> c.refKey().startsWith(NO + "#c")).findFirst().orElseThrow();
        assertThat(only.parentRefKey()).isNull();
        assertThat(only.sectionTitle()).isEqualTo("제1장 총칙");
    }

    @Test
    void 품질_낮은_페이지는_버린다() {
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of("�".repeat(300), "정상 본문 " + "사".repeat(400)));
        assertThat(out.stream().noneMatch(c -> c.text().contains("�"))).isTrue();
    }

    // 리뷰 fix 1: 번호 매긴 본문 문장("3. 작업자는 …해야 한다")이 숫자 접두 제목 정규식에 걸려
    // 제목으로 오탐되던 문제. 제목/로마숫자 제목은 그대로 두고, 숫자 접두 제목만 40자 이하·
    // 다/요/음으로 끝나지 않음·토큰 6개 이하로 더 엄격하게 본다.
    @Test
    void 번호_매긴_본문_문장은_제목이_아니다() {
        assertThat(GuideChunkBuilder.isHeading("3. 작업자는 안전모를 착용하고 안전대를 걸어야 한다")).isFalse();
        assertThat(GuideChunkBuilder.isHeading("3. 작업 전 확인")).isTrue();
        assertThat(GuideChunkBuilder.isHeading("제3장 안전조치")).isTrue();
        assertThat(GuideChunkBuilder.isHeading("1.2 적용범위")).isTrue();
    }

    // 리뷰 fix 2: 제목이 하나도 없는 문서에서 문단을 5개씩 묶을 때 page가 항상 1로 고정되던 문제.
    // 페이지별로 문단을 태그한 뒤 그룹의 page를 그룹 내 첫 문단의 page로 정한다.
    @Test
    void 제목_없는_문서는_문단_페이지를_유지한다() {
        String para = "마".repeat(300);
        String page1 = String.join("\n\n", List.of(para, para, para, para, para));
        String page2 = String.join("\n\n", List.of(para, para, para, para, para));
        List<ChunkDraft> out = GuideChunkBuilder.build(1L, NO, "지침", null, List.of(page1, page2));
        List<ChunkDraft> body = out.stream().filter(c -> !(NO + "#0").equals(c.refKey())).toList();
        assertThat(body).isNotEmpty();
        assertThat(body.get(0).metadata().get("page")).isEqualTo(1);
        assertThat(body.get(body.size() - 1).metadata().get("page")).isEqualTo(2);
    }
}
