package io.saife.publicapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공공 사례 본문 전처리 테스트.
 *
 * <p>여기 있는 입력은 전부 <b>실제 응답에서 잘라온 것</b>이다. 지어낸 입력으로
 * 통과하는 테스트는 이 전처리에서 아무 의미가 없다 — 문제는 항상 실제 데이터의
 * 예상 못 한 모양에서 나왔다.
 */
class CaseTextCleanerTest {

    @Test
    @DisplayName("base64 이미지가 통째로 들어간 태그를 제거한다 (실측 최대 535KB)")
    void removesBase64ImageTag() {
        String raw = "<p><img src='data:image/jpeg;base64,"
                + "/9j/4AAQSkZJRgABAQEAYABgAAD".repeat(200)
                + "'></p><p>지붕 작업 중 추락</p>";

        String cleaned = CaseTextCleaner.clean(raw);

        assertThat(cleaned).doesNotContain("base64");
        assertThat(cleaned).doesNotContain("/9j/");
        assertThat(cleaned).isEqualTo("지붕 작업 중 추락");
    }

    @Test
    @DisplayName("CR·LF·탭을 공백 한 칸으로 정규화한다")
    void normalizesControlCharacters() {
        // 실측: "소재 OO금속에서\r\r패널 교체" — CR이 보이지 않아 단어가 붙은 것처럼 보였다
        String raw = "소재 OO금속에서\r\r패널 교체 작업 중\n\t15m 높이에서 떨어져 사망";

        String cleaned = CaseTextCleaner.clean(raw);

        assertThat(cleaned).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
        assertThat(cleaned).isEqualTo("소재 OO금속에서 패널 교체 작업 중 15m 높이에서 떨어져 사망");
    }

    @Test
    @DisplayName("꼬리말 고지문을 제거한다")
    void removesDisclaimerTail() {
        String raw = "<p>후진하는 타이어롤러에 부딪힘(사망 1명)"
                + "※ 위 내용은 신고 및 현재 파악된 내용으로 조사결과에 따라 변경될 수 있습니다.<br></p>";

        assertThat(CaseTextCleaner.clean(raw)).isEqualTo("후진하는 타이어롤러에 부딪힘(사망 1명)");
    }

    @Test
    @DisplayName("태그 제거 부산물인 깨진 공백을 붙인다")
    void fixesBrokenSpacing() {
        assertThat(CaseTextCleaner.clean("사망 1 명")).isEqualTo("사망 1명");
        assertThat(CaseTextCleaner.clean("( 수 )")).isEqualTo("(수)");
        assertThat(CaseTextCleaner.clean("15:29경충남 아산시")).isEqualTo("15:29경 충남 아산시");
    }

    @Test
    @DisplayName("조사 확장형은 건드리지 않는다 — 현장에서의/사업장에서는")
    void keepsParticleForms() {
        assertThat(CaseTextCleaner.clean("현장에서의 작업")).isEqualTo("현장에서의 작업");
        assertThat(CaseTextCleaner.clean("사업장에서는 중대재해")).isEqualTo("사업장에서는 중대재해");
        // 붙은 문장은 떼어놓는다
        assertThat(CaseTextCleaner.clean("공사 현장에서신호수인")).isEqualTo("공사 현장에서 신호수인");
    }

    @Test
    @DisplayName("[9/17, 충남 아산시] 머리에서 지역과 일자를 뽑는다")
    void extractsRegionAndDate() {
        String keyword = "[9/17, 충남 아산시] 차량 유도 작업 중 후진하는 타이어롤러에 부딪힘";

        assertThat(CaseTextCleaner.regionOf(keyword)).isEqualTo("충남 아산시");
        assertThat(CaseTextCleaner.occurredOn(keyword))
                .isNotNull()
                .satisfies(d -> {
                    assertThat(d.getMonthValue()).isEqualTo(9);
                    assertThat(d.getDayOfMonth()).isEqualTo(17);
                });
    }

    @Test
    @DisplayName("머리 포맷을 벗어나면 추측하지 않고 null을 돌려준다")
    void doesNotGuessRegion() {
        // 2019년대 레코드에는 대괄호 머리가 없다. 틀린 지역이 붙으면 필터가 조용히 망가진다
        assertThat(CaseTextCleaner.regionOf("공장 상층 패널교체 작업 중 추락 사망")).isNull();
        assertThat(CaseTextCleaner.occurredOn("공장 상층 패널교체 작업 중 추락 사망")).isNull();
        assertThat(CaseTextCleaner.regionOf(null)).isNull();
    }

    @Test
    @DisplayName("null과 빈 문자열을 그대로 돌려준다")
    void handlesEmptyInput() {
        assertThat(CaseTextCleaner.clean(null)).isNull();
        assertThat(CaseTextCleaner.clean("")).isEmpty();
    }

    @Test
    void imageUrl_첫_img_src를_뽑는다() {
        String raw = "<p><img src='https://portal.kosha.or.kr/api/compn24/auth/stdtboard/getImage.do?bbsId=B1&pstNo=P1&bbsAtcflNo=E1' style='width: 931px;'></p><p>본문</p>";
        assertThat(CaseTextCleaner.imageUrlOf(raw))
                .isEqualTo("https://portal.kosha.or.kr/api/compn24/auth/stdtboard/getImage.do?bbsId=B1&pstNo=P1&bbsAtcflNo=E1");
    }

    @Test
    void imageUrl_쌍따옴표와_복수_태그() {
        String raw = "<img src=\"https://portal.kosha.or.kr/a.png\"><img src='https://portal.kosha.or.kr/b.png'>";
        assertThat(CaseTextCleaner.imageUrlOf(raw)).isEqualTo("https://portal.kosha.or.kr/a.png");
    }

    @Test
    void imageUrl_base64나_외부호스트는_버린다() {
        assertThat(CaseTextCleaner.imageUrlOf("<img src='data:image/png;base64,AAAA'>")).isNull();
        assertThat(CaseTextCleaner.imageUrlOf("<img src='https://evil.example/x.png'>")).isNull();
        assertThat(CaseTextCleaner.imageUrlOf(null)).isNull();
    }
}
