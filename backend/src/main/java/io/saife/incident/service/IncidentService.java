package io.saife.incident.service;

import io.saife.core.domain.Equipment;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.service.EquipmentMatcher;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.dto.IncidentDtos;
import io.saife.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * UC2 오케스트레이션 — 사고 등록 한 번으로 네 가지가 동시에 일어난다.
 *
 * <ol>
 *   <li>법정 제출 기한 판정 (휴업 3일 이상 → 1개월)</li>
 *   <li>같은 설비의 이력 소환 — 평가·미이행 조치·작업 브리핑·과거 사고</li>
 *   <li>수시평가 자동 생성 + 재등급</li>
 *   <li>산업재해조사표 문안 초안</li>
 * </ol>
 *
 * <p>1~3은 결정론적 코드다. 모델은 4번에서만 문장을 쓴다. <b>모델이 죽어도 1~3은 돈다.</b>
 *
 * <p>이 순서는 의미가 있다. 소환(2)이 먼저 끝나야 그 결과를 근거로 문안(4)을 쓸 수 있고,
 * 소환 없이 쓴 문안은 일반론이 된다 — 그건 이미 있는 서식 템플릿과 다를 게 없다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentMatcher equipmentMatcher;
    private final EquipmentHistoryRecaller recaller;
    private final FollowUpAssessmentService followUpAssessmentService;
    private final IncidentReportDrafter drafter;

    private static final String DISCLAIMER =
            "작성 보조 결과입니다. 법률 자문이 아니며 최종 확정과 제출은 담당자가 합니다.";

    private static final String FOLLOW_UP_LEGAL_BASIS =
            "산업안전보건법 제36조 — 재해가 발생한 작업은 재개 전 수시평가 대상입니다.";

    @Transactional
    public IncidentDtos.RegisterResponse register(Long siteId, IncidentDtos.RegisterRequest request) {
        OffsetDateTime occurredAt = request.occurredAt() != null
                ? request.occurredAt() : OffsetDateTime.now();

        Long equipmentId = resolveEquipmentId(siteId, request);

        Incident incident = Incident.builder()
                .siteId(siteId)
                .equipmentId(equipmentId)
                .workPlanId(request.workPlanId())
                .occurredAt(occurredAt)
                .victimName(request.victimName())
                .severity(request.severity())
                .leaveDays(request.leaveDays())
                .accidentType(request.accidentType())
                .description(request.description())
                .build();

        incident.decideReportDuty();
        incident = incidentRepository.save(incident);

        // knownAsOf = 사고가 기록된 시각. 이후에 생기는 수시평가를
        // "사고 전에 알고 있었다"의 근거로 쓰면 순환 논증이 된다
        EquipmentHistoryRecaller.Recall recall = recaller.recall(
                equipmentId, request.accidentType(), occurredAt, incident.getCreatedAt());

        // 평가일은 사고보다 앞설 수 없다. 수시평가는 재해 발생 뒤,
        // 작업 재개 전에 하는 것이다. today를 그대로 쓰면 UC4 타임라인에서
        // 수시평가가 그걸 만든 사고보다 앞에 놀이고 화살표가 거꾸로 간다.
        LocalDate today = LocalDate.now();
        LocalDate assessedOn = today.isBefore(occurredAt.toLocalDate())
                ? occurredAt.toLocalDate() : today;

        FollowUpAssessmentService.Result followUp =
                followUpAssessmentService.create(incident, assessedOn);
        incident.attachFollowUpAssessment(followUp.assessmentId());

        IncidentReportDrafter.Draft draft = drafter.draft(incident, recall);
        incident.attachDraft(draft.cause(), draft.prevention());

        incident = incidentRepository.save(incident);

        log.info("[UC2] 사고 {} 등록 완료 — 설비 {}, 제출 {}, 수시평가 {}",
                incident.getId(), equipmentId, incident.getReportStatus(), followUp.assessmentId());

        return new IncidentDtos.RegisterResponse(
                summary(incident, recall.equipmentName()),
                reportDuty(incident, today),
                IncidentDtos.RecallView.from(recall),
                new IncidentDtos.FollowUpView(followUp.assessmentId(), "수시",
                        FOLLOW_UP_LEGAL_BASIS, followUp.regraded(), followUp.newHazardId()),
                new IncidentDtos.DraftView(draft.cause(), draft.prevention(),
                        draft.aiGenerated(), DISCLAIMER));
    }

    /** 이미 등록된 사고를 다시 펼친다 (UC4 타임라인에서 진입) */
    @Transactional(readOnly = true)
    public IncidentDtos.RegisterResponse detail(Long incidentId) {
        Incident incident = incidentRepository.findById(incidentId).orElseThrow(
                () -> new IllegalArgumentException("사고를 찾을 수 없습니다: " + incidentId));

        EquipmentHistoryRecaller.Recall recall = recaller.recall(
                incident.getEquipmentId(), incident.getAccidentType(),
                incident.getOccurredAt(), incident.getCreatedAt());

        return new IncidentDtos.RegisterResponse(
                summary(incident, recall.equipmentName()),
                reportDuty(incident, LocalDate.now()),
                IncidentDtos.RecallView.from(recall),
                new IncidentDtos.FollowUpView(incident.getFollowUpAssessmentId(), "수시",
                        FOLLOW_UP_LEGAL_BASIS, List.of(), null),
                new IncidentDtos.DraftView(incident.getCause(), incident.getPrevention(),
                        false, DISCLAIMER));
    }

    /**
     * 목록. 조회할 때마다 기한 경과를 반영한다.
     *
     * <p>스케줄러를 두지 않았다. 대회 기간에 돌아가는 배치 프로세스는 무대에서
     * 관찰할 수 없는 상태를 만들고, 기한 판정은 조회 시점에 계산해도 결과가 같다.
     */
    @Transactional
    public Page<IncidentDtos.IncidentListItem> list(Long siteId, Pageable pageable) {
        LocalDate today = LocalDate.now();
        return incidentRepository.findBySiteIdOrderByOccurredAtDesc(siteId, pageable)
                .map(incident -> {
                    ReportStatus before = incident.getReportStatus();
                    incident.refreshOverdue(today);
                    if (before != incident.getReportStatus()) {
                        incidentRepository.save(incident);
                    }
                    return listItem(incident, today);
                });
    }

    /**
     * 설비 매칭. UC3과 같은 매처를 쓴다.
     *
     * <p><b>후보가 애매하면 만들지 않는다.</b> 잘못 붙은 설비 ID는 조용히 틀리고,
     * 그 순간 "하나의 설비 ID" 위에 쌓은 이력이 둘로 갈라진다. 확신이 없으면
     * {@code equipmentId} 없이 기록하고 사람이 나중에 붙이게 둔다.
     */
    private Long resolveEquipmentId(Long siteId, IncidentDtos.RegisterRequest request) {
        if (request.equipmentId() != null) {
            return request.equipmentId();
        }
        if (request.equipmentQuery() == null || request.equipmentQuery().isBlank()) {
            return null;
        }
        EquipmentMatcher.MatchResult match =
                equipmentMatcher.match(siteId, request.equipmentQuery(), null);

        if (match.isConfirmed()) {
            return match.equipment().getId();
        }
        log.warn("[UC2] 설비 매칭 실패 '{}' — 후보 {}건. 설비 없이 기록한다",
                request.equipmentQuery(), match.candidates().size());
        return null;
    }

    private IncidentDtos.IncidentSummary summary(Incident incident, String equipmentName) {
        return new IncidentDtos.IncidentSummary(
                incident.getId(), incident.getSiteId(), incident.getEquipmentId(), equipmentName,
                incident.getWorkPlanId(), incident.getOccurredAt(), incident.getVictimName(),
                incident.getSeverity(), incident.getLeaveDays(), incident.getAccidentType(),
                incident.getDescription(), incident.getFollowUpAssessmentId());
    }

    /**
     * 법정 기한 카드.
     *
     * <p>{@code basis}는 왜 이 판정이 나왔는지를 그대로 문자열로 남긴 것이다.
     * 등급과 마찬가지로, 근거를 화면에 띄울 수 없는 자동 판정은 만들지 않는다.
     */
    private IncidentDtos.ReportDuty reportDuty(Incident incident, LocalDate today) {
        Integer leaveDays = incident.getLeaveDays();
        String basis;

        if (leaveDays == null) {
            basis = "휴업일수가 입력되지 않아 제출 의무를 판단하지 못했습니다. 확인 후 입력하십시오.";
        } else if (leaveDays >= Incident.REPORTABLE_LEAVE_DAYS) {
            basis = ("휴업 %d일 (3일 이상) → 산업안전보건법 시행규칙 제73조에 따라 "
                    + "발생일로부터 1개월 이내 관할 지방고용노동관서에 산업재해조사표를 제출해야 합니다.")
                    .formatted(leaveDays);
        } else {
            basis = "휴업 %d일 (3일 미만) → 산업재해조사표 제출 의무는 없습니다. 사내 기록은 보존합니다."
                    .formatted(leaveDays);
        }

        return new IncidentDtos.ReportDuty(
                incident.getReportStatus(), incident.getReportStatus().getLabel(),
                incident.getReportDueDate(), incident.daysUntilDue(today), basis);
    }

    private IncidentDtos.IncidentListItem listItem(Incident incident, LocalDate today) {
        String equipmentName = incident.getEquipmentId() == null ? null
                : equipmentRepository.findById(incident.getEquipmentId())
                        .map(Equipment::getName).orElse(null);

        return new IncidentDtos.IncidentListItem(
                incident.getId(), incident.getEquipmentId(), equipmentName,
                incident.getOccurredAt(), incident.getAccidentType(), incident.getSeverity(),
                incident.getLeaveDays(), incident.getReportStatus(),
                incident.getReportStatus().getLabel(), incident.getReportDueDate(),
                incident.daysUntilDue(today), incident.getFollowUpAssessmentId());
    }
}
