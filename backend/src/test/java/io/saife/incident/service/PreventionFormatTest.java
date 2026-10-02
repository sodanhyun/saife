package io.saife.incident.service;

import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.Origin;
import io.saife.core.domain.AccidentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 재발방지 줄 형식(A-5)과 사고 근거 카드 정리(A-8). DB 없이 도는 단위 테스트 */
class PreventionFormatTest {

    private static final LocalDate OCCURRED = LocalDate.of(2026, 10, 2);   // 금요일

    @Test
    @DisplayName("기한은 YYYY-MM-DD로: 날짜 아닌 기한은 대책 성격에 따라 7일 또는 14일(공휴일이면 다음 평일), MM-DD는 연도를 붙인다")
    void normalizesDueDates() {
        String out = PreventionFormat.normalize("""
                1. 차양부 천장 작업 시 이동식 비계(안전난간) 사용 (담당 생산반장 김철수, 기한 작업 재개 전) [#4]
                2. 사다리 사용 기준 교육 (담당 생산반장 김철수, 기한 10-12)
                3. 수시평가 완료 후 작업 재개 (담당 안전관리자 홍길동, 기한 작업 재개 전)
                작업 전 TBM에서 사다리 사용 기준 공유
                """, OCCURRED, "안전관리자 홍길동");

        assertThat(out.split("\n")).containsExactly(
                "1. 차양부 천장 작업 시 이동식 비계(안전난간) 사용 (담당 생산반장 김철수, 기한 2026-10-16) [#4]",
                "2. 사다리 사용 기준 교육 (담당 생산반장 김철수, 기한 2026-10-12)",
                "3. 작업 전 TBM에서 사다리 사용 기준 공유 (담당 안전관리자 홍길동, 기한 2026-10-12)");
        assertThat(out).doesNotContain("작업 재개 전").doesNotContain("수시평가");
    }

    @Test
    @DisplayName("담당이 직책만이면 사업장 안전관리자로, 주말 기한은 다음 평일로")
    void titleOnlyOwnerAndWeekend() {
        String out = PreventionFormat.normalize("1. 줄걸이 점검표 도입 (담당 관리감독자, 기한 즉시)",
                LocalDate.of(2026, 10, 3), "안전관리자 홍길동");   // 토요일 + 7 = 토요일
        assertThat(out).isEqualTo("1. 줄걸이 점검표 도입 (담당 안전관리자 홍길동, 기한 2026-10-12)");
    }

    @Test
    @DisplayName("사례 제목의 원문 머리표와 연월 코드, 발췌 끝 null을 지운다")
    void tidiesCaseTitles() {
        assertThat(IncidentEvidenceCollector.cleanTitle("[추락] [제조업] [6/19, 경남 거제시] 사다리에서 떨어짐 (200903)"))
                .isEqualTo("사다리에서 떨어짐");
        assertThat(IncidentEvidenceCollector.cleanTitle("[사망 1명] 프레스에 끼임")).isEqualTo("프레스에 끼임");
        assertThat(IncidentEvidenceCollector.cleanSnippet("스크류 콘베이어를 점검하던중 협착 null")).isEqualTo("스크류 콘베이어를 점검하던중 협착");
    }

    private Evidence ev(String business, String axis, String snippet) {
        return new Evidence(0, EvidenceKind.CASE_DISASTER, 1L, "D:1", "사례", snippet, null, null, null,
                Origin.CACHE, 0.5, OffsetDateTime.now(), axis == null ? Map.of("business", business)
                : Map.of("business", business, "accidentType", axis));
    }

    @Test
    @DisplayName("무관 사례 제외: 제조업이 아닌 업종, 다른 발생형태, 공사현장")
    void filtersIrrelevantCases() {
        assertThat(IncidentEvidenceCollector.relevant(ev("제조업", "FALL", "작업 중 떨어짐"), AccidentType.FALL)).isTrue();
        assertThat(IncidentEvidenceCollector.relevant(ev("건설업", "FALL", "작업 중 떨어짐"), AccidentType.FALL)).isFalse();
        assertThat(IncidentEvidenceCollector.relevant(ev("제조업", "CAUGHT", "끼임"), AccidentType.FALL)).isFalse();
        assertThat(IncidentEvidenceCollector.relevant(ev("", null, "상가 철거 공사현장에서 떨어짐"), AccidentType.FALL)).isFalse();
        assertThat(IncidentEvidenceCollector.relevant(ev("", null, "공장 내 떨어짐"), null)).isTrue();
    }
}
