package io.saife.dashboard.service;

import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.repository.IncidentRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2차 개선(B7, B6) — 현재 등급 규칙, 아차사고 분리, 예정 작업에서 보류 제외, 승인 상태 표기.
 * 시드에 기대지 않도록 테스트 전용 설비를 만들어 그 위에서만 본다(@Transactional 롤백).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EquipmentTimelineRound2Test {

    private static final Long SITE = 1L;

    @Autowired private EquipmentTimelineService service;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private HazardRepository hazardRepository;
    @Autowired private AssessmentRepository assessmentRepository;
    @Autowired private AssessmentHazardRepository assessmentHazardRepository;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private WorkPlanRepository workPlanRepository;

    private LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private Equipment newEquipment() {
        return equipmentRepository.save(Equipment.builder().siteId(SITE)
                .name("[TEST] 2차 설비").normalizedName("test2차설비" + System.nanoTime()).build());
    }

    private Hazard hazard(Long equipmentId, AccidentType type) {
        return hazardRepository.save(Hazard.builder().siteId(SITE).equipmentId(equipmentId)
                .accidentType(type).description("[TEST]").source(HazardSource.WORK_PLAN).build());
    }

    private void assess(LocalDate on, Hazard h, RiskLevel level) {
        Assessment a = assessmentRepository.save(Assessment.builder().siteId(SITE)
                .kind(AssessmentKind.ROUTINE).assessedOn(on).status("CONFIRMED").build());
        assessmentHazardRepository.save(AssessmentHazard.builder().assessmentId(a.getId())
                .hazardId(h.getId()).riskLevel(level).ruleTrace("[TEST]").build());
    }

    @Test
    @DisplayName("현재 등급 — 발생형태별 최신 등급의 최댓값. 끼임만 다시 본 하 평가가 떨어짐 상을 덮지 않는다")
    void currentGradeIsMaxOfLatestPerAxis() {
        Equipment eq = newEquipment();
        Hazard fall = hazard(eq.getId(), AccidentType.FALL);
        Hazard caught = hazard(eq.getId(), AccidentType.CAUGHT);
        assess(today().minusDays(30), fall, RiskLevel.HIGH);
        assess(today().minusDays(20), caught, RiskLevel.MEDIUM);
        assess(today().minusDays(5), caught, RiskLevel.LOW);

        TimelineDtos.TimelineSummary s = service.timeline(eq.getId()).summary();

        assertThat(s.currentRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(s.currentRiskAxis()).isEqualTo(AccidentType.FALL);
        assertThat(s.lastAssessedOn()).isEqualTo(today().minusDays(5));
    }

    @Test
    @DisplayName("현재 등급 — 같은 발생형태는 가장 최근 평가 등급을 쓴다(떨어짐 상에서 하로 개선되면 하)")
    void sameAxisUsesLatest() {
        Equipment eq = newEquipment();
        Hazard fall = hazard(eq.getId(), AccidentType.FALL);
        assess(today().minusDays(30), fall, RiskLevel.HIGH);
        assess(today().minusDays(3), fall, RiskLevel.LOW);

        assertThat(service.timeline(eq.getId()).summary().currentRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("아차사고 — 사고 수에서 빼고 따로 센다. 제목은 '부딪힘 아차사고'")
    void nearMissSeparatedFromIncidents() {
        Equipment eq = newEquipment();
        Hazard h = hazard(eq.getId(), AccidentType.STRUCK);
        assess(today().minusDays(10), h, RiskLevel.LOW);
        incidentRepository.save(Incident.builder().siteId(SITE).equipmentId(eq.getId())
                .occurredAt(OffsetDateTime.now().minusDays(2)).accidentType(AccidentType.STRUCK)
                .severity(IncidentSeverity.NEAR_MISS).reportStatus(ReportStatus.NOT_REQUIRED).build());

        TimelineDtos.EquipmentTimeline t = service.timeline(eq.getId());
        assertThat(t.summary().incidentCount()).isZero();
        assertThat(t.summary().nearMissCount()).isEqualTo(1);
        assertThat(t.summary().headline()).isEqualTo("아차사고 1");
        assertThat(t.events()).anyMatch(e -> e.type() == TimelineDtos.EventType.INCIDENT
                && e.title().equals("부딪힘 아차사고"));

        TimelineDtos.EquipmentCard card = service.cards(SITE).stream()
                .filter(c -> c.id().equals(eq.getId())).findFirst().orElseThrow();
        assertThat(card.incidentCount()).isZero();
        assertThat(card.nearMissCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("예정 작업 — 작업 보류(HOLD)는 세지 않는다. 표기는 보류면 재개 조건, 승인이면 승인")
    void upcomingExcludesHoldAndTbmLabel() {
        Equipment eq = newEquipment();
        workPlanRepository.save(WorkPlan.builder().siteId(SITE).equipmentId(eq.getId())
                .workName("[TEST] 보류").workDate(today().plusDays(1)).status(WorkPlanStatus.HOLD).build());
        workPlanRepository.save(WorkPlan.builder().siteId(SITE).equipmentId(eq.getId())
                .workName("[TEST] 승인").workDate(today().plusDays(2)).status(WorkPlanStatus.APPROVED).build());

        TimelineDtos.EquipmentCard card = service.cards(SITE).stream()
                .filter(c -> c.id().equals(eq.getId())).findFirst().orElseThrow();
        assertThat(card.upcomingWorkPlanCount()).isEqualTo(1);

        assertThat(service.timeline(eq.getId()).events())
                .filteredOn(e -> e.type() == TimelineDtos.EventType.WORK_PLAN)
                .extracting(TimelineDtos.TimelineEvent::detail)
                .containsExactlyInAnyOrder("작업 보류, 수시평가 확정 후 재개", "승인");
    }
}
