package io.saife.incident.service;

import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.core.service.RiskRuleEngine;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 사고 발생 시 수시평가를 자동 생성한다.
 *
 * <p>법 근거: 산업재해가 발생한 경우 <b>관련 작업을 시작하기 전까지</b> 수시평가
 * (시행규칙 제37조제2항제3호, 고시 제2024-76호 제15조제2항제5호). 그래서 이 생성은
 * 편의 기능이 아니라 법정 절차의 자동화다.
 *
 * <p><b>등급은 룰 엔진이 낸다.</b> 사고가 났다는 사실이 빈도를 확정하므로
 * 재평가는 대개 등급이 올라간다 — 그리고 올라간 이유가 {@code ruleTrace}에 남아
 * 화면에 그대로 뜬다. 모델은 이 과정에 관여하지 않는다.
 *
 * <p>상태는 {@code DRAFT}로 만든다. <b>확정은 사람이 한다.</b> 자동으로 확정해
 * 버리면 "AI가 위험성평가를 대신했다"가 되고, 그건 이 제품이 주장하는 바가 아니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FollowUpAssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final HazardRepository hazardRepository;
    private final ActionRepository actionRepository;
    private final RiskRuleEngine riskRuleEngine;

    /** 사고로 생성된 평가임을 표시하는 값. UC4 타임라인이 이걸로 사고와 평가를 잇는다 */
    public static final String TRIGGER_INCIDENT = "INCIDENT";

    /**
     * @param assessmentId 생성된 수시평가
     * @param regraded     재평가된 위험요인들
     * @param newHazardId  사고로 새로 등록된 위험요인 (없으면 null)
     */
    public record Result(Long assessmentId, List<Regrade> regraded, Long newHazardId) {}

    public record Regrade(Long hazardId, AccidentType accidentType, String missingControl,
                          RiskLevel before, RiskLevel after, String ruleTrace, boolean changed) {}

    /**
     * 사고 → 수시평가.
     *
     * @param incident 등록된 사고 (id가 있어야 한다)
     * @param today    평가일
     */
    @Transactional
    public Result create(Incident incident, LocalDate today) {
        Assessment assessment = assessmentRepository.save(Assessment.builder()
                .siteId(incident.getSiteId())
                .kind(AssessmentKind.OCCASIONAL)
                .triggerType(TRIGGER_INCIDENT)
                .triggerRefId(incident.getId())
                .assessedOn(today)
                // 참여 근로자는 사람이 입력한다(시행규칙 제37조의2). 자동 생성 단계에서는 비워 둔다
                .participants(null)
                .status("DRAFT")
                .build());

        // 순회점검에서 제외한 후보는 이 설비의 위험요인이 아니다. 수시평가에 올리지 않는다
        List<Hazard> hazards = incident.getEquipmentId() == null ? List.of()
                : hazardRepository.findByEquipmentIdOrderByCreatedAtDesc(incident.getEquipmentId()).stream()
                        .filter(h -> !(h.isAiSuggested() && Boolean.FALSE.equals(h.getAiAdopted())))
                        .toList();

        List<Regrade> regraded = new ArrayList<>();
        boolean axisCovered = false;

        for (Hazard h : hazards) {
            AssessmentHazard last = lastAssessment(h.getId());
            RiskLevel before = last == null ? null : last.getRiskLevel();
            boolean sameAxis = incident.getAccidentType() != null
                    && incident.getAccidentType() == h.getAccidentType();

            RiskRuleEngine.Decision decision = sameAxis
                    ? afterIncident(before, incident)
                    : carryForward(last);

            axisCovered |= sameAxis;
            regraded.add(persist(assessment.getId(), h, before, decision));
        }

        // 사고의 발생형태에 해당하는 위험요인이 없었다면 지금 만든다.
        // 사고가 드러낸 위험을 기록하지 않으면 다음 평가에서 또 빠진다.
        Long newHazardId = null;
        if (!axisCovered && incident.getAccidentType() != null && incident.getEquipmentId() != null) {
            Hazard created = hazardRepository.save(Hazard.builder()
                    .siteId(incident.getSiteId())
                    .equipmentId(incident.getEquipmentId())
                    .accidentType(incident.getAccidentType())
                    .missingControl(incident.getAccidentType().getMissingControlHint())
                    .description(nvl(incident.getDescription(),
                            incident.getIncidentType() == null ? "사고로 확인된 위험요인"
                                    : incident.getIncidentType().getLabel() + " 사고로 확인된 위험요인"))
                    .source(HazardSource.INCIDENT)
                    // 사고 사실에서 도출한 것이지 모델이 제안한 게 아니다.
                    // 여기를 true로 두면 채택률 지표가 오염된다.
                    .aiSuggested(false)
                    .build());
            newHazardId = created.getId();

            RiskRuleEngine.Decision decision = afterIncident(null, incident);
            regraded.add(persist(assessment.getId(), created, null, decision));
        }

        refreshOverdueActions(hazards, today);

        log.info("[UC2] 수시평가 {} 생성 — 사고 {}, 재평가 {}건, 신규 위험요인 {}",
                assessment.getId(), incident.getId(), regraded.size(), newHazardId);

        return new Result(assessment.getId(), regraded, newHazardId);
    }

    /**
     * 이미 만들어진 수시평가의 재평가 결과를 다시 읽는다 — {@code IncidentService.detail()} 재조회 경로용
     * (최종 리뷰 F9). 새로 채점하지 않는다: 등급·근거는 저장된 {@code assessment_hazard} 그대로이고,
     * "이전 등급"은 그 위험요인이 이 수시평가보다 <b>앞서</b> 받은 가장 최근 등급이다
     * ({@link #create}가 채점 직전에 {@link #lastAssessment}로 읽은 값과 같은 정의). 이후에 있었던
     * 재평가(예: 조치 완료 뒤 상시평가)는 제외한다 — 넣으면 사고 당시의 등급 변화가 아니라 현재
     * 등급과 비교하게 된다.
     *
     * @return 평가가 없으면 빈 리스트
     */
    @Transactional(readOnly = true)
    public Result reconstruct(Long assessmentId) {
        if (assessmentId == null) {
            return new Result(null, List.of(), null);
        }
        Assessment followUp = assessmentRepository.findById(assessmentId).orElse(null);
        if (followUp == null) {
            return new Result(assessmentId, List.of(), null);
        }
        List<Regrade> regraded = new ArrayList<>();
        Long newHazardId = null;
        for (AssessmentHazard ah : assessmentHazardRepository.findByAssessmentId(assessmentId)) {
            Hazard h = hazardRepository.findById(ah.getHazardId()).orElse(null);
            RiskLevel before = riskLevelBefore(ah.getHazardId(), followUp);
            if (before == null && h != null && h.getSource() == HazardSource.INCIDENT && newHazardId == null) {
                newHazardId = h.getId();
            }
            regraded.add(new Regrade(ah.getHazardId(), h == null ? null : h.getAccidentType(),
                    h == null ? null : h.getMissingControl(), before, ah.getRiskLevel(), ah.getRuleTrace(),
                    before != null && before != ah.getRiskLevel()));
        }
        return new Result(assessmentId, regraded, newHazardId);
    }

    /** 이 수시평가보다 앞선(평가일이 이르거나, 같은 날이면 먼저 만든) 평가에서의 가장 최근 등급 */
    private RiskLevel riskLevelBefore(Long hazardId, Assessment followUp) {
        for (AssessmentHazard prev : assessmentHazardRepository.findHistoryByHazardId(hazardId)) {
            if (followUp.getId().equals(prev.getAssessmentId())) {
                continue;
            }
            Assessment a = assessmentRepository.findById(prev.getAssessmentId()).orElse(null);
            if (a == null || a.getAssessedOn() == null || followUp.getAssessedOn() == null) {
                continue;
            }
            boolean earlier = a.getAssessedOn().isBefore(followUp.getAssessedOn())
                    || (a.getAssessedOn().isEqual(followUp.getAssessedOn()) && a.getId() < followUp.getId());
            if (earlier) {
                return prev.getRiskLevel();   // 이력은 평가일 내림차순 — 처음 걸리는 게 가장 최근
            }
        }
        return null;
    }

    /**
     * 사고와 같은 발생형태: 등급은 룰 엔진({@link RiskRuleEngine#reassessAfterIncident})이 낸다.
     * 사망 재해는 강도를 최대로 본다. 근거 문구는 화면과 서식에 그대로 나가므로 평문으로 다시 쓴다
     * (화살표, 따옴표 등급, 빈도 × 강도 숫자를 쓰지 않는다).
     */
    private RiskRuleEngine.Decision afterIncident(RiskLevel before, Incident incident) {
        boolean fatal = incident.getSeverity() == IncidentSeverity.FATALITY;
        RiskRuleEngine.Decision d = riskRuleEngine.reassessAfterIncident(before,
                fatal ? Integer.valueOf(Incident.REPORTABLE_LEAVE_DAYS) : incident.getLeaveDays());
        return new RiskRuleEngine.Decision(d.riskLevel(), d.frequency(), d.severity(),
                incidentTrace(before, incident));
    }

    static String incidentTrace(RiskLevel before, Incident incident) {
        List<String> parts = new ArrayList<>();
        String axis = incident.getIncidentType() == null ? "" : incident.getIncidentType().getLabel() + " ";
        Integer leave = incident.getLeaveDays();
        if (incident.getSeverity() == IncidentSeverity.NEAR_MISS) {
            // 아차사고는 상해가 없어 휴업 표기를 쓰지 않는다(시드 문구 "부딪힘 아차사고, 사고 전 평가 없음"과 같은 형식)
            parts.add(axis + "아차사고");
        } else {
            parts.add(axis + "사고 발생");
            if (incident.getSeverity() == IncidentSeverity.FATALITY) {
                parts.add("사망");
            } else if (leave != null) {
                parts.add("휴업예상 %d일".formatted(leave));
            } else {
                parts.add("휴업예상일수 미입력");
            }
        }
        parts.add(before == null ? "사고 전 평가 없음" : "사고 전 " + before.getLabel());
        return String.join(", ", parts);
    }

    /**
     * 사고와 다른 발생형태는 등급을 바꾸지 않는다. 사고가 그 축의 빈도를 말해주지 않는다.
     *
     * <p>등급 근거 칸에는 처리 메모("종전 등급 유지")가 아니라 <b>그 등급의 근거</b>를 남긴다. 종전 평가의
     * 근거를 그대로 옮긴다. 종전 평가가 없으면 3단계 판단법의 기본값(중)과 그 사유를 쓴다.
     */
    private RiskRuleEngine.Decision carryForward(AssessmentHazard last) {
        if (last == null) {
            return new RiskRuleEngine.Decision(RiskLevel.MEDIUM, (short) 2, (short) 2,
                    "평가 이력 없음, 현장 확인 전 중");
        }
        short frequency = last.getFrequency() == null ? 2 : last.getFrequency();
        short severity = last.getSeverity() == null ? 2 : last.getSeverity();
        String trace = last.getRuleTrace() == null || last.getRuleTrace().isBlank()
                ? "종전 평가 등급" : last.getRuleTrace();
        return new RiskRuleEngine.Decision(last.getRiskLevel(), frequency, severity, trace);
    }

    private Regrade persist(Long assessmentId, Hazard hazard, RiskLevel before,
                            RiskRuleEngine.Decision decision) {
        assessmentHazardRepository.save(AssessmentHazard.builder()
                .assessmentId(assessmentId)
                .hazardId(hazard.getId())
                .frequency(decision.frequency())
                .severity(decision.severity())
                .riskLevel(decision.riskLevel())
                .ruleTrace(decision.ruleTrace())
                .build());

        return new Regrade(hazard.getId(), hazard.getAccidentType(), hazard.getMissingControl(),
                before, decision.riskLevel(), decision.ruleTrace(),
                before != null && before != decision.riskLevel());
    }

    /**
     * 기한이 지난 미이행 조치를 OVERDUE로 올린다.
     *
     * <p>상태를 만들어내는 게 아니라 <b>이미 참인 사실을 반영</b>하는 것이다.
     * 사고 화면에서 "기한 지남"이라고 말하려면 데이터가 실제로 그래야 한다.
     */
    private void refreshOverdueActions(List<Hazard> hazards, LocalDate today) {
        if (hazards.isEmpty()) {
            return;
        }
        List<Long> hazardIds = hazards.stream().map(Hazard::getId).toList();
        for (Action action : actionRepository.findPendingByHazardIds(hazardIds, ActionStatus.DONE)) {
            if (action.getStatus() == ActionStatus.PENDING
                    && action.getDueDate() != null && today.isAfter(action.getDueDate())) {
                actionRepository.save(Action.builder()
                        .id(action.getId())
                        .hazardId(action.getHazardId())
                        .assessmentId(action.getAssessmentId())
                        .content(action.getContent())
                        .owner(action.getOwner())
                        .dueDate(action.getDueDate())
                        .status(ActionStatus.OVERDUE)
                        .evidencePath(action.getEvidencePath())
                        .guideRef(action.getGuideRef())
                        .completedAt(action.getCompletedAt())
                        .createdAt(action.getCreatedAt())
                        .build());
                log.debug("[UC2] 조치 {} 기한 경과 반영", action.getId());
            }
        }
    }

    private AssessmentHazard lastAssessment(Long hazardId) {
        List<AssessmentHazard> history = assessmentHazardRepository.findHistoryByHazardId(hazardId);
        return history.isEmpty() ? null : history.get(0);
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
