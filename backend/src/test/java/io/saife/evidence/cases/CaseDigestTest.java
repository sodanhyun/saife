package io.saife.evidence.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 공단 재해사례 원문 서식 3종에서 개요, 원인, 대책을 원문 그대로 자른다 */
class CaseDigestTest {

    private static final String BRACKET = "제목 : A형 이동식 사다리 위에서 도장작업 중 사망 날짜 : 2001년 02월 업종 : 건설업 "
            + "1. A형 이동식 사다리 위에서 도장작업 중 → 도장공, 추락 사망 ■ 발생월일 : 2001. 2. "
            + "■ 피재자가 바닥에서 2.8m~3.3m 높이의 천장 모서리부분 도장작업을 하기 위하여 A형 사다리 위에서 작업중 "
            + "몸의 균형을 잃고 0.8m 아래로 추락 사망한 재해임 ■ 공사규모 : 2층 기존건물 보수공사 "
            + "4. 원인과 대책 【원 인】 o 작업방법 불량 - 피재자가 양손에 도장작업용 도구를 잡은 채 작업 발판이 아닌 "
            + "이동식 사다리 위에서 작업하다 몸의 균형을 잃고 추락함 ㅇ 개인보호구(안전모) 미착용 - 안전모를 미착용하여 "
            + "낮은 높이의 추락시에도 머리를 다쳐 사망함 【대 책】 o 안전한 작업발판 설치 - 고소작업시 안전한 지지구조의 "
            + "작업발판을 설치 후 작업을 실시함 o 사다리의 올바른 사용 - 사다리는 높은 장소로 이동하기 위한 도구이며, "
            + "가능한 작업발판 대용으로 사다리 위에서의 작업을 금함";

    private static final String NUMBERED = "1. 재해개요 2000년 5월 ○일 10:30분경 경기 소재 ○○금속 출하 대기장의 천장 형광등을 "
            + "교체하고 내려오던 중 사다리가 흔들리면서 추락하여 사망함 3. 재해발생 원인 가. 이동식 사다리 안전장치 미설치 "
            + "사다리 하단부에는 미끄럼방지장치를 하여야 하나 미부착된 사다리를 사용함 나. 작업방법 불량 이동식 사다리 작업시 "
            + "2인1조로 작업을 실시하여야 하나 단독으로 작업을 수행함 4. 동종재해 예방대책 가. 이동식 사다리 안전장치 설치 "
            + "미끄럼 방지장치를 부착함 나. 2인1조 작업 실시 사다리를 잡아 주는 사람을 둠";

    @Test
    void 괄호_서식에서_원인과_대책을_머리말과_설명으로_자른다() {
        CaseDigest.Digest d = CaseDigest.parse(BRACKET);

        assertThat(d.year()).isEqualTo("2001년");
        assertThat(d.summary()).startsWith("피재자가 바닥에서 2.8m~3.3m").endsWith("재해임.");
        assertThat(d.causes()).extracting(CaseDigest.Item::head)
                .containsExactly("작업방법 불량", "개인보호구(안전모) 미착용");
        assertThat(d.causes().get(0).detail()).contains("작업 발판이 아닌 이동식 사다리 위에서");
        assertThat(d.measures()).extracting(CaseDigest.Item::head)
                .containsExactly("안전한 작업발판 설치", "사다리의 올바른 사용");
        assertThat(d.hasCauseAndMeasure()).isTrue();
    }

    @Test
    void 번호_서식에서도_원인과_대책을_찾는다() {
        CaseDigest.Digest d = CaseDigest.parse(NUMBERED);

        assertThat(d.causes()).extracting(CaseDigest.Item::head)
                .containsExactly("이동식 사다리 안전장치 미설치", "작업방법 불량");
        assertThat(d.measures()).extracting(CaseDigest.Item::head).first().isEqualTo("이동식 사다리 안전장치 설치");
        assertThat(d.summary()).contains("형광등").endsWith("사망함.");
    }

    @Test
    void 원문의_가운뎃점은_슬래시로_바꾼다() {
        CaseDigest.Digest d = CaseDigest.parse(
                "[원인] o 승·하강용 사다리 미설치 - 틀비계에 승ㆍ하강용 사다리를 두지 않음 [대책] o 승·하강용 사다리 설치 철저");
        assertThat(d.causes().get(0).head()).isEqualTo("승/하강용 사다리 미설치");
        assertThat(d.causes().get(0).detail()).doesNotContain("ㆍ").contains("승/하강용");
        assertThat(d.measures().get(0).head()).doesNotContain("·");
    }

    @Test
    void 원인_절이_없으면_원인과_대책을_비운다() {
        CaseDigest.Digest d = CaseDigest.parse("재해개요 피재자가 외벽 도장작업 중 추락하여 사망한 재해임. ※ 상세 내용은 첨부파일 참조");

        assertThat(d.hasCauseAndMeasure()).isFalse();
        assertThat(d.summary()).contains("외벽 도장작업");
        assertThat(CaseDigest.parse(null).causes()).isEmpty();
    }

    @Test
    void 작업명과_설비명에서_현장_동의어까지_검색어를_만든다() {
        List<String> terms = SimilarCaseService.terms("천장 페인트 작업", "이동식 사다리 A");

        assertThat(terms).contains("천장", "천정", "페인트", "도장", "도색", "이동식", "사다리")
                .doesNotContain("작업", "A");
    }
}
