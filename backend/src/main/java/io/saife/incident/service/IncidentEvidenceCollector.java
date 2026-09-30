package io.saife.incident.service;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.service.LawCitationTable;
import io.saife.evidence.service.LawEvidenceBuilder;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사고 등록(UC2) 응답에 실을 근거 — 유사 사례 3건(사진 우선) + 사고 후 조문 2건.
 *
 * <p><b>검색 실패가 등록을 막으면 안 된다.</b> 사례 검색·조문 조회를 각각 개별
 * try/catch로 감싸 실패는 빈 리스트로 흡수한다({@code IncidentServiceEvidenceTest}).
 *
 * <p>대화 원장({@code EvidenceLedger})을 쓰지 않는다 — UC2는 대화가 아니라 등록
 * 한 번짜리 호출이다. 이번 응답 안에서만 유일한 <b>새 카운터</b>로 1부터 번호를 매긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEvidenceCollector {

    private static final int CASE_LIMIT = 3;
    private static final String BUSINESS = "제조업";

    /** @param similarCases 유사 사례(사진 우선 쿼터 적용). {@code laws}는 사고 후 조문 */
    public record Collected(List<Evidence> similarCases, List<Evidence> laws) {
        public List<Evidence> all() {
            List<Evidence> out = new ArrayList<>(similarCases);
            out.addAll(laws);
            return out;
        }
    }

    private final EvidenceSearchService search;
    private final LawArticleService lawArticleService;

    /**
     * @param queryText 유사 사례 검색어. 호출부(IncidentService)가 <b>설비명 + 사고 경위</b>로
     *                  미리 조립해 넘긴다 — 장소 태그는 절대 섞지 않는다(R51). 발생형태 축
     *                  라벨은 여기서 앞에 붙인다(이미 포함돼 있으면 중복하지 않는다).
     * @param axis      사고의 발생형태. {@code accidentType}은 등록 요청의 선택 필드라 null일 수
     *                  있다(R54) — null이어도 사례 검색 자체를 생략하지 않는다. 설명 키워드(또는
     *                  "사고")만으로 축 필터·가산점 없이 검색한다. 법 조문은 축과 무관하게 고정이다
     */
    public Collected collect(String queryText, AccidentType axis) {
        List<Evidence> cases = searchSimilarCases(queryText, axis);
        List<Evidence> laws = new ArrayList<>();
        for (LawCitationTable.Citation c : LawCitationTable.afterIncident()) {
            laws.addAll(LawEvidenceBuilder.build(lawArticleService, c));
        }

        int no = 1;
        List<Evidence> numberedCases = new ArrayList<>();
        for (Evidence e : cases) {
            numberedCases.add(e.withNo(no++));
        }
        List<Evidence> numberedLaws = new ArrayList<>();
        for (Evidence e : laws) {
            numberedLaws.add(e.withNo(no++));
        }
        return new Collected(numberedCases, numberedLaws);
    }

    /**
     * 검색 실패는 도구 실패가 아니라 빈 결과다 — 등록 자체가 이걸로 막히면 안 된다.
     *
     * <p><b>axis가 null이어도 검색을 생략하지 않는다(R54).</b> {@code accidentType}은
     * 사고 등록 요청의 선택 필드라 비어 올 수 있는데, 여기서 빈 리스트로 끊으면
     * "발생형태를 안 적었다"는 이유만으로 유사 사례 근거 자체가 통째로 사라진다.
     * 축이 없으면 {@code SearchRequest.cases(q, null, ...)}로 넘겨 축 필터·가산점 없이
     * 설명 키워드(또는 "사고")만으로 검색한다.
     */
    private List<Evidence> searchSimilarCases(String queryText, AccidentType axis) {
        String q = buildQuery(queryText, axis);
        if (q.isBlank()) {
            return List.of();
        }
        try {
            return search.search(SearchRequest.cases(q, axis, BUSINESS, CASE_LIMIT));
        } catch (Exception e) {
            log.warn("[UC2] 유사 사례 검색 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 축 라벨을 앞에 붙인다 — 이미 포함돼 있으면 중복하지 않는다(HazardAnalysisTools.searchCases와
     * 동일 규칙). axis가 null이면 라벨을 붙이지 않고, 설명 키워드마저 없으면 "사고"로 대체한다.
     */
    private String buildQuery(String queryText, AccidentType axis) {
        String base = queryText == null ? "" : queryText.strip();
        if (axis == null) {
            return base.isBlank() ? "사고" : base;
        }
        String axisLabel = axis.getLabel();
        if (base.isBlank()) {
            return axisLabel;
        }
        return base.contains(axisLabel) ? base : axisLabel + " " + base;
    }
}
