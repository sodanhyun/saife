package io.saife.workplan.service;

import io.saife.core.domain.*;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanSlot;
import io.saife.workplan.repository.WorkPlanSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 작업 전 위험 브리핑 생성기.
 *
 * <p><b>이 브리핑이 TBM의 디지털 구현체다.</b> TBM의 실질은 "작업 전 5~10분간 그날\n* 작업의 위험을 공유하는 것"이고, 이 카드가 정확히 그 일을 한다. 게다가 작업자가
 * 확인하면 {@code work_plan.briefing_ack_at}에 시각이 남아 구두 TBM보다 증빙이 강하다.
 *
 * <p>상시평가 트랙(월1회 순회점검 + 주1회 논의 + <b>매 작업일 TBM</b>)의 세 번째
 * 요건이 여기서 충족된다.
 *
 * <p>브리핑의 위험요인은 <b>그 설비의 데이터 코어에서 나온다.</b> 범용 챗봇이
 * 만들 수 없는 문장이 여기 있다 — "3개월 전 평가에서 '상'이었고 아직 미이행입니다".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BriefingComposer {

    private static final List<String> MSDS_SECTIONS_FOR_BRIEFING = List.of("02", "05", "07", "08");

    private final EquipmentRepository equipmentRepository;
    private final HazardRepository hazardRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final WorkPlanSlotRepository workPlanSlotRepository;
    private final MsdsCacheRepository msdsCacheRepository;
    private final MsdsResolver msdsResolver;
    private final RiskRuleEngine riskRuleEngine;
    private final EvidenceSearchService evidenceSearchService;
    private final EvidenceLedger evidenceLedger;

    @Transactional(readOnly = true)
    public String compose(WorkPlan plan) {
        StringBuilder sb = new StringBuilder();

        sb.append("작업: %s / %s / %s\n".formatted(
                plan.getWorkName(),
                nvl(plan.getWorkPlace(), "장소 미지정"),
                plan.getWorkDate()));

        Map<String, String> slots = loadSlots(plan.getId());

        List<Hazard> hazards = plan.getEquipmentId() != null
                ? hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(plan.getEquipmentId())
                : List.of();

        appendLedgerWarnings(sb, hazards);
        appendRuleDecisions(sb, hazards, slots);
        appendMsds(sb, slots.get(RiskRuleEngine.SlotKeys.PRODUCT_NAME));
        appendSimilarCases(sb, plan, hazards);
        appendEquipmentLine(sb, plan.getEquipmentId());

        sb.append("\n작업 전 위 내용을 확인하고 이상이 있으면 작업을 중지하십시오.\n");
        return sb.toString();
    }

    /**
     * 대장 기록에서 나오는 경고 — 에이전트가 <b>묻기도 전에 먼저 말하는</b> 부분.
     * 이게 이 출품작의 핵심 대사다.
     */
    private void appendLedgerWarnings(StringBuilder sb, List<Hazard> hazards) {
        if (hazards.isEmpty()) {
            return;
        }
        List<Long> ids = hazards.stream().map(Hazard::getId).toList();
        List<Action> pending = actionRepository.findPendingByHazardIds(ids, ActionStatus.DONE);
        if (pending.isEmpty()) {
            return;
        }

        sb.append("\n이 설비에 미이행 조치가 있습니다\n");
        for (Action a : pending) {
            String grade = gradeOf(a.getHazardId());
            sb.append("- %s%s (기한 %s)\n".formatted(
                    a.getContent(), grade,
                    a.getDueDate() != null ? a.getDueDate().toString() : "미지정"));
        }
    }

    /** 룰 엔진 판정. 되묻기 턴의 답이 등급을 바꾸는 것이 여기서 보인다 */
    private void appendRuleDecisions(StringBuilder sb, List<Hazard> hazards, Map<String, String> slots) {
        Set<AccidentType> axes = new LinkedHashSet<>();
        hazards.forEach(h -> axes.add(h.getAccidentType()));

        // 슬롯으로 추가 판정이 가능한 축을 보충한다
        if (slots.containsKey(RiskRuleEngine.SlotKeys.WORK_HEIGHT)) {
            axes.add(AccidentType.FALL);
        }
        if (slots.containsKey(RiskRuleEngine.SlotKeys.PRODUCT_NAME)) {
            axes.add(AccidentType.FIRE);
        }
        if (axes.isEmpty()) {
            return;
        }

        sb.append("\n[위험요인 및 등급]\n");
        for (AccidentType axis : axes) {
            RiskRuleEngine.Decision d = riskRuleEngine.decide(axis, slots);
            sb.append("- %s: 위험성 '%s'\n  근거: %s\n".formatted(
                    axis.getLabel(), d.riskLevel().getLabel(), d.ruleTrace()));
        }
    }

    /** MSDS — 일반론이 아니라 법정 노출기준과 H코드를 인용한다 */
    private void appendMsds(StringBuilder sb, String productName) {
        if (productName == null || productName.isBlank()) {
            return;
        }
        // 해석은 MsdsResolver 하나로 모은다. 예전에 도구에만 넣었더니 도구는 톨루엔을 찾는데
        // 브리핑은 "MSDS 없음"을 찍었다 (2026-09-20 스모크 테스트).
        String chemId = msdsResolver.resolveChemId(productName);
        if (chemId == null) {
            sb.append("\n[화학물질] '%s'의 MSDS가 캐시에 없습니다. 물질안전보건자료를 확인하십시오.\n"
                    .formatted(productName));
            return;
        }

        List<MsdsCache> rows = msdsCacheRepository
                .findByChemIdAndSectionCodeIn(chemId, MSDS_SECTIONS_FOR_BRIEFING);
        if (rows.isEmpty()) {
            return;
        }

        sb.append("\n[화학물질 — %s]\n".formatted(nvl(rows.get(0).getChemNameKor(), productName)));
        for (MsdsCache row : rows) {
            if (row.getItemDetail() == null || row.getItemDetail().isBlank()) {
                continue;
            }
            // itemDetail은 '|'가 줄 구분자다
            String first = row.getItemDetail().split("\\|")[0].trim();
            if (first.isBlank()) {
                continue;
            }
            sb.append("- %s: %s\n".formatted(nvl(row.getItemName(), row.getSectionCode()), first));
        }
    }

    /**
     * 유사 사고사례 — 근거 검색 파이프라인(임베딩+키워드+리랭크)에서. 무대에서 외부 호출 0.
     *
     * <p>질의는 <b>발생형태+설비+작업</b>으로 만든다. 재해 원문에는 사업장 위치가 없으므로
     * 위치 태그를 넣으면 매칭이 안 된다(ruling R51, {@code HazardAnalysisTools.searchCases}와 동일 규칙).
     *
     * <p>같은 대화에서 이미 {@code searchCases} 등으로 원장에 등록된 카드면 <b>#n</b>을 병기한다 —
     * 작업자가 브리핑에서 본 사례와 대화 중 인용된 근거가 같은 것임을 알 수 있다. 원장에 없으면
     * (이 턴에 처음 조회된 경우) 번호 없이 나간다.
     */
    private void appendSimilarCases(StringBuilder sb, WorkPlan plan, List<Hazard> hazards) {
        Set<AccidentType> axes = new LinkedHashSet<>();
        hazards.forEach(h -> axes.add(h.getAccidentType()));
        if (axes.isEmpty()) {
            return;
        }

        String equipmentName = plan.getEquipmentId() == null ? null
                : equipmentRepository.findById(plan.getEquipmentId()).map(Equipment::getName).orElse(null);

        List<Evidence> found = new ArrayList<>();
        for (AccidentType axis : axes) {
            String query = buildCaseQuery(axis, equipmentName, plan.getWorkName());
            List<Evidence> hits = safeSearch(SearchRequest.cases(query, axis, "제조업", 2).withoutRerank());
            if (hits.isEmpty()) {
                hits = safeSearch(SearchRequest.cases(query, axis, null, 2).withoutRerank());
            }
            found.addAll(hits);
            if (found.size() >= 2) {
                break;
            }
        }
        if (found.isEmpty()) {
            return;
        }

        Map<String, Integer> ledgerNos = ledgerNumbersByIdentity(plan.getConversationId());

        sb.append("\n[유사 사고사례]\n");
        found.stream().limit(2).forEach(e -> {
            Integer no = ledgerNos.get(e.identity());
            sb.append("- ");
            // 프론트 인용 칩(CitationChip)은 "[#n]" 형태만 칩으로 그린다. 맨 "#n"으로 쓰면 데모 모드(고정
            // 스크립트가 이 브리핑을 그대로 답변에 싣는 경로)에서 클릭할 칩이 하나도 없다(2026-09-30 워크스루 실측).
            if (no != null) {
                sb.append("[#").append(no).append("] ");
            }
            if (e.mediaUrl() != null) {
                sb.append("[사진] ");
            }
            sb.append(e.title()).append('\n');
        });
    }

    /**
     * 검색 실패가 계획서 제출 실패가 되면 안 된다(ruling R53) — 빈 목록으로 흡수한다.
     *
     * <p>{@code compose()}는 {@code createWorkPlan}의 {@code @Transactional} 흐름 안에서
     * {@code plan.submit()}/{@code save()}보다 <b>먼저</b> 실행된다. 여기서 예외가 새면
     * 계획서 제출 전체가 롤백된다 — 유사 사례 하나 못 찾았다고 작업계획서 자체가 날아가면 안 된다.
     * {@code HazardAnalysisTools.safeSearch}와 동일한 방어.
     */
    private List<Evidence> safeSearch(SearchRequest req) {
        try {
            return evidenceSearchService.search(req);
        } catch (Exception e) {
            log.warn("[BRIEFING] 유사 사례 검색 실패 query={}: {}", req.query(), e.getMessage());
            return List.of();
        }
    }

    /** 발생형태 라벨 + 설비명 + 작업명. 위치 태그는 절대 넣지 않는다(ruling R51) */
    private String buildCaseQuery(AccidentType axis, String equipmentName, String workName) {
        StringBuilder qb = new StringBuilder(axis.getLabel());
        if (equipmentName != null && !equipmentName.isBlank()) {
            qb.append(' ').append(equipmentName);
        }
        if (workName != null && !workName.isBlank()) {
            qb.append(' ').append(workName);
        }
        return qb.toString();
    }

    /** 대화 원장에 이미 등록된 카드의 (kind:refKey) → 번호. 대화 ID가 없으면(테스트·수동 호출) 빈 맵 */
    private Map<String, Integer> ledgerNumbersByIdentity(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> map = new HashMap<>();
        for (Evidence e : evidenceLedger.all(conversationId)) {
            map.put(e.identity(), e.no());
        }
        return map;
    }

    private void appendEquipmentLine(StringBuilder sb, Long equipmentId) {
        if (equipmentId == null) {
            return;
        }
        equipmentRepository.findById(equipmentId).ifPresent(eq ->
                sb.append("\n대상 설비: %s (%s)\n".formatted(eq.getName(), nvl(eq.getLocationTag(), "위치 미지정"))));
    }

    private String gradeOf(Long hazardId) {
        List<AssessmentHazard> history = assessmentHazardRepository.findHistoryByHazardId(hazardId);
        if (history.isEmpty()) {
            return "";
        }
        return " — 최근 평가 '%s'".formatted(history.get(0).getRiskLevel().getLabel());
    }

    private Map<String, String> loadSlots(Long workPlanId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (WorkPlanSlot s : workPlanSlotRepository.findByWorkPlanId(workPlanId)) {
            if (s.getAnsweredValue() != null) {
                map.put(s.getSlotKey(), s.getAnsweredValue());
            }
        }
        return map;
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
