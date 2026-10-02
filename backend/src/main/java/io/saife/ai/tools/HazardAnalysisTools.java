package io.saife.ai.tools;

import io.saife.ai.agent.AgentContextKeys;
import io.saife.common.service.SseService;
import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.live.MsdsLiveClient;
import io.saife.evidence.live.Origin;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.service.LawCitationTable;
import io.saife.evidence.service.LawEvidenceBuilder;
import io.saife.publicapi.service.PublicApiCrawler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 도구 3·4·5 — 위험요인 도출, 사례 매칭, MSDS 조회.
 *
 * <p>근거 계층(evidence)으로 재배선됐다. 사례·지침은 하이브리드 벡터/키워드 검색,
 * 법 조문은 고정 인용표 + 라이브 확인, MSDS는 라이브 우선 + 캐시 폴백이다.
 * 셋 다 결과를 {@link EvidenceLedger}에 등록해 번호({@code #n})를 받고, 도구 결과 텍스트에
 * 그 번호가 그대로 박혀 모델이 인용할 수 있게 한다.
 *
 * <p>검색·원장 등록이 실패해도 도구는 예외를 던지지 않는다 — 서술 텍스트만이라도 반환한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HazardAnalysisTools {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private final EvidenceSearchService search;
    private final LawArticleService laws;
    private final MsdsLiveClient msds;
    private final EvidenceLedger ledger;
    private final PublicApiCrawler crawler;
    private final SseService sseService;

    @Tool(description = """
            <tool-description>
            <purpose>작업 유형·장소·설비·취급물질 조합으로 발생형태별 위험요인을 도출하고, 관련 KOSHA GUIDE 기술지침과 법 조문을 근거 번호와 함께 반환합니다.</purpose>
            <returns>발생형태 목록과 각 축의 빠진 안전조치, 관련 기술지침(#n)과 법 조문(#n). 위험성 등급은 여기서 정하지 않습니다.</returns>
            <prerequisites>findLocationEquipment로 장소를 확인한 뒤 호출하세요.</prerequisites>
            </tool-description>
            """)
    public String analyzeHazards(@ToolParam(description = "작업 유형 (예: 도장, 용접, 정비, 청소)") String workType,
                                 @ToolParam(description = "작업 장소 설명", required = false) String location,
                                 @ToolParam(description = "사용 설비 (예: 이동식 사다리, 고소작업대)", required = false) String equipment,
                                 @ToolParam(description = "취급 물질 (예: 유성페인트, 시너)", required = false) String material,
                                 ToolContext toolContext) {
        return ToolCallTracker.execute("analyzeHazards", Map.of("workType", String.valueOf(workType), "equipment", String.valueOf(equipment)),
                sseService, toolContext, () -> {
            String combined = String.join(" ", nvl(workType), nvl(location), nvl(equipment), nvl(material)).strip();
            Set<AccidentType> axes = deriveAxes(combined);
            if (axes.isEmpty()) {
                return ToolResult.of("작업 정보가 부족해 위험요인을 도출할 수 없습니다. 작업 유형과 사용 설비를 물어보세요.");
            }
            String cid = AgentContextKeys.conversationId(toolContext);
            StringBuilder sb = new StringBuilder("도출된 발생형태 " + axes.size() + "종:\n");
            for (AccidentType axis : axes) sb.append("- ").append(axis.getLabel()).append(": ").append(axis.getMissingControlHint()).append('\n');

            List<Evidence> guides = registerSafely(cid, safeSearch(SearchRequest.guides(combined, 3)));
            if (!guides.isEmpty()) {
                sb.append("\n관련 기술지침:\n");
                guides.forEach(g -> sb.append(line(g)).append('\n'));
            }
            List<Evidence> lawCards = new ArrayList<>();
            for (AccidentType axis : axes) {
                for (LawCitationTable.Citation c : LawCitationTable.forAxis(axis).stream().limit(2).toList()) lawCards.addAll(LawEvidenceBuilder.build(laws, c));
            }
            for (LawCitationTable.Citation c : LawCitationTable.common()) lawCards.addAll(LawEvidenceBuilder.build(laws, c));
            List<Evidence> numberedLaws = registerSafely(cid, lawCards);
            if (!numberedLaws.isEmpty()) {
                sb.append("\n관련 법 조문:\n");
                numberedLaws.forEach(l -> sb.append(line(l)).append('\n'));
            }
            sb.append("\n※ 위험성 등급은 작업높이, 안전대 부착설비 등 현장 확인 항목을 받은 뒤 룰 엔진이 결정합니다.\n");
            ToolCallTracker.summarize("발생형태 " + axes.size() + "종 ("
                    + String.join(", ", axes.stream().map(AccidentType::getLabel).toList())
                    + "), 지침 " + guides.size() + "건, 조문 " + numberedLaws.size() + "건");
            return ToolResult.of(sb.toString());
        });
    }

    @Tool(description = """
            <tool-description>
            <purpose>유사한 실제 사고사례를 공단 데이터에서 찾아 근거 번호와 함께 첨부합니다. 사진이 있는 사례는 [사진]으로 표시됩니다. query가 없으면 equipment(사용 설비)·workType(작업 유형)으로 대체 질의를 만듭니다 — 위치가 아니라 발생형태·설비·작업이 매칭 축입니다.</purpose>
            <returns>업종·발생형태·작업 설명(또는 equipment·workType 대체 질의)과 유사한 사고사례 최대 3건. 각 줄은 #번호, 한 줄 요약, 유사도, 출처 상태.</returns>
            <prerequisites>analyzeHazards로 발생형태를 도출한 뒤, 작업 설명을 query로 넘겨 호출하세요. query를 모르면 equipment·workType만으로도 동작합니다.</prerequisites>
            </tool-description>
            """)
    public String searchCases(@ToolParam(description = "발생형태 코드 (FALL 추락 / CAUGHT 협착 / DROP 낙하 / STRUCK 부딪힘 / FIRE 화재 / PPE 보호구)") String accidentType,
                              @ToolParam(description = "업종 필터 (예: 제조업, 건설업, 조선업). 모르면 생략", required = false) String business,
                              @ToolParam(description = "작업 설명 자연어 (예: 사다리 위에서 천장 도장). 유사도 검색에 쓰입니다", required = false) String query,
                              @ToolParam(description = "사용 설비 (예: 이동식 사다리, 고소작업대). query가 없을 때 대체 질의에 쓰입니다", required = false) String equipment,
                              @ToolParam(description = "작업 유형 (예: 도장, 용접). query가 없을 때 대체 질의에 쓰입니다", required = false) String workType,
                              ToolContext toolContext) {
        return ToolCallTracker.execute("searchCases",
                Map.of("accidentType", String.valueOf(accidentType), "business", String.valueOf(business),
                        "query", String.valueOf(query), "equipment", String.valueOf(equipment),
                        "workType", String.valueOf(workType)),
                sseService, toolContext, () -> {
            AccidentType axis = parseAxis(accidentType);
            if (axis == null) return ToolResult.of("발생형태를 알 수 없습니다. FALL/CAUGHT/DROP/STRUCK/FIRE/PPE 중 하나로 지정하세요.");
            // 재해 원문에는 사업장 위치가 없다 — 발생형태·설비·작업이 매칭 축이다.
            // query가 없으면 위치 태그가 아니라 발생형태+설비+작업으로 대체 질의를 만든다
            String q;
            if (query == null || query.isBlank()) {
                StringBuilder qb = new StringBuilder(axis.getLabel());
                if (equipment != null && !equipment.isBlank()) qb.append(' ').append(equipment.strip());
                if (workType != null && !workType.isBlank()) qb.append(' ').append(workType.strip());
                q = qb.toString();
            } else {
                q = query.strip();
                if (!q.contains(axis.getLabel())) {
                    q = axis.getLabel() + " " + q;
                }
            }
            String cid = AgentContextKeys.conversationId(toolContext);
            List<Evidence> found = safeSearch(SearchRequest.cases(q, axis, blankToNull(business), 3));
            // 업종 필터(최종 리뷰 F7)로 비면 업종 없이 한 번 더 — 업종이 없던 요청은 같은 검색을 되풀이하지 않는다
            if (found.isEmpty() && blankToNull(business) != null) found = safeSearch(SearchRequest.cases(q, axis, null, 3));
            if (found.isEmpty()) return ToolResult.of("해당 발생형태의 유사 사고사례를 찾지 못했습니다.");
            List<Evidence> numbered = registerSafely(cid, found);
            if (numbered.isEmpty()) return ToolResult.of("해당 발생형태의 유사 사고사례를 찾지 못했습니다.");
            long photos = numbered.stream().filter(e -> e.mediaUrl() != null).count();
            ToolCallTracker.summarize(axis.getLabel() + " 사례 " + numbered.size() + "건" + (photos > 0 ? ", 사진 " + photos + "건" : ""));
            StringBuilder sb = new StringBuilder("유사 사고사례:\n");
            numbered.forEach(e -> sb.append(line(e)).append('\n'));
            safeCheckLatest("FATALITY").ifPresent(li -> sb.append("(공단 사고사망 게시판 최신 등재 ").append(li.totalCount()).append("건 기준, ")
                    .append(li.checkedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))).append(" 확인)\n"));
            return ToolResult.of(sb.toString());
        });
    }

    @Tool(description = """
            <tool-description>
            <purpose>취급 화학물질의 유해성·폭발화재 위험·취급저장 주의사항·노출기준과 보호구를 조회합니다. 가능하면 공단 MSDS를 실시간으로 조회하고, 안 되면 캐시를 씁니다.</purpose>
            <returns>MSDS 4개 항목의 핵심 내용, GHS 그림문자 코드, 법정 노출기준(TWA/STEL), 근거 번호(#n).</returns>
            <prerequisites>작업자에게 제품명을 먼저 확인하세요. 제품명을 모르면 이 도구를 호출하지 말고 물어보세요.</prerequisites>
            </tool-description>
            """)
    public String getMsds(@ToolParam(description = "제품명 또는 물질명 (예: 톨루엔, 유성페인트, 시너)") String productName, ToolContext toolContext) {
        return ToolCallTracker.execute("getMsds", Map.of("productName", String.valueOf(productName)), sseService, toolContext, () -> {
            if (productName == null || productName.isBlank()) return ToolResult.of("제품명이 없습니다. 작업자에게 사용 제품명을 물어보세요.");
            Fetched<MsdsLiveClient.MsdsBundle> f;
            try {
                f = msds.resolve(productName.strip());
            } catch (Exception e) {
                log.warn("[MSDS] 조회 실패 productName={}: {}", productName, e.getMessage());
                return ToolResult.of("'" + productName + "'의 MSDS 조회 중 오류가 발생했습니다. 다시 시도하거나 제품 용기 라벨을 확인하도록 안내하세요.");
            }
            if (f.isEmpty()) {
                return ToolResult.of("'" + productName + "'의 MSDS를 찾지 못했습니다(" + f.note() + "). 주요 성분명으로 다시 시도하거나 제품 용기 라벨을 확인하도록 안내하세요.");
            }
            MsdsLiveClient.MsdsBundle b = f.value();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chemId", b.chemId()); meta.put("casNo", b.casNo()); meta.put("pictograms", b.pictograms());
            meta.put("sections", b.sections());
            meta.put("sourceUrl", "https://msds.kosha.or.kr/MSDSInfo/kcic/msdsdetail.do?chem_id=" + b.chemId());
            Evidence card = new Evidence(0, EvidenceKind.MSDS, null, "MSDS:" + b.chemId(), b.chemNameKor() + " MSDS (공단)",
                    String.join(" / ", b.sections().getOrDefault("02", List.of())), (String) meta.get("sourceUrl"), null, null,
                    f.origin(), 1.0, f.fetchedAt(), meta);
            List<Evidence> numberedList = registerSafely(AgentContextKeys.conversationId(toolContext), List.of(card));
            Evidence numbered = numberedList.isEmpty() ? card : numberedList.get(0);
            ToolCallTracker.summarize(b.chemNameKor() + " MSDS 4개 항목" + (f.origin() == Origin.LIVE ? " (실시간)" : " (캐시)"));
            StringBuilder sb = new StringBuilder();
            // 번호가 없으면(conversationId 없음·원장 등록 실패 → no()==0) 접두사를 생략한다
            if (numbered.no() > 0) sb.append("#").append(numbered.no()).append(' ');
            sb.append("[").append(b.chemNameKor()).append("] MSDS (")
              .append(f.origin() == Origin.LIVE ? "실시간 조회" : "캐시").append(")\n");
            if (b.pictograms() != null) sb.append("그림문자: ").append(b.pictograms()).append('\n');
            b.sections().forEach((sec, lines) -> {
                if (lines.isEmpty()) return;
                sb.append('\n').append(sectionName(sec)).append('\n');
                lines.stream().limit(8).forEach(l -> sb.append("  - ").append(l).append('\n'));
            });
            return ToolResult.of(sb.toString());
        });
    }

    /**
     * 근거 한 줄: #n [사진] 제목 (유사도 84%, 캐시 09-21)
     *
     * <p>R49: {@code KEYWORD_FALLBACK}은 유사도가 아니라 정규화된 RRF 순위 점수다 —
     * "유사도 39%"처럼 찍히면 심사위원이 약한 근거로 오독한다. 그래서 이 출처일 때는
     * "유사도 NN%," 구간을 아예 뺀다(출처 접미사 "키워드 검색"만 남는다).
     *
     * <p>번호가 없는 근거(원장 등록 실패·conversationId 없음 → {@code no()==0})는
     * {@code #n } 접두사를 생략한다 — "#0"을 찍으면 실제로 없는 번호를 있는 것처럼 보인다.
     */
    static String line(Evidence e) {
        StringBuilder sb = new StringBuilder();
        if (e.no() > 0) sb.append("#").append(e.no()).append(' ');
        if (e.kind().isCase() && e.mediaUrl() != null) sb.append("[사진] ");
        sb.append(e.title());
        sb.append(" (");
        if (e.kind() != EvidenceKind.LAW && e.kind() != EvidenceKind.MSDS && e.origin() != Origin.KEYWORD_FALLBACK) {
            // 최종 리뷰 F2: 표시값은 매퍼가 이미 0~1로 자르지만, 다른 경로에서 만든 카드가 와도 100%를 넘기지 않는다
            sb.append("유사도 ").append(Math.min(100, Math.max(0, Math.round(e.score() * 100)))).append("%, ");
        }
        sb.append(switch (e.origin()) { case LIVE -> "실시간 조회"; case CACHE -> "캐시 " + e.fetchedAt().format(MD); case KEYWORD_FALLBACK -> "키워드 검색"; });
        return sb.append(')').toString();
    }

    /** 검색 실패가 도구 실패가 되면 안 된다 — 빈 목록으로 흡수한다 */
    private List<Evidence> safeSearch(SearchRequest req) {
        try {
            return search.search(req);
        } catch (Exception e) {
            log.warn("[EVIDENCE] 검색 실패 query={}: {}", req.query(), e.getMessage());
            return List.of();
        }
    }

    /** 원장 등록 실패가 도구 실패가 되면 안 된다 — 실패하면 번호 없이 원본을 그대로 쓴다 */
    private List<Evidence> registerSafely(String conversationId, List<Evidence> items) {
        if (items.isEmpty()) return List.of();
        if (conversationId == null) {
            log.warn("[EVIDENCE] conversationId 없음 — 근거 {}건을 번호 없이 반환합니다", items.size());
            return items;
        }
        try {
            return ledger.registerAll(conversationId, items);
        } catch (Exception e) {
            log.warn("[EVIDENCE] 원장 등록 실패 cid={}: {}", conversationId, e.getMessage());
            return items;
        }
    }

    /** 공단 최신 등재 확인 실패가 도구 실패가 되면 안 된다 */
    private Optional<PublicApiCrawler.LatestInfo> safeCheckLatest(String dataset) {
        try {
            return crawler.checkLatest(dataset);
        } catch (Exception e) {
            log.warn("[CRAWLER] 최신 등재 확인 실패 dataset={}: {}", dataset, e.getMessage());
            return Optional.empty();
        }
    }

    private static String sectionName(String s) {
        return switch (s) { case "02" -> "유해성, 위험성"; case "05" -> "폭발, 화재시 대처방법"; case "07" -> "취급 및 저장방법"; case "08" -> "노출방지 및 개인보호구"; default -> "항목 " + s; };
    }

    /**
     * 작업 설명에서 발생형태 축을 도출한다.
     *
     * <p>규칙 테이블이다. LLM에 맡기지 않는 이유는 무대에서 재현 가능해야 하기 때문이다.
     */
    private Set<AccidentType> deriveAxes(String text) {
        Set<AccidentType> axes = new LinkedHashSet<>();
        if (text.matches("(?s).*(사다리|비계|고소|천장|지붕|옥상|단부|개구부).*")) {
            axes.add(AccidentType.FALL);
        }
        if (text.matches("(?s).*(정비|점검|청소|컨베이어|롤러|회전|구동|프레스).*")) {
            axes.add(AccidentType.CAUGHT);
        }
        if (text.matches("(?s).*(인양|적재|하역|크레인|지게차|중량물).*")) {
            axes.add(AccidentType.DROP);
        }
        if (text.matches("(?s).*(지게차|차량|운반|대차|통로).*")) {
            axes.add(AccidentType.STRUCK);
        }
        if (text.matches("(?s).*(페인트|도장|용제|시너|용접|화기|가연).*")) {
            axes.add(AccidentType.FIRE);
        }
        // 보호구는 거의 모든 작업에 해당한다
        if (!axes.isEmpty()) {
            axes.add(AccidentType.PPE);
        }
        return axes;
    }

    private AccidentType parseAxis(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        for (AccidentType t : AccidentType.values()) {
            if (t.name().equals(v) || t.getLabel().equals(raw.trim())) {
                return t;
            }
        }
        return null;
    }

    private String nvl(String v) {
        return v == null ? "" : v;
    }

    private String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
