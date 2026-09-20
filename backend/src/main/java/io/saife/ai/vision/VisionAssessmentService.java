package io.saife.ai.vision;

import io.saife.common.config.DemoModeConfig;
import io.saife.common.service.SseService;
import io.saife.core.domain.*;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.AssessmentRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.core.service.PhotoRiskTable;
import io.saife.core.service.RiskRuleEngine;
import io.saife.common.error.ApiExceptions.NotFoundException;
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
import java.util.List;
import java.util.Map;

/**
 * UC1 — 사진 판독으로 위험요인 후보를 만든다.
 *
 * <p>순서가 규칙으로 정해져 있다({@code .claude/rules/sse-streaming.md}):
 * HTTP 스레드에서 평가 레코드를 <b>"판독 중"으로 먼저 커밋</b>하고,
 * 그 다음에 비동기로 모델을 부른다. 선커밋을 빼면 새로고침했을 때 진행 중이라는
 * 사실이 어디에도 없어 화면이 복원되지 않는다.
 *
 * <p>만들어지는 것:
 * <ul>
 *   <li>{@code assessment} — kind=ROUTINE(상시·순회점검), triggerType=PHOTO</li>
 *   <li>{@code hazard} — {@code aiSuggested=true}, {@code aiAdopted=null} (아직 판단 전)</li>
 *   <li>{@code assessment_hazard} — 등급은 {@link PhotoRiskTable}이 낸다. 모델이 아니다</li>
 * </ul>
 *
 * <p>{@code aiAdopted}가 null로 남는 게 핵심이다. <b>사람이 채택해야 값이 생긴다.</b>
 * 성과 지표(후보 채택률)의 분모와 분자가 여기서 나온다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisionAssessmentService {

    /** 사진 판독이 만드는 평가의 트리거. UC4 타임라인이 이 값으로 출처를 구분한다 */
    public static final String TRIGGER_PHOTO = "PHOTO";

    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_ANALYZED = "ANALYZED";
    public static final String STATUS_FAILED = "FAILED";

    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final HazardRepository hazardRepository;
    private final VisionAnalyzer visionAnalyzer;
    private final PhotoRiskTable photoRiskTable;
    private final SseService sseService;
    private final DemoModeConfig demoModeConfig;

    // 자기 호출로 @Transactional(REQUIRES_NEW)를 태우려면 프록시를 거쳐야 한다.
    // 순환 의존성은 @Lazy가 아니라 ObjectProvider로 푼다 (CLAUDE.md 규칙)
    private final ObjectProvider<VisionAssessmentService> self;

    /**
     * @param adopted      사람의 채택 여부. <b>null이면 아직 판단 전</b>
     * @param alreadyKnown 이 설비에 이미 등록돼 있던 위험요인인가.
     *                     참이면 신규 발견이 아니라 <b>재확인</b>이다 — 화면에서 구분해야 한다.
     *                     "이미 아는 위험을 사진이 또 잡았다"와 "새 위험을 찾았다"는
     *                     심사에서 의미가 완전히 다르다
     */
    public record Candidate(Long hazardId, AccidentType accidentType, String accidentLabel,
                            String missingControl, String evidence, Double confidence,
                            RiskLevel riskLevel, String ruleTrace, Boolean adopted,
                            boolean alreadyKnown, GateStatus gateStatus, String gateNote) {}

    public record AnalysisResult(Long assessmentId, String status,
                                 List<Candidate> candidates, boolean demoMode) {}

    /**
     * 판독 중 상태를 먼저 커밋한다. <b>비동기 디스패치 전에 호출한다.</b>
     *
     * <p>REQUIRES_NEW라 호출자 트랜잭션과 무관하게 즉시 보인다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long markAnalyzing(Long siteId) {
        Assessment assessment = assessmentRepository.save(Assessment.builder()
                .siteId(siteId)
                .kind(AssessmentKind.ROUTINE)
                .triggerType(TRIGGER_PHOTO)
                .assessedOn(LocalDate.now())
                .participants("(사진 판독 — 점검자를 입력한 뒤 확정하십시오)")
                .status(STATUS_ANALYZING)
                .build());
        return assessment.getId();
    }

    /**
     * 판독을 수행하고 결과를 기록한다. 호출자가 별도 스레드에서 부른다.
     *
     * @param sessionId SSE 세션. 진행 상황이 여기로 나간다
     */
    public void analyze(Long assessmentId, Long siteId, Long equipmentId, Long processId,
                        byte[] imageBytes, String contentType, String photoPath,
                        String sessionId, String correlationId) {

        emit(sessionId, "assess.progress", correlationId, assessmentId,
                Map.of("phase", "ANALYZING", "message", "사진을 판독하고 있습니다"));

        try {
            List<VisionAnalyzer.Finding> findings = visionAnalyzer.analyze(imageBytes, contentType);

            AnalysisResult result = self.getObject().persist(
                    assessmentId, siteId, equipmentId, processId, findings, photoPath);

            emit(sessionId, "assess.done", correlationId, assessmentId, result);
            log.info("[UC1] 평가 {} 판독 완료 — 후보 {}건", assessmentId, result.candidates().size());

        } catch (Exception e) {
            log.error("[UC1] 평가 {} 판독 실패", assessmentId, e);
            self.getObject().markFailed(assessmentId);
            emit(sessionId, "assess.failed", correlationId, assessmentId,
                    Map.of("errorType", "INTERNAL", "message", "사진 판독에 실패했습니다",
                            "detail", String.valueOf(e.getMessage())));
        } finally {
            sseService.complete(sessionId);
        }
    }

    /**
     * 후보를 저장한다.
     *
     * <p>같은 설비에 <b>같은 축·같은 빠진 조치</b>가 이미 있으면 새로 만들지 않고 재사용한다.
     * 사진을 두 번 올렸다고 위험요인이 두 개가 되면 설비 이력이 부풀고,
     * UC2의 소환과 UC4 타임라인이 중복으로 더러워진다.
     */
    @Transactional
    public AnalysisResult persist(Long assessmentId, Long siteId, Long equipmentId, Long processId,
                                  List<VisionAnalyzer.Finding> findings, String photoPath) {

        Map<String, Hazard> existing = new LinkedHashMap<>();
        if (equipmentId != null) {
            for (Hazard h : hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(equipmentId)) {
                existing.putIfAbsent(hazardKey(h.getAccidentType(), h.getMissingControl()), h);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
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
                        // 모델이 제안한 것이다. 채택 여부는 사람이 정한다 → adopted는 null
                        .aiSuggested(true)
                        .build());
                existing.put(hazardKey(hazard.getAccidentType(), hazard.getMissingControl()), hazard);
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
                    .build());

            candidates.add(new Candidate(hazard.getId(), finding.accidentType(),
                    finding.accidentType().getLabel(), finding.missingControl(),
                    finding.evidence(), finding.confidence(),
                    decision.riskLevel(), decision.ruleTrace(), hazard.getAiAdopted(),
                    alreadyKnown, GateStatus.of(finding.accidentType()),
                    gateNote(finding.accidentType())));
        }

        updateStatus(assessmentId, STATUS_ANALYZED);
        return new AnalysisResult(assessmentId, STATUS_ANALYZED, candidates,
                demoModeConfig.isDemoMode());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long assessmentId) {
        updateStatus(assessmentId, STATUS_FAILED);
    }

    /**
     * 후보 채택/반려. <b>성과 지표가 여기서 나온다.</b>
     *
     * <p>정확도를 주장하지 않는다 — 주장하는 순간 심사위원이 검증하려 든다.
     * 대신 사람이 몇 개를 채택했는지를 센다.
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

        log.info("[UC1] 위험요인 {} {}", hazardId, adopt ? "채택" : "반려");
        return new Candidate(hazard.getId(), hazard.getAccidentType(),
                hazard.getAccidentType().getLabel(), hazard.getMissingControl(),
                hazard.getDescription(), null,
                latest == null ? null : latest.getRiskLevel(),
                latest == null ? null : latest.getRuleTrace(),
                hazard.getAiAdopted(), true, GateStatus.of(hazard.getAccidentType()),
                gateNote(hazard.getAccidentType()));
    }

    /** 채택률 지표. 분모는 AI가 제안한 전체, 분자는 사람이 채택한 것 */
    /** 미통과 축에 붙는 안내. 화면이 이 문장을 그대로 띄운다 */
    private String gateNote(AccidentType axis) {
        if (GateStatus.of(axis) == GateStatus.PHOTO) {
            return null;
        }
        return "이 축은 2026-09-21 사진 판독 검증(30장)을 통과하지 못했습니다. "
                + "참고용이며 현장 확인 후 확정하십시오. 채택률 지표에는 포함되지 않습니다.";
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adoptionRate(Long siteId) {
        // 게이트를 통과한 축만 센다. 검증 안 된 축을 지표에 넣으면
        // 게이트 결과를 본 심사위원이 바로 짚는다
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
        m.put("note", "정확도가 아니라 사람이 채택한 비율입니다. 아직 판단하지 않은 후보는 "
                + "분자에 들어가지 않습니다. 사진 판독 검증(2026-09-21)을 통과한 "
                + gated.size() + "개 축만 집계합니다.");
        return m;
    }

    @Transactional(readOnly = true)
    public AnalysisResult result(Long assessmentId) {
        Assessment assessment = assessmentRepository.findById(assessmentId).orElseThrow(
                () -> new NotFoundException("평가를 찾을 수 없습니다: " + assessmentId));

        List<Candidate> candidates = new ArrayList<>();
        for (AssessmentHazard link : assessmentHazardRepository.findByAssessmentId(assessmentId)) {
            hazardRepository.findById(link.getHazardId()).ifPresent(h -> candidates.add(
                    new Candidate(h.getId(), h.getAccidentType(), h.getAccidentType().getLabel(),
                            h.getMissingControl(), h.getDescription(), null,
                            link.getRiskLevel(), link.getRuleTrace(), h.getAiAdopted(),
                            h.getSource() != HazardSource.PHOTO,
                            GateStatus.of(h.getAccidentType()),
                            gateNote(h.getAccidentType()))));
        }
        return new AnalysisResult(assessmentId, assessment.getStatus(), candidates,
                demoModeConfig.isDemoMode());
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

    private String hazardKey(AccidentType axis, String missingControl) {
        return axis + "|" + (missingControl == null ? "" : missingControl.replaceAll("\\s+", ""));
    }

    private void emit(String sessionId, String type, String correlationId,
                      Long targetId, Object payload) {
        sseService.send(sessionId, SseService.SseEvent.of(
                type, correlationId, targetId == null ? null : String.valueOf(targetId), payload));
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
