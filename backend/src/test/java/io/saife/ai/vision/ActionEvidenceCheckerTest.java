package io.saife.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 증빙 사진 대조 결과 파싱과 종합 판정 */
class ActionEvidenceCheckerTest {

    @Test
    void 모든_항목이_보이면_확인됨() {
        ActionEvidenceChecker.Result r = ActionEvidenceChecker.parse("""
                {"items":[{"item":"작업발판","status":"SEEN","evidence":"상단에 발판이 깔려 있음"},
                          {"item":"안전난간","status":"seen","evidence":"난간대 설치됨"}]}""");
        assertThat(r.verdict()).isEqualTo(ActionEvidenceChecker.Verdict.CONFIRMED);
        assertThat(r.items()).hasSize(2);
    }

    @Test
    void 하나라도_안_보이면_미확인_판단불가가_섞이면_일부() {
        assertThat(ActionEvidenceChecker.parse("""
                {"items":[{"item":"안전난간","status":"NOT_SEEN","evidence":"난간이 없음"},
                          {"item":"작업발판","status":"SEEN","evidence":"있음"}]}""").verdict())
                .isEqualTo(ActionEvidenceChecker.Verdict.NOT_CONFIRMED);
        assertThat(ActionEvidenceChecker.parse("""
                ```json
                {"items":[{"item":"교육 실시","status":"UNCLEAR","evidence":"사진으로 확정 불가"},
                          {"item":"작업발판","status":"SEEN","evidence":"있음"}]}
                ```""").verdict())
                .isEqualTo(ActionEvidenceChecker.Verdict.PARTIAL);
    }

    @Test
    void 알_수_없는_상태는_판단불가_구분자는_쉼표로_깨진_응답은_빈_결과() {
        ActionEvidenceChecker.Result r = ActionEvidenceChecker.parse("""
                {"items":[{"item":"바퀴·고정","status":"MAYBE","evidence":"바퀴 — 잠금 장치 안 보임"}]}""");
        assertThat(r.items().get(0).status()).isEqualTo(ActionEvidenceChecker.ItemStatus.UNCLEAR);
        assertThat(r.items().get(0).item()).isEqualTo("바퀴/고정");
        assertThat(r.items().get(0).evidence()).doesNotContain("—");
        assertThat(ActionEvidenceChecker.parse("not json").items()).isEmpty();
    }
}
