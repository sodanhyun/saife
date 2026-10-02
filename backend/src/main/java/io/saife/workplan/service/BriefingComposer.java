package io.saife.workplan.service;

import io.saife.core.domain.*;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.evidence.Evidence;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.dto.WorkPlanDtos;
import io.saife.workplan.repository.WorkPlanWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 작업 전 TBM 문안 생성기.
 *
 * <p>법 제36조제4항(2026.6.1 시행)은 위험성평가 결과 중 중대재해로 이어질 수 있는 요인을 작업 전
 * 안전점검회의(TBM) 등으로 근로자에게 상시 주지하라고 정한다. 이 문안이 그 회의에서 반장이 읽는 내용이고,
 * 작업자 확인 시각은 {@code work_plan.briefing_ack_at}에 남는다(TBM 실시 기록).
 *
 * <p>구성은 고정이다: 위험 포인트 3개 이내, 지킬 것 3개 이내, 마지막 줄 "위험하면 작업을 멈추고
 * 관리감독자에게 알립니다". 내용은 모델이 아니라 판정 결과({@link BriefingViewBuilder})에서 나온다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BriefingComposer {

    static final String STOP_LINE = "위험하면 작업을 멈추고 관리감독자에게 알립니다.";

    private final EquipmentRepository equipmentRepository;
    private final HazardRepository hazardRepository;
    private final WorkPlanWorkerRepository workPlanWorkerRepository;
    private final BriefingViewBuilder briefingViewBuilder;
    private final EvidenceSearchService evidenceSearchService;
    private final EvidenceLedger evidenceLedger;

    @Transactional(readOnly = true)
    public String compose(WorkPlan plan) {
        StringBuilder sb = new StringBuilder();

        sb.append("작업: %s / %s / %s\n".formatted(
                plan.getWorkName(),
                nvl(plan.getWorkPlace(), "장소 미지정"),
                plan.getWorkDate()));
        String workers = workPlanWorkerRepository.findByWorkPlanId(plan.getId()).stream()
                .map(w -> w.getPosition() == null || w.getPosition().isBlank() ? w.getName() : w.getName() + " " + w.getPosition())
                .collect(Collectors.joining(", "));
        if (!workers.isBlank()) {
            sb.append("작업자: ").append(workers).append('\n');
        }
        appendEquipmentLine(sb, plan.getEquipmentId());

        WorkPlanDtos.BriefingView view = briefingViewBuilder.compute(plan);
        appendNumbered(sb, "위험 포인트", view.riskPoints());
        appendNumbered(sb, "지킬 것", view.keepPoints());
        appendMsds(sb, view.msds());
        appendPending(sb, view.pendingActions());

        List<Hazard> hazards = plan.getEquipmentId() != null
                ? hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(plan.getEquipmentId())
                : List.of();
        appendSimilarCases(sb, plan, hazards);

        sb.append('\n').append(STOP_LINE).append('\n');
        return sb.toString();
    }

    private void appendNumbered(StringBuilder sb, String title, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append('\n');
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        }
    }

    /** 주성분을 추정했으면 그렇다고 밝힌다. MSDS는 제품별 법정 문서이고 성분은 제품마다 다르다 */
    private void appendMsds(StringBuilder sb, WorkPlanDtos.MsdsSummary msds) {
        if (msds == null) {
            return;
        }
        sb.append("\n화학물질: ").append(msds.productName());
        if (msds.inferred()) {
            sb.append(", 추정 주성분 ").append(msds.chemName()).append(", 제품 MSDS 확인 필요");
        }
        msds.lines().stream().filter(l -> "유해성".equals(l.item())).findFirst()
                .ifPresent(l -> sb.append(", 유해성 ").append(l.text()));
        sb.append('\n');
    }

    private void appendPending(StringBuilder sb, List<WorkPlanDtos.PendingAction> pending) {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (WorkPlanDtos.PendingAction a : pending) {
            sb.append("미이행 조치: ").append(a.content());
            // 저장되는 문안이라 출력 시점에 따라 바뀌는 "n일 경과" 대신 기한 날짜를 쓴다
            if (a.dueDate() != null) {
                sb.append(" (기한 ").append(a.dueDate()).append(')');
            }
            sb.append('\n');
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

        sb.append("\n유사 재해사례\n");
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
        StringBuilder qb = new StringBuilder(axis.getSearchTerm());
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
                sb.append("대상 설비: %s (%s)\n".formatted(eq.getName(), nvl(eq.getLocationTag(), "위치 미지정"))));
    }



    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
