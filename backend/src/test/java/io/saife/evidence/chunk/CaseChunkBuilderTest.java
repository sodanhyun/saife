package io.saife.evidence.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.EvidenceKind;
import io.saife.publicapi.domain.PublicCase;
import org.junit.jupiter.api.Test;

class CaseChunkBuilderTest {
    @Test
    void 사고사망은_CASE_FATALITY이고_사진_유무를_메타에_넣는다() {
        PublicCase c = PublicCase.builder().source("FATALITY").sourceKey("A1").keyword("[7/28, 경남 함양군] 스크류에 끼임")
                .contents("설비 내 슬러지 제거 작업 중 설비가 가동되어 끼임").accidentType(AccidentType.CAUGHT)
                .imageUrl("https://portal.kosha.or.kr/x.png").build();
        ChunkDraft d = CaseChunkBuilder.build(c);
        assertThat(d.kind()).isEqualTo(EvidenceKind.CASE_FATALITY);
        assertThat(d.refKey()).isEqualTo("FATALITY:A1");
        assertThat(d.title()).startsWith("[협착]");
        assertThat(d.text()).contains("스크류에 끼임").contains("슬러지");
        assertThat(d.metadata()).containsEntry("accidentType", "CAUGHT").containsEntry("hasImage", true);
        assertThat(d.parentRefKey()).isNull();
        assertThat(d.searchable()).isTrue();
    }

    @Test
    void 재해사례는_업종이_제목에_들어간다() {
        PublicCase c = PublicCase.builder().source("DISASTER").sourceKey("B1").business("제조업").keyword("지붕교체 중 떨어짐")
                .contents("본문").accidentType(AccidentType.FALL).build();
        ChunkDraft d = CaseChunkBuilder.build(c);
        assertThat(d.kind()).isEqualTo(EvidenceKind.CASE_DISASTER);
        assertThat(d.title()).isEqualTo("[추락] [제조업] 지붕교체 중 떨어짐");
        assertThat(d.metadata()).containsEntry("hasImage", false);
    }

    @Test
    void keyword_contents_accidentType가_모두_null이어도_예외없이_업종만_남는다() {
        PublicCase c = PublicCase.builder().source("DISASTER").sourceKey("C1").business("제조업").build();
        ChunkDraft d = CaseChunkBuilder.build(c);
        assertThat(d.title()).isEqualTo("[제조업]");
        assertThat(d.text()).isEmpty();
        assertThat(d.metadata()).containsEntry("accidentType", null).containsEntry("hasImage", false);
    }
}
