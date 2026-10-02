package io.saife.incident.service;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.service.LawCitationTable;
import io.saife.evidence.service.LawEvidenceBuilder;
import io.saife.incident.domain.IncidentType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 *
 * <p>근거 카드는 그대로 화면과 조사표 초안 프롬프트에 나간다. 그래서 여기서 다듬는다.
 * <ul>
 *   <li>무관 사례 제외: 업종이 제조업이 아닌 사례, 사고 발생형태와 축이 다른 사례, 공사현장 사례</li>
 *   <li>제목 머리표 제거: "[추락]", "[제조업]", "[6/19, 경남 거제시]", "[사망 1명]", "(200903)" 같은 원문 표기</li>
 *   <li>발췌 끝의 "null"(원문 본문이 비어 있던 흔적) 제거</li>
 *   <li>시행규칙 제37조는 인용한 항호(제2항제3호) 본문을 발췌로 보인다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEvidenceCollector {

    private static final int CASE_LIMIT = 3;
    /** 무관 사례를 걸러낸 뒤에도 3건이 남도록 넉넉히 받는다 */
    private static final int CASE_FETCH = 12;
    private static final String BUSINESS = "제조업";

    /** 대괄호 머리표 하나: [추락], [제조업], [6/19, 경남 거제시], [사망 1명] */
    private static final Pattern BRACKET_TAG = Pattern.compile("^\\s*\\[[^\\]]{1,30}]\\s*");
    /** 끝의 "(200903)" 같은 연월 코드 */
    private static final Pattern TRAILING_CODE = Pattern.compile("\\s*\\(\\d{4,8}\\)\\s*$");
    /** 본문 끝의 "null" */
    private static final Pattern TRAILING_NULL = Pattern.compile("\\s*null\\s*(…)?\\s*$");
    private static final Pattern CONSTRUCTION = Pattern.compile("공사\\s?현장|건설\\s?현장|공사장");

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
     *                  미리 조립해 넘긴다 — 장소 태그는 절대 섞지 않는다(R51). 발생형태
     *                  검색어는 여기서 앞에 붙인다(이미 포함돼 있으면 중복하지 않는다).
     * @param type      사고 발생형태. null이어도 사례 검색 자체를 생략하지 않는다(R54).
     *                  6축 대응이 있으면 그 축으로 필터와 가산점을 준다. 법 조문은 발생형태와 무관하게 고정이다
     */
    public Collected collect(String queryText, IncidentType type) {
        List<Evidence> cases = searchSimilarCases(queryText, type);
        List<Evidence> laws = new ArrayList<>();
        for (LawCitationTable.Citation c : LawCitationTable.afterIncident()) {
            for (Evidence e : LawEvidenceBuilder.build(lawArticleService, c)) {
                laws.add(focusLaw(e, c));
            }
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
     * 넉넉히 받아 무관 사례를 걸러낸 뒤 앞에서 3건을 쓴다(검색이 정한 순서, 사진 우선 쿼터 유지).
     */
    private List<Evidence> searchSimilarCases(String queryText, IncidentType type) {
        String q = buildQuery(queryText, type);
        if (q.isBlank()) {
            return List.of();
        }
        AccidentType axis = type == null ? null : type.axis();
        try {
            return search.search(SearchRequest.cases(q, axis, BUSINESS, CASE_FETCH)).stream()
                    .filter(e -> relevant(e, axis))
                    .map(IncidentEvidenceCollector::tidyCase)
                    .limit(CASE_LIMIT)
                    .toList();
        } catch (Exception e) {
            log.warn("[UC2] 유사 사례 검색 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 이 사고의 근거가 될 만한 사례인가. 업종이 적혀 있으면 제조업만, 축이 적혀 있으면 사고 축과 같은 것만,
     * 본문이 공사현장이면 제외한다(제조 사업장 사고의 근거로 약하다).
     */
    static boolean relevant(Evidence e, AccidentType axis) {
        Map<String, Object> m = e.meta() == null ? Map.of() : e.meta();
        Object business = m.get("business");
        if (business instanceof String b && !b.isBlank() && !BUSINESS.equals(b.strip())) {
            return false;
        }
        Object caseAxis = m.get("accidentType");
        if (axis != null && caseAxis instanceof String a && !a.isBlank() && !axis.name().equals(a)) {
            return false;
        }
        String text = (e.title() == null ? "" : e.title()) + " " + (e.snippet() == null ? "" : e.snippet());
        return !CONSTRUCTION.matcher(text).find();
    }

    /** 제목 머리표와 연월 코드, 발췌 끝 "null"을 지운다 */
    static Evidence tidyCase(Evidence e) {
        return new Evidence(e.no(), e.kind(), e.refId(), e.refKey(), cleanTitle(e.title()), cleanSnippet(e.snippet()),
                e.sourceUrl(), e.mediaUrl(), e.thumbnailUrl(), e.origin(), e.score(), e.fetchedAt(), e.meta());
    }

    static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title.strip();
        String prev;
        do {
            prev = t;
            t = BRACKET_TAG.matcher(t).replaceFirst("");
        } while (!t.equals(prev));
        t = TRAILING_CODE.matcher(t).replaceFirst("");
        t = t.replace("·", ", ").replaceAll("\\s{2,}", " ").strip();
        return t.isEmpty() ? title.strip() : t;
    }

    static String cleanSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        String s = TRAILING_NULL.matcher(snippet).replaceFirst("").strip();
        return s.replace("·", ", ");
    }

    /**
     * 조문 카드의 발췌를 인용한 항호로 맞춘다. 시행규칙 제37조는 수시평가 근거인 제2항제3호를,
     * 제73조는 제출 의무를 정한 제1항을 보인다. 원문 전문은 {@code meta.fullText}에 그대로 남는다.
     */
    private Evidence focusLaw(Evidence e, LawCitationTable.Citation c) {
        String full = e.meta() == null ? null : (String) e.meta().get("fullText");
        if (full == null || full.isBlank()) {
            return e;
        }
        String snippet = null;
        if (c.articleNo() == 37 && c.articleSub() == 0) {
            String p2 = paragraph(full, '②');
            if (p2 != null) {
                snippet = p2 + "\n3. 수시평가: 산업재해가 발생한 경우 관련 작업을 시작하기 전까지";
            }
        } else if (c.articleNo() == 73 && c.articleSub() == 0) {
            snippet = paragraph(full, '①');
        }
        if (snippet == null) {
            return e;
        }
        Map<String, Object> meta = new LinkedHashMap<>(e.meta());
        meta.put("focus", c.articleNo() == 37 ? "제2항제3호" : "제1항");
        return new Evidence(e.no(), e.kind(), e.refId(), e.refKey(), e.title(), snippet,
                e.sourceUrl(), e.mediaUrl(), e.thumbnailUrl(), e.origin(), e.score(), e.fetchedAt(), meta);
    }

    /** 원문에서 ①, ② 같은 항 하나를 줄 단위로 꺼낸다 */
    private static String paragraph(String full, char mark) {
        for (String line : full.split("\\r?\\n")) {
            if (!line.isBlank() && line.strip().charAt(0) == mark) {
                return line.strip();
            }
        }
        return null;
    }

    /**
     * 발생형태 검색어를 앞에 붙인다 — 이미 포함돼 있으면 중복하지 않는다. type이 null이면 붙이지 않고,
     * 설명 키워드마저 없으면 "사고"로 대체한다.
     */
    private String buildQuery(String queryText, IncidentType type) {
        String base = queryText == null ? "" : queryText.strip();
        if (type == null) {
            return base.isBlank() ? "사고" : base;
        }
        String term = type.getSearchTerm();
        if (base.isBlank()) {
            return term;
        }
        return base.contains(term) ? base : term + " " + base;
    }
}
