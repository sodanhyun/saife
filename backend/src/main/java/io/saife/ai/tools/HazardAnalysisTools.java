package io.saife.ai.tools;

import io.saife.common.service.SseService;
import io.saife.core.domain.AccidentType;
import io.saife.publicapi.domain.KoshaGuide;
import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.domain.PublicCase;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import io.saife.publicapi.repository.PublicCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 도구 3·4·5 — 위험요인 도출, 사례 매칭, MSDS 조회.
 *
 * <p>셋 다 <b>로컬 캐시</b>에 대해 실행된다. 무대에서 외부 공공 API를 호출하지 않는다.
 * 그래서 트레이스 패널의 지연과 결과가 진짜이면서도 네트워크에 의존하지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HazardAnalysisTools {

    /** UC3 브리핑이 쓰는 MSDS 4개 항목 — 16개를 다 받지 않는다 */
    private static final List<String> BRIEFING_SECTIONS = List.of("02", "05", "07", "08");

    private final PublicCaseRepository publicCaseRepository;
    private final KoshaGuideRepository koshaGuideRepository;
    private final MsdsCacheRepository msdsCacheRepository;
    private final MsdsResolver msdsResolver;
    private final SseService sseService;

    @Tool(description = """
            <tool-description>
            <purpose>작업 유형·장소·설비·취급물질 조합으로 발생형태별 위험요인을 도출하고, 관련 KOSHA GUIDE 기술지침을 함께 반환합니다.</purpose>
            <returns>해당 작업에서 예상되는 발생형태 목록과 각 축의 탐지 대상(빠진 안전조치), 관련 기술지침 번호를 반환합니다. 위험성 등급은 여기서 정하지 않습니다 — 등급은 되묻기 답변을 받은 뒤 룰 엔진이 결정합니다.</returns>
            <prerequisites>findLocationEquipment로 장소를 확인한 뒤 호출하세요.</prerequisites>
            </tool-description>
            """)
    public String analyzeHazards(
            @ToolParam(description = "작업 유형 (예: 도장, 용접, 정비, 청소)") String workType,
            @ToolParam(description = "작업 장소 설명", required = false) String location,
            @ToolParam(description = "사용 설비 (예: 이동식 사다리, 고소작업대)", required = false) String equipment,
            @ToolParam(description = "취급 물질 (예: 유성페인트, 시너)", required = false) String material,
            ToolContext toolContext) {

        return ToolCallTracker.execute("analyzeHazards",
                Map.of("workType", String.valueOf(workType), "equipment", String.valueOf(equipment)),
                sseService, toolContext, () -> {

            String combined = String.join(" ",
                    nvl(workType), nvl(location), nvl(equipment), nvl(material));

            Set<AccidentType> axes = deriveAxes(combined);
            if (axes.isEmpty()) {
                return ToolResult.of("작업 정보가 부족해 위험요인을 도출할 수 없습니다. "
                        + "작업 유형과 사용 설비를 물어보세요.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("도출된 발생형태 ").append(axes.size()).append("종:\n");
            for (AccidentType axis : axes) {
                sb.append("- ").append(axis.getLabel()).append(": ")
                        .append(axis.getMissingControlHint()).append("\n");
            }

            List<KoshaGuide> guides = findGuides(combined);
            if (!guides.isEmpty()) {
                sb.append("\n관련 기술지침:\n");
                for (KoshaGuide g : guides) {
                    sb.append("- [").append(g.getGuideNo()).append("] ")
                            .append(g.getGuideName()).append("\n");
                }
            }

            sb.append("\n※ 위험성 등급은 작업높이·안전대 부착설비 등 현장 확인 항목을 ")
                    .append("받은 뒤 룰 엔진이 결정합니다.\n");
            return ToolResult.of(sb.toString());
        });
    }

    @Tool(description = """
            <tool-description>
            <purpose>유사한 실제 사고사례를 공단 데이터에서 찾아 근거로 첨부합니다.</purpose>
            <returns>업종·발생형태가 일치하는 사고사례를 최대 3건 반환합니다. 각 사례는 한 줄 요약과 업종을 포함합니다.</returns>
            <prerequisites>analyzeHazards로 발생형태를 도출한 뒤 호출하면 더 정확합니다.</prerequisites>
            </tool-description>
            """)
    public String searchCases(
            @ToolParam(description = "발생형태 코드 (FALL 추락 / CAUGHT 협착 / DROP 낙하 / STRUCK 부딪힘 / FIRE 화재 / PPE 보호구)")
            String accidentType,
            @ToolParam(description = "업종 필터 (예: 제조업, 건설업, 조선업). 모르면 생략", required = false)
            String business,
            ToolContext toolContext) {

        return ToolCallTracker.execute("searchCases",
                Map.of("accidentType", String.valueOf(accidentType), "business", String.valueOf(business)),
                sseService, toolContext, () -> {

            AccidentType axis = parseAxis(accidentType);
            if (axis == null) {
                return ToolResult.of("발생형태를 알 수 없습니다. "
                        + "FALL/CAUGHT/DROP/STRUCK/FIRE/PPE 중 하나로 지정하세요.");
            }

            // 업종 필터를 먼저 건다 — 제조업을 찾는데 건설 사례를 올리면 근거가 약하다
            List<PublicCase> cases = publicCaseRepository.search(axis, blankToNull(business));
            if (cases.isEmpty()) {
                cases = publicCaseRepository.search(axis, null);
            }
            if (cases.isEmpty()) {
                return ToolResult.of("해당 발생형태의 캐시된 사고사례가 없습니다.");
            }

            StringBuilder sb = new StringBuilder("유사 사고사례:\n");
            cases.stream().limit(3).forEach(c -> {
                sb.append("- ").append(nvl2(c.getKeyword(), c.getContents()));
                if (c.getBusiness() != null) {
                    sb.append(" (").append(c.getBusiness()).append(")");
                }
                sb.append("\n");
            });
            return ToolResult.of(sb.toString());
        });
    }

    @Tool(description = """
            <tool-description>
            <purpose>취급 화학물질의 유해성·폭발화재 위험·취급저장 주의사항·노출기준과 보호구를 조회합니다.</purpose>
            <returns>MSDS 4개 항목(유해성, 폭발·화재 대처, 취급·저장, 노출방지·개인보호구)의 핵심 내용을 반환합니다. 법정 노출기준(TWA/STEL)과 H코드를 포함합니다.</returns>
            <prerequisites>작업자에게 제품명을 먼저 확인하세요. 제품명을 모르면 이 도구를 호출하지 말고 물어보세요.</prerequisites>
            <usage-guide>
            - 제품명으로 못 찾으면 주요 성분명(예: 톨루엔, 크실렌)으로 다시 시도하세요.
            - 그래도 없으면 작업자에게 제품 용기 라벨이나 MSDS 문서를 확인하도록 안내하세요.
            </usage-guide>
            </tool-description>
            """)
    public String getMsds(
            @ToolParam(description = "제품명 또는 물질명 (예: 톨루엔, 유성페인트, 시너)") String productName,
            ToolContext toolContext) {

        return ToolCallTracker.execute("getMsds",
                Map.of("productName", String.valueOf(productName)),
                sseService, toolContext, () -> {

            if (productName == null || productName.isBlank()) {
                return ToolResult.of("제품명이 없습니다. 작업자에게 사용 제품명을 물어보세요.");
            }

            String chemId = msdsResolver.resolveChemId(productName.trim());
            if (chemId == null) {
                return ToolResult.of("'" + productName + "'의 MSDS가 캐시에 없습니다. "
                        + "주요 성분명으로 다시 시도하거나, 작업자에게 제품 용기 라벨을 "
                        + "확인하도록 안내하세요.");
            }

            List<MsdsCache> rows = msdsCacheRepository
                    .findByChemIdAndSectionCodeIn(chemId, BRIEFING_SECTIONS);
            if (rows.isEmpty()) {
                return ToolResult.of("'" + productName + "' 관련 MSDS 항목이 비어 있습니다.");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[").append(nvl2(rows.get(0).getChemNameKor(), productName)).append("] MSDS\n");
            for (MsdsCache row : rows) {
                if (row.getItemDetail() == null || row.getItemDetail().isBlank()) {
                    continue;
                }
                sb.append("\n").append(nvl2(row.getItemName(), "항목 " + row.getSectionCode())).append("\n");
                // itemDetail은 '|'가 줄 구분자다
                for (String part : row.getItemDetail().split("\\|")) {
                    if (!part.isBlank()) {
                        sb.append("  - ").append(part.trim()).append("\n");
                    }
                }
            }
            return ToolResult.of(sb.toString());
        });
    }

    /**
     * 제품명 → 물질 ID.
     *
     * <p>현장은 "○○ 유성페인트"처럼 상품명으로 말하는데 MSDS는 물질명으로 등재되어 있다.
     * 직접 조회가 실패하면 흔한 상품 분류를 주성분으로 치환해 다시 찾는다.
     *
     * <p>임시 매핑이다. 실제로는 제품 → 성분 테이블이 필요하고, 공단 MSDS에도
     * 제품별 자료가 따로 있다. 지금은 시연에 필요한 만큼만 덮는다.
     */

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

    private List<KoshaGuide> findGuides(String text) {
        List<KoshaGuide> out = new ArrayList<>();
        for (String kw : List.of("사다리", "도장", "용접", "고소", "방호")) {
            if (text.contains(kw)) {
                out.addAll(koshaGuideRepository.searchByName(kw));
            }
        }
        return out.stream().distinct().limit(3).toList();
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

    private String nvl2(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
