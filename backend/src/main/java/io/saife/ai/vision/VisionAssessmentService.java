package io.saife.ai.vision;

import io.saife.common.config.DemoModeConfig;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.common.service.SseService;
import io.saife.core.action.ActionDtos;
import io.saife.core.action.ActionService;
import io.saife.core.action.ActionSuggestionTable;
import io.saife.core.action.InspectionRecordStore;
import io.saife.core.action.InspectionRules;
import io.saife.core.domain.*;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.AssessmentRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.PhotoRiskTable;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 순회점검(근로자 참여, 시행규칙 제37조의2). 사진은 순회점검의 기록 수단이다.
 *
 * <p>흐름: 점검 정보(설비, 점검자, 참여 근로자) → 사진 → 위험요인 반영/제외 → 허용 가능 여부
 * → 개선대책(우선순위, 담당, 기한) → 이행 결과. 기록 항목은 시행규칙 제37조의4를 따른다.
 *
 * <p>순서가 규칙으로 정해져 있다({@code .claude/rules/sse-streaming.md}):
 * HTTP 스레드에서 평가 레코드를 <b>"분석 중"으로 먼저 커밋</b>하고, 그 다음에 비동기로 모델을 부른다.
 *
 * <p>만들어지는 것:
 * <ul>
 *   <li>{@code assessment} — kind=ROUTINE(상시, 순회점검), triggerType=PHOTO, 점검자와 참여 근로자</li>
 *   <li>{@code hazard} — {@code aiSuggested=true}, {@code aiAdopted=null} (반영/제외 판단 전)</li>
 *   <li>{@code assessment_hazard} — 등급은 {@link PhotoRiskTable}이 낸다. 모델이 아니다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisionAssessmentService {

    /** 사진 순회점검이 만드는 평가의 트리거. 설비 이력이 이 값으로 출처를 구분한다 */
    public static final String TRIGGER_PHOTO = "PHOTO";

    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_ANALYZED = "ANALYZED";
    public static final String STATUS_FAILED = "FAILED";

    /** {@code assess.progress} 단계. 화면의 진행 표시가 이 값으로 문구를 고른다 */
    public static final String PHASE_ANALYZING = "ANALYZING";
    public static final String PHASE_GRADING = "GRADING";
    public static final String PHASE_EVIDENCE = "EVIDENCE";

    /** 최근 점검 목록 길이 */
    private static final int RECENT_LIMIT = 6;

    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final HazardRepository hazardRepository;
    private final VisionAnalyzer visionAnalyzer;
    private final PhotoRiskTable photoRiskTable;
    private final SseService sseService;
    private final DemoModeConfig demoModeConfig;
    private final CandidateEvidenceCollector evidenceCollector;
    private final ActionService actionService;
    private final InspectionRecordStore records;
    private final EquipmentRepository equipmentRepository;

    // 자기 호출로 @Transactional(REQUIRES_NEW)를 태우려면 프록시를 거쳐야 한다.
    // 순환 의존성은 @Lazy가 아니라 ObjectProvider로 푼다 (CLAUDE.md 규칙)
    private final ObjectProvider<VisionAssessmentService> self;

    /**
     * 위험요인 한 건.
     *
     * @param adopted      반영 여부. <b>null이면 아직 판단 전</b>, false면 제외
     * @param alreadyKnown 이 설비에 이미 등록돼 있던 위험요인. 신규 발견이 아니라 재확인이다
     * @param acceptable   허용 가능 여부. 사람이 정하지 않았으면 등급 기본값(상, 중은 불가)
     * @param evidenceItems 근거 카드 최대 3장(지침 1, 조문 1, 사례 1). 영속화하지 않는다
     * @param suggestedAction 개선대책 초안({@link ActionSuggestionTable}, 모델 호출 없음)
     * @param action          이 점검에서 등록한 개선대책. 없으면 null
     * @param priorOpenAction 다른 평가에서 걸어 둔 미이행 조치
     */
    public record Candidate(Long hazardId, AccidentType accidentType, String accidentLabel,
                            String missingControl, String evidence, Double confidence,
                            RiskLevel riskLevel, String ruleTrace, Boolean adopted,
                            boolean alreadyKnown, GateStatus gateStatus, boolean acceptable,
                            List<Evidence> evidenceItems,
                            ActionDtos.SuggestedAction suggestedAction,
                            ActionDtos.ActionView action,
                            ActionDtos.ActionView priorOpenAction,
                            List<Integer> box) {}

    /**
     * @param inspector    담당자(점검자)
     * @param participants 참여 근로자 이름
     * @param equipmentId  점검 설비. 위험요인에서 파생한다(없으면 null)
     */
    public record AnalysisResult(Long assessmentId, String status, LocalDate assessedOn,
                                 String inspector, List<String> participants, Long equipmentId,
                                 List<Candidate> candidates, boolean demoMode) {}

    /** 점검 정보 갱신 요청과 응답 */
    public record InspectionRequest(String inspector, List<String> participants) {}

    public record InspectionView(Long assessmentId, String inspector, List<String> participants) {}

    /** 허용 가능 여부 변경 응답 */
    public record AcceptableView(Long hazardId, boolean acceptable) {}

    /**
     * 최근 순회점검 한 줄.
     *
     * @param equipmentNames 이 점검에서 반영한 위험요인의 설비들
     * @param hazardCount    반영한 위험요인 수(제외 빼고)
     * @param highCount      그중 상 등급 수
     * @param complete       기록 확정 여부({@link InspectionRules#complete})
     */
    public record RecentInspection(Long assessmentId, LocalDate assessedOn, String inspector,
                                   List<String> participants, List<String> equipmentNames,
                                   int hazardCount, int highCount, boolean complete) {}

    /**
     * 분석 중 상태와 점검 정보를 먼저 커밋한다. <b>비동기 디스패치 전에 호출한다.</b>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long markAnalyzing(Long siteId, String inspector, List<String> participants) {
        Assessment assessment = assessmentRepository.save(Assessment.builder()
                .siteId(siteId)
                .kind(AssessmentKind.ROUTINE)
                .triggerType(TRIGGER_PHOTO)
                .assessedOn(LocalDate.now())
                .participants(InspectionRules.joinParticipants(participants))
                .status(STATUS_ANALYZING)
                .build());
        records.saveInspection(assessment.getId(), blankToNull(inspector),
                InspectionRules.joinParticipants(participants));
        return assessment.getId();
    }

    /** 점검 정보(점검자, 참여 근로자)를 고친다. 사진을 올린 뒤에 참여 근로자를 적는 경우가 많다 */
    @Transactional
    public InspectionView updateInspection(Long assessmentId, InspectionRequest request) {
        assessmentRepository.findById(assessmentId).orElseThrow(
                () -> NotFoundException.of("점검", assessmentId));
        String inspector = request == null ? null : blankToNull(request.inspector());
        if (inspector != null && inspector.length() > 100) {
            throw new InvalidRequestException("점검자는 100자 이하로 입력하십시오.");
        }
        String participants = InspectionRules.joinParticipants(request == null ? null : request.participants());
        records.saveInspection(assessmentId, inspector, participants);
        return new InspectionView(assessmentId, inspector, InspectionRules.splitParticipants(participants));
    }

    /**
     * 분석을 수행하고 결과를 기록한다. 호출자가 별도 스레드에서 부른다.
     */
    public void analyze(Long assessmentId, Long siteId, Long equipmentId, Long processId,
                        byte[] imageBytes, String contentType, String photoPath,
                        String sessionId, String correlationId) {

        emit(sessionId, "assess.progress", correlationId, assessmentId,
                Map.of("phase", PHASE_ANALYZING, "message", "사진 분석 중"));

        try {
            List<VisionAnalyzer.Finding> findings = visionAnalyzer.analyze(imageBytes, contentType);

            AnalysisResult result = self.getObject().persist(
                    assessmentId, siteId, equipmentId, processId, findings, photoPath,
                    (phase, message) -> emit(sessionId, "assess.progress", correlationId, assessmentId,
                            Map.of("phase", phase, "message", message)));

            emit(sessionId, "assess.done", correlationId, assessmentId, result);
            log.info("[UC1] 점검 {} 분석 완료, 위험요인 {}건", assessmentId, result.candidates().size());

        } catch (Exception e) {
            log.error("[UC1] 점검 {} 분석 실패", assessmentId, e);
            self.getObject().markFailed(assessmentId);
            emit(sessionId, "assess.failed", correlationId, assessmentId,
                    Map.of("errorType", "INTERNAL", "message", "사진을 분석하지 못했습니다. 다시 올려 주십시오.",
                            "detail", String.valueOf(e.getMessage())));
        } finally {
            sseService.complete(sessionId);
        }
    }

    @Transactional
    public AnalysisResult persist(Long assessmentId, Long siteId, Long equipmentId, Long processId,
                                  List<VisionAnalyzer.Finding> findings, String photoPath) {
        return persist(assessmentId, siteId, equipmentId, processId, findings, photoPath, (p, m) -> {});
    }

    /** 진행 단계 알림. SSE {@code assess.progress}로 나간다 */
    @FunctionalInterface
    public interface ProgressSink {
        void phase(String phase, String message);
    }

    /**
     * 위험요인을 저장한다.
     *
     * <p>같은 설비에 <b>같은 위험요인</b>이 이미 있으면 새로 만들지 않고 재사용한다. 같은지는
     * {@link PhotoRiskTable#categoryOf}의 분류로 본다. 모델은 같은 장면을 "최상부 디딤대 사용"이라고도
     * "작업발판 미확보"라고도 쓴다. 문자열로 가르면 같은 설비에 같은 위험요인이 둘로 쪼개지고
     * 설비 이력과 미이행 조치 연결이 끊긴다.
     */
    @Transactional
    public AnalysisResult persist(Long assessmentId, Long siteId, Long equipmentId, Long processId,
                                  List<VisionAnalyzer.Finding> findings, String photoPath,
                                  ProgressSink progress) {

        progress.phase(PHASE_GRADING, "위험성 결정 중");

        // 같은 키의 위험요인이 여럿이면 먼저 등록된 것(설비 대장의 원래 항목)을 쓴다.
        // 그래야 그 위험요인에 걸린 미이행 조치와 등급 이력이 이어진다
        Map<String, Hazard> existing = new LinkedHashMap<>();
        if (equipmentId != null) {
            List<Hazard> registered = new ArrayList<>(hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(equipmentId));
            java.util.Collections.reverse(registered);
            for (Hazard h : registered) {
                // 제외된 사진 후보는 위험요인이 아니다. 재사용하지 않는다
                if (h.isAiSuggested() && Boolean.FALSE.equals(h.getAiAdopted())) {
                    continue;
                }
                existing.putIfAbsent(hazardKey(h.getAccidentType(), h.getMissingControl()), h);
            }
        }

        record Graded(VisionAnalyzer.Finding finding, Hazard hazard, boolean alreadyKnown,
                      RiskRuleEngine.Decision decision) {}
        List<Graded> graded = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (VisionAnalyzer.Finding finding : findings) {
            Hazard hazard = existing.get(hazardKey(finding.accidentType(), finding.missingControl()));
            boolean alreadyKnown = hazard != null;

            if (hazard == null) {
                hazard = hazardRepository.save(Hazard.builder()
                        .siteId(siteId)
                        .equipmentId(equipmentId)
                        .processId(processId)
                        .accidentType(finding.accidentType())
                        .missingControl(finding.missingControl())
                        .description(nvl(finding.evidence(), finding.missingControl()))
                        .source(HazardSource.PHOTO)
                        .photoPath(photoPath)
                        // 사진에서 올라온 후보다. 반영 여부는 사람이 정한다 → adopted는 null
                        .aiSuggested(true)
                        .build());
                existing.put(hazardKey(hazard.getAccidentType(), hazard.getMissingControl()), hazard);
            }
            // 한 사진에서 같은 위험요인이 두 번 나오면 한 번만 기록한다(평가-위험요인 유니크 제약)
            if (!seen.add(hazard.getId())) {
                continue;
            }

            RiskRuleEngine.Decision decision =
                    photoRiskTable.decide(finding.accidentType(), finding.missingControl());

            assessmentHazardRepository.save(AssessmentHazard.builder()
                    .assessmentId(assessmentId)
                    .hazardId(hazard.getId())
                    .frequency(decision.frequency())
                    .severity(decision.severity())
                    .riskLevel(decision.riskLevel())
                    .ruleTrace(decision.ruleTrace())
                    .photoBox(boxText(finding.box()))
                    .build());

            graded.add(new Graded(finding, hazard, alreadyKnown, decision));
        }

        if (!graded.isEmpty()) {
            progress.phase(PHASE_EVIDENCE, "근거 확인 중");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Graded g : graded) {
            Hazard h = g.hazard();
            VisionAnalyzer.Finding finding = g.finding();
            List<Evidence> evidenceItems =
                    evidenceCollector.forCandidate(h.getAccidentType(), h.getMissingControl());
            // 기존 위험요인이면 설비 대장의 이름을 그대로 쓴다. 판독 내용(evidence)은 오늘 사진의 서술이다
            candidates.add(new Candidate(h.getId(), h.getAccidentType(),
                    h.getAccidentType().getLabel(), h.getMissingControl(),
                    finding.evidence(), finding.confidence(),
                    g.decision().riskLevel(), g.decision().ruleTrace(), h.getAiAdopted(),
                    g.alreadyKnown(), GateStatus.of(h.getAccidentType()),
                    InspectionRules.defaultAcceptable(g.decision().riskLevel()), evidenceItems,
                    suggest(h.getAccidentType(), h.getMissingControl(), evidenceItems),
                    actionView(h, assessmentId),
                    priorOpenActionView(h, assessmentId),
                    finding.box()));
        }

        updateStatus(assessmentId, STATUS_ANALYZED);
        InspectionRecordStore.Inspection inspection = records.inspection(assessmentId).orElse(null);
        Assessment assessment = assessmentRepository.findById(assessmentId).orElse(null);
        return new AnalysisResult(assessmentId, STATUS_ANALYZED,
                assessment == null ? LocalDate.now() : assessment.getAssessedOn(),
                inspection == null ? null : inspection.inspector(),
                InspectionRules.splitParticipants(inspection == null ? null : inspection.participants()),
                equipmentId, candidates, demoModeConfig.isDemoMode());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long assessmentId) {
        updateStatus(assessmentId, STATUS_FAILED);
    }

    /**
     * 위험요인 반영/제외. 사람이 하는 일이다.
     */
    @Transactional
    public Candidate decideCandidate(Long hazardId, boolean adopt) {
        Hazard hazard = hazardRepository.findById(hazardId).orElseThrow(
                () -> new NotFoundException("위험요인을 찾을 수 없습니다: " + hazardId));

        if (adopt) {
            hazard.adopt();
        } else {
            hazard.reject();
        }
        hazardRepository.save(hazard);

        List<AssessmentHazard> graded = assessmentHazardRepository.findHistoryByHazardId(hazardId);
        AssessmentHazard latest = graded.isEmpty() ? null : graded.get(0);

        log.info("[UC1] 위험요인 {} {}", hazardId, adopt ? "반영" : "제외");
        List<Evidence> evidenceItems =
                evidenceCollector.forCandidate(hazard.getAccidentType(), hazard.getMissingControl());
        Long latestAssessmentId = latest == null ? null : latest.getAssessmentId();
        RiskLevel level = latest == null ? null : latest.getRiskLevel();
        Boolean stored = latestAssessmentId == null ? null
                : records.acceptableByHazard(latestAssessmentId).get(hazardId);
        return new Candidate(hazard.getId(), hazard.getAccidentType(),
                hazard.getAccidentType().getLabel(), hazard.getMissingControl(),
                hazard.getDescription(), null,
                level,
                latest == null ? null : latest.getRuleTrace(),
                hazard.getAiAdopted(), true, GateStatus.of(hazard.getAccidentType()),
                InspectionRules.acceptable(stored, level), evidenceItems,
                suggest(hazard.getAccidentType(), hazard.getMissingControl(), evidenceItems),
                actionView(hazard, latestAssessmentId),
                priorOpenActionView(hazard, latestAssessmentId),
                latest == null ? null : parseBox(latest.getPhotoBox()));
    }

    /** "ymin,xmin,ymax,xmax" 저장 문자열 */
    static String boxText(List<Integer> box) {
        return box == null || box.size() != 4 ? null
                : box.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    static List<Integer> parseBox(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            List<Integer> v = java.util.Arrays.stream(text.split(",")).map(String::trim).map(Integer::valueOf).toList();
            return v.size() == 4 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 허용 가능 여부를 사람이 정한다. 이 점검(평가)과 위험요인의 연결에 기록된다 */
    @Transactional
    public AcceptableView setAcceptable(Long assessmentId, Long hazardId, boolean acceptable) {
        boolean linked = assessmentHazardRepository.findByAssessmentId(assessmentId).stream()
                .anyMatch(l -> hazardId.equals(l.getHazardId()));
        if (!linked) {
            throw new InvalidRequestException("점검 %d에 이 위험요인(%d)이 없습니다.".formatted(assessmentId, hazardId));
        }
        records.saveAcceptable(assessmentId, hazardId, acceptable);
        return new AcceptableView(hazardId, acceptable);
    }

    /** 반영/제외 비율. 화면 지표가 아니라 문서와 검증 기록용이다 */
    @Transactional(readOnly = true)
    public Map<String, Object> adoptionRate(Long siteId) {
        List<AccidentType> gated = Arrays.stream(AccidentType.values())
                .filter(GateStatus::countsTowardAdoptionRate)
                .toList();

        long suggested = hazardRepository.countAiSuggestedIn(siteId, gated);
        long adopted = hazardRepository.countAiAdoptedIn(siteId, gated);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("suggested", suggested);
        m.put("adopted", adopted);
        m.put("rate", suggested == 0 ? null : (double) adopted / suggested);
        m.put("axes", gated.stream().map(AccidentType::getLabel).toList());
        m.put("note", "사진에서 올라온 위험요인 중 사람이 반영한 비율. 정확도가 아니다. "
                + "2026-09-21 사진 검증을 통과한 발생형태 " + gated.size() + "개만 센다.");
        return m;
    }

    @Transactional(readOnly = true)
    public AnalysisResult result(Long assessmentId) {
        Assessment assessment = assessmentRepository.findById(assessmentId).orElseThrow(
                () -> new NotFoundException("평가를 찾을 수 없습니다: " + assessmentId));

        Map<Long, Boolean> acceptable = records.acceptableByHazard(assessmentId);
        List<Candidate> candidates = new ArrayList<>();
        Long equipmentId = null;
        for (AssessmentHazard link : assessmentHazardRepository.findByAssessmentId(assessmentId)) {
            Hazard h = hazardRepository.findById(link.getHazardId()).orElse(null);
            if (h == null) {
                continue;
            }
            if (equipmentId == null) {
                equipmentId = h.getEquipmentId();
            }
            List<Evidence> evidenceItems =
                    evidenceCollector.forCandidate(h.getAccidentType(), h.getMissingControl());
            candidates.add(new Candidate(h.getId(), h.getAccidentType(), h.getAccidentType().getLabel(),
                    h.getMissingControl(), h.getDescription(), null,
                    link.getRiskLevel(), link.getRuleTrace(), h.getAiAdopted(),
                    h.getSource() != HazardSource.PHOTO,
                    GateStatus.of(h.getAccidentType()),
                    InspectionRules.acceptable(acceptable.get(h.getId()), link.getRiskLevel()),
                    evidenceItems,
                    suggest(h.getAccidentType(), h.getMissingControl(), evidenceItems),
                    actionView(h, assessmentId),
                    priorOpenActionView(h, assessmentId),
                    parseBox(link.getPhotoBox())));
        }
        InspectionRecordStore.Inspection inspection = records.inspection(assessmentId).orElse(null);
        return new AnalysisResult(assessmentId, assessment.getStatus(), assessment.getAssessedOn(),
                inspection == null ? null : inspection.inspector(),
                InspectionRules.splitParticipants(inspection == null ? assessment.getParticipants()
                        : inspection.participants()),
                equipmentId, candidates, demoModeConfig.isDemoMode());
    }

    /** 최근 순회점검(상시평가) 목록. 분석 중이거나 실패한 점검은 뺀다 */
    @Transactional(readOnly = true)
    public List<RecentInspection> recent(Long siteId) {
        List<RecentInspection> out = new ArrayList<>();
        for (Assessment a : assessmentRepository.findBySiteIdOrderByAssessedOnDesc(siteId)) {
            if (out.size() >= RECENT_LIMIT) {
                break;
            }
            if (a.getKind() != AssessmentKind.ROUTINE
                    || STATUS_ANALYZING.equals(a.getStatus()) || STATUS_FAILED.equals(a.getStatus())) {
                continue;
            }
            out.add(summarize(a));
        }
        // 같은 날짜면 나중에 만든 점검이 위로
        out.sort((x, y) -> {
            int byDate = y.assessedOn().compareTo(x.assessedOn());
            return byDate != 0 ? byDate : Long.compare(y.assessmentId(), x.assessmentId());
        });
        return out;
    }

    private RecentInspection summarize(Assessment a) {
        Map<Long, Boolean> acceptable = records.acceptableByHazard(a.getId());
        Set<String> equipmentNames = new LinkedHashSet<>();
        List<InspectionRules.Item> items = new ArrayList<>();
        int hazards = 0;
        int high = 0;
        for (AssessmentHazard link : assessmentHazardRepository.findByAssessmentId(a.getId())) {
            Hazard h = hazardRepository.findById(link.getHazardId()).orElse(null);
            if (h == null) {
                continue;
            }
            boolean excluded = h.isAiSuggested() && Boolean.FALSE.equals(h.getAiAdopted());
            boolean ok = InspectionRules.acceptable(acceptable.get(h.getId()), link.getRiskLevel());
            boolean hasAction = actionService.findFor(h.getId(), a.getId()).isPresent()
                    || actionService.findPriorOpen(h.getId(), a.getId())
                            .filter(p -> !InspectionRules.overdue(p.getDueDate())).isPresent();
            items.add(new InspectionRules.Item(h.isAiSuggested(), h.getAiAdopted(), ok, hasAction));
            if (excluded) {
                continue;
            }
            hazards++;
            if (link.getRiskLevel() == RiskLevel.HIGH) {
                high++;
            }
            if (h.getEquipmentId() != null) {
                equipmentRepository.findById(h.getEquipmentId()).map(Equipment::getName)
                        .ifPresent(equipmentNames::add);
            }
        }
        InspectionRecordStore.Inspection inspection = records.inspection(a.getId()).orElse(null);
        String participants = inspection == null ? a.getParticipants() : inspection.participants();
        boolean complete = "CONFIRMED".equals(a.getStatus()) || InspectionRules.complete(participants, items);
        return new RecentInspection(a.getId(), a.getAssessedOn(),
                inspection == null ? null : inspection.inspector(),
                InspectionRules.splitParticipants(participants),
                List.copyOf(equipmentNames), hazards, high, complete);
    }

    /**
     * 개선대책 초안. 지침 번호는 이 위험요인의 근거 카드 중 지침(GUIDE)에서 가져온다.
     */
    private ActionDtos.SuggestedAction suggest(AccidentType axis, String missingControl,
                                               List<Evidence> evidenceItems) {
        String guideRef = evidenceItems == null ? null : evidenceItems.stream()
                .filter(e -> e.kind() == EvidenceKind.GUIDE)
                .map(VisionAssessmentService::guideNo)
                .filter(g -> g != null && !g.isBlank())
                .findFirst()
                .orElse(null);
        return ActionDtos.SuggestedAction.of(
                ActionSuggestionTable.suggest(axis, missingControl, guideRef));
    }

    /** 지침 번호. 메타의 guideNo가 우선이고, 없으면 refKey("C-31-2017#c3")의 앞부분 */
    static String guideNo(Evidence e) {
        Object meta = e.meta() == null ? null : e.meta().get("guideNo");
        if (meta != null && !String.valueOf(meta).isBlank()) {
            return String.valueOf(meta);
        }
        String key = e.refKey();
        if (key == null) {
            return null;
        }
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    private ActionDtos.ActionView actionView(Hazard hazard, Long assessmentId) {
        return actionService.findFor(hazard.getId(), assessmentId)
                .map(a -> actionService.view(a, hazard.getEquipmentId()))
                .orElse(null);
    }

    private ActionDtos.ActionView priorOpenActionView(Hazard hazard, Long assessmentId) {
        return actionService.findPriorOpen(hazard.getId(), assessmentId)
                .map(a -> actionService.view(a, hazard.getEquipmentId()))
                .orElse(null);
    }

    private void updateStatus(Long assessmentId, String status) {
        assessmentRepository.findById(assessmentId).ifPresent(a ->
                assessmentRepository.save(Assessment.builder()
                        .id(a.getId())
                        .siteId(a.getSiteId())
                        .kind(a.getKind())
                        .triggerType(a.getTriggerType())
                        .triggerRefId(a.getTriggerRefId())
                        .assessedOn(a.getAssessedOn())
                        .participants(a.getParticipants())
                        .status(status)
                        .createdAt(a.getCreatedAt())
                        .build()));
    }

    /**
     * 같은 위험요인인지 가르는 키. 분류표의 구체 항목에 걸리면 분류 코드로, 아니면(축 기본값) 공백과
     * 구분자를 뺀 문구로 가른다. 시드의 "유도 표식·구획선"과 판독 결과 "유도 표식, 구획선"이 같아야 한다.
     */
    private String hazardKey(AccidentType axis, String missingControl) {
        String category = photoRiskTable.categoryOf(axis, missingControl);
        if (category != null && !category.endsWith("_OTHER") && !category.endsWith("_UNKNOWN")
                && !category.endsWith("_DEFAULT")) {
            return axis + "#" + category;
        }
        return axis + "|" + (missingControl == null ? "" : missingControl.replaceAll("[\\s·,]+", ""));
    }

    private void emit(String sessionId, String type, String correlationId,
                      Long targetId, Object payload) {
        sseService.send(sessionId, SseService.SseEvent.of(
                type, correlationId, targetId == null ? null : String.valueOf(targetId), payload));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
