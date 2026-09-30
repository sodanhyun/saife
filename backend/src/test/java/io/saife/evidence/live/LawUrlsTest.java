package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 최종 리뷰 F1 — 법제처 OC 자격증명이 링크로 새지 않는지 */
class LawUrlsTest {

    @Test
    void 조문_페이지_URL은_한글_경로를_인코딩하고_OC가_없다() {
        String url = LawUrls.articlePage("산업안전보건기준에 관한 규칙", 42, 0);
        assertThat(url).startsWith("https://www.law.go.kr/%EB%B2%95%EB%A0%B9/");
        assertThat(url).contains("%20").doesNotContain("+").doesNotContain("OC=");
        assertThat(LawUrls.articlePage("산업안전보건법", 38, 2))
                .isEqualTo("https://www.law.go.kr/%EB%B2%95%EB%A0%B9/"
                        + "%EC%82%B0%EC%97%85%EC%95%88%EC%A0%84%EB%B3%B4%EA%B1%B4%EB%B2%95/"
                        + "%EC%A0%9C38%EC%A1%B0%EC%9D%982");
    }

    @Test
    void OC_파라미터를_위치별로_지우고_구분자를_정리한다() {
        String oc = "OC" + "=sample_id";   // 자리표시 값
        assertThat(LawUrls.stripOc("https://h/DRF/x.do?" + oc + "&target=law&MST=1#J36:0"))
                .isEqualTo("https://h/DRF/x.do?target=law&MST=1#J36:0");
        assertThat(LawUrls.stripOc("https://h/x.do?" + oc)).isEqualTo("https://h/x.do");
        assertThat(LawUrls.stripOc("https://h/x.do?" + oc + "#J1")).isEqualTo("https://h/x.do#J1");
        assertThat(LawUrls.stripOc("https://h/x.do?a=1&" + oc)).isEqualTo("https://h/x.do?a=1");
        assertThat(LawUrls.stripOc("https://h/x.do?a=1&" + oc + "&b=2")).isEqualTo("https://h/x.do?a=1&b=2");
    }

    @Test
    void JSON_문자열_안의_URL도_지우고_나머지_텍스트는_건드리지_않는다() {
        String oc = "OC" + "=sample_id";
        String json = "{\"sourceUrl\":\"https://h/x.do?" + oc + "&MST=1\",\"q\":\"맞습니까?\"}";
        assertThat(LawUrls.stripOc(json)).isEqualTo("{\"sourceUrl\":\"https://h/x.do?MST=1\",\"q\":\"맞습니까?\"}");
        assertThat(LawUrls.stripOc(null)).isNull();
        assertThat(LawUrls.stripOc("OC 없음?")).isEqualTo("OC 없음?");
    }
}
