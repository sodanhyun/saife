package io.saife.evidence.cases;

import io.saife.core.domain.AccidentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 작업 전 점검표에 붙는 유사 재해사례. 원인과 대책 절이 있는 국내재해사례 원문을 고른다.
 *
 * <p>근거 검색(하이브리드 RRF)은 "관련 있는 글"을 찾는다. 점검표 카드에 필요한 것은 그중
 * <b>원인과 대책이 적혀 있어 이번 작업과 대조할 수 있는 사례</b>다. 사진만 있는 사고사망 속보는
 * 근거 목록에 남고 이 카드에는 오지 않는다.
 *
 * <p>모델 호출 없음. 작업명과 설비명의 단어, 발생형태로 점수를 매기고 원문을 {@link CaseDigest}로 자른다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimilarCaseService {

    private static final int CANDIDATES = 400;
    private static final int LIMIT = 2;

    /** 같은 뜻의 현장 말. 사례 원문은 "도장, 도색, 천정"을 섞어 쓴다 */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("페인트", List.of("페인트", "도장", "도색")),
            Map.entry("도장", List.of("도장", "도색", "페인트")),
            Map.entry("도색", List.of("도색", "도장", "페인트")),
            Map.entry("천장", List.of("천장", "천정")),
            Map.entry("천정", List.of("천장", "천정")),
            Map.entry("사다리", List.of("사다리")),
            Map.entry("비계", List.of("비계")),
            Map.entry("지게차", List.of("지게차")),
            Map.entry("크레인", List.of("크레인")),
            Map.entry("하역", List.of("하역", "적재", "상차", "하차")));

    /** 단어로 쓰지 않는 말 */
    private static final Set<String> STOP = Set.of("작업", "작업장", "설비", "기계", "공장", "공장동", "후면", "전면",
            "1호", "2호", "3호", "A", "B", "C", "정기", "점검", "교체", "보수");

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param id       사례 PK
     * @param title    사례 제목(공단 키워드)
     * @param business 업종
     * @param year     발생 연도 표기
     */
    public record SimilarCase(Long id, String title, String business, String year, String summary,
                              List<CaseDigest.Item> causes, List<CaseDigest.Item> measures, String sourceUrl) {}

    public List<SimilarCase> find(String workName, String equipmentName, Collection<AccidentType> axes) {
        List<String> terms = terms(workName, equipmentName);
        if (terms.isEmpty()) return List.of();
        String regex = String.join("|", terms);

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("re", regex)
                .addValue("limit", CANDIDATES);
        String axisCond = "";
        if (axes != null && !axes.isEmpty()) {
            p.addValue("axes", axes.stream().map(Enum::name).toList());
            axisCond = " AND accident_type IN (:axes)";
        }
        String sql = """
                SELECT id, keyword, business, occurred_on, contents, source_url
                FROM public_case
                WHERE source = 'DISASTER'
                  AND contents ~ '원\\s*인' AND contents ~ '대\\s*책'
                  AND (keyword ~ :re OR contents ~ :re)
                """ + axisCond + " ORDER BY (keyword ~ :re) DESC, (business = '제조업') DESC, id LIMIT :limit";

        record Row(Long id, String keyword, String business, LocalDate on, String contents, String url) {}
        List<Row> rows = jdbc.query(sql, p, (rs, i) -> new Row(rs.getLong("id"), rs.getString("keyword"),
                rs.getString("business"), rs.getObject("occurred_on", LocalDate.class),
                rs.getString("contents"), rs.getString("source_url")));

        List<Map.Entry<Double, SimilarCase>> scored = new ArrayList<>();
        for (Row r : rows) {
            CaseDigest.Digest d = CaseDigest.parse(r.contents());
            if (!d.hasCauseAndMeasure()) continue;
            double score = score(r.keyword(), r.contents(), r.business(), terms, d);
            String year = d.year() != null ? d.year() : r.on() == null ? null : r.on().getYear() + "년";
            String title = r.keyword() == null ? null : r.keyword().replaceAll("\\s*[·ㆍ]\\s*", "/").strip();
            scored.add(Map.entry(score, new SimilarCase(r.id(), title, r.business(), year, d.summary(),
                    d.causes(), d.measures(), r.url())));
        }
        scored.sort((a, b) -> Double.compare(b.getKey(), a.getKey()));
        List<SimilarCase> out = scored.stream().limit(LIMIT).map(Map.Entry::getValue).toList();
        log.info("[CASE] 유사 재해사례 단어={} 후보={} 선택={}", terms, rows.size(),
                out.stream().map(SimilarCase::id).toList());
        return out;
    }

    /** 제목에 맞으면 3점, 본문에 맞으면 1점(단어마다 한 번). 제조업 1점, 개요와 설명이 갖춰지면 1점 */
    static double score(String keyword, String contents, String business, List<String> terms, CaseDigest.Digest d) {
        double s = 0;
        Set<String> groups = new HashSet<>();
        for (String t : terms) {
            String group = SYNONYMS.containsKey(t) ? SYNONYMS.get(t).get(0) : t;
            if (keyword != null && keyword.contains(t) && groups.add("k:" + group)) s += 3;
            else if (contents != null && contents.contains(t) && groups.add("c:" + group)) s += 1;
        }
        if ("제조업".equals(business)) s += 1;
        if (d.summary() != null) s += 0.5;
        if (d.causes().stream().allMatch(i -> i.detail() != null)) s += 0.5;
        return s;
    }

    static List<String> terms(String workName, String equipmentName) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String src : new String[]{workName, equipmentName}) {
            if (src == null) continue;
            for (String w : src.split("[\\s,/()]+")) {
                String word = w.strip();
                if (word.length() < 2 || STOP.contains(word)) continue;
                out.addAll(SYNONYMS.getOrDefault(word, List.of(word)));
            }
        }
        return out.stream().filter(t -> t.matches("[가-힣A-Za-z0-9]+")).toList();
    }
}
