package io.saife.incident.service;

import io.saife.common.error.ApiExceptions.ConflictException;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.IncidentType;
import io.saife.incident.dto.IncidentDtos;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사고가 만든 수시평가의 확정과 작업 보류 해제(round2 A-2).
 *
 * <p>확정 조건: 참여 근로자, 허용 불가 위험요인마다 개선대책(담당, 기한). 확정하면 같은 설비의 작업 보류가
 * 재승인 대기(SUBMITTED)로 돌아가고 보류 해제 문구가 남는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FollowUpConfirmServiceTest {

    private static final Long SITE_ID = 1L;
    private static final Long EQUIPMENT_ID = 4L;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private FollowUpConfirmService followUpConfirmService;

    @Autowired
    private WorkPlanRepository workPlanRepository;

    private IncidentDtos.RegisterResponse register(OffsetDateTime occurredAt) {
        return incidentService.register(SITE_ID, new IncidentDtos.RegisterRequest(EQUIPMENT_ID, null, null,
                occurredAt, null, IncidentSeverity.LOST_TIME, 5, IncidentType.CAUGHT, "테스트용 사고 서술", null, null));
    }

    private WorkPlan savePlan(LocalDate workDate) {
        return workPlanRepository.save(WorkPlan.builder()
                .siteId(SITE_ID).equipmentId(EQUIPMENT_ID).workName("테스트 작업")
                .workDate(workDate).status(WorkPlanStatus.APPROVED).build());
    }

    /** 허용 불가 위험요인마다 대책을 채운 확정 요청 */
    private IncidentDtos.FollowUpRequest fullRequest(IncidentDtos.FollowUpDetail detail, List<String> participants) {
        LocalDate due = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(14);
        List<IncidentDtos.FollowUpHazardInput> inputs = detail.hazards().stream()
                .map(h -> new IncidentDtos.FollowUpHazardInput(h.hazardId(), h.acceptable(),
                        h.acceptable() ? null : "방호덮개 설치", h.acceptable() ? null : "가공반장 이영희",
                        h.acceptable() ? null : due))
                .toList();
        return new IncidentDtos.FollowUpRequest("홍길동", participants, inputs);
    }

    @Test
    @DisplayName("조회 — 사고가 만든 수시평가는 작성 중이고, 사고와 같은 발생형태 위험요인이 맨 위, 상과 중은 허용 불가로 시작한다")
    void detailStartsAsDraft() {
        IncidentDtos.RegisterResponse r = register(OffsetDateTime.now().minusHours(1));

        IncidentDtos.FollowUpDetail d = followUpConfirmService.detail(r.followUp().assessmentId());

        assertThat(d.status()).isEqualTo("DRAFT");
        assertThat(d.confirmedOn()).isNull();
        assertThat(d.incidentId()).isEqualTo(r.incident().id());
        assertThat(d.incidentTypeLabel()).isEqualTo("끼임");
        assertThat(d.legalBasis()).isEqualTo("시행규칙 제37조제2항제3호");
        assertThat(d.hazards()).isNotEmpty();
        assertThat(d.hazards().get(0).sameAxis()).isTrue();
        d.hazards().stream()
                .filter(h -> h.riskLevel() != io.saife.core.domain.RiskLevel.LOW)
                .forEach(h -> assertThat(h.acceptable()).isFalse());
    }

    @Test
    @DisplayName("확정 — 참여 근로자가 없으면 400")
    void confirmRequiresParticipants() {
        IncidentDtos.RegisterResponse r = register(OffsetDateTime.now().minusHours(1));
        Long id = r.followUp().assessmentId();
        IncidentDtos.FollowUpDetail d = followUpConfirmService.detail(id);

        assertThatThrownBy(() -> followUpConfirmService.confirm(id, fullRequest(d, List.of())))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("참여 근로자");
    }

    @Test
    @DisplayName("확정 — 허용 불가 위험요인에 대책이 없으면 400")
    void confirmRequiresPlansForUnacceptableHazards() {
        IncidentDtos.RegisterResponse r = register(OffsetDateTime.now().minusHours(1));
        Long id = r.followUp().assessmentId();
        IncidentDtos.FollowUpDetail d = followUpConfirmService.detail(id);
        List<IncidentDtos.FollowUpHazardInput> noPlans = d.hazards().stream()
                .map(h -> new IncidentDtos.FollowUpHazardInput(h.hazardId(), false, null, null, null))
                .toList();

        assertThatThrownBy(() -> followUpConfirmService.confirm(id,
                new IncidentDtos.FollowUpRequest("홍길동", List.of("김철수"), noPlans)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("개선대책");
    }

    @Test
    @DisplayName("확정 — 수시평가가 CONFIRMED가 되고, 작업 보류는 재승인 대기로 풀리며, 사고 화면도 확정일과 보류 해제를 말한다")
    void confirmReleasesHolds() {
        OffsetDateTime occurredAt = OffsetDateTime.now().minusHours(1);
        WorkPlan plan = savePlan(occurredAt.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(1));
        IncidentDtos.RegisterResponse r = register(occurredAt);
        assertThat(workPlanRepository.findById(plan.getId()).orElseThrow().getStatus()).isEqualTo(WorkPlanStatus.HOLD);

        Long id = r.followUp().assessmentId();
        IncidentDtos.FollowUpDetail before = followUpConfirmService.detail(id);
        IncidentDtos.FollowUpDetail after = followUpConfirmService.confirm(id, fullRequest(before, List.of("김철수", "이영희")));

        assertThat(after.status()).isEqualTo("CONFIRMED");
        assertThat(after.confirmedOn()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertThat(after.participants()).containsExactly("김철수", "이영희");
        assertThat(after.inspector()).isEqualTo("홍길동");
        after.hazards().stream().filter(h -> !h.acceptable())
                .forEach(h -> assertThat(h.action()).as("허용 불가 위험요인에 이번 대책이 붙는다").isNotNull());

        WorkPlan released = workPlanRepository.findById(plan.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(WorkPlanStatus.SUBMITTED);
        assertThat(released.getWarningNote()).contains("보류 해제").contains("재승인");
        assertThat(after.workPlans()).extracting(IncidentDtos.AffectedWorkPlan::status).contains("SUBMITTED");

        IncidentDtos.RegisterResponse reloaded = incidentService.detail(r.incident().id());
        assertThat(reloaded.followUp().status()).isEqualTo("CONFIRMED");
        assertThat(reloaded.followUp().confirmedOn()).isEqualTo(after.confirmedOn());
        assertThat(reloaded.cascade().get(1).title()).startsWith("수시평가 확정 ");
        assertThat(reloaded.cascade().get(3).title()).isEqualTo("보류 해제 1건");
    }

    @Test
    @DisplayName("확정은 한 번만 — 이미 확정한 수시평가를 다시 확정하면 409")
    void confirmTwiceIsConflict() {
        IncidentDtos.RegisterResponse r = register(OffsetDateTime.now().minusHours(1));
        Long id = r.followUp().assessmentId();
        IncidentDtos.FollowUpRequest req = fullRequest(followUpConfirmService.detail(id), List.of("김철수"));
        followUpConfirmService.confirm(id, req);

        assertThatThrownBy(() -> followUpConfirmService.confirm(id, req)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("저장 — 작성 중 저장은 확정 조건을 보지 않고 참여 근로자와 허용 여부만 남긴다")
    void saveKeepsDraft() {
        IncidentDtos.RegisterResponse r = register(OffsetDateTime.now().minusHours(1));
        Long id = r.followUp().assessmentId();
        IncidentDtos.FollowUpDetail d = followUpConfirmService.detail(id);
        Long first = d.hazards().get(0).hazardId();

        IncidentDtos.FollowUpDetail saved = followUpConfirmService.save(id, new IncidentDtos.FollowUpRequest(
                null, List.of("박민수"), List.of(new IncidentDtos.FollowUpHazardInput(first, true, null, null, null))));

        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(saved.participants()).containsExactly("박민수");
        assertThat(saved.hazards()).filteredOn(h -> h.hazardId().equals(first))
                .extracting(IncidentDtos.FollowUpHazard::acceptable).containsExactly(true);
    }
}
