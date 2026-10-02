package io.saife.incident.service;

import io.saife.common.error.ApiExceptions.ConflictException;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.core.domain.*;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.HazardRepository;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.IncidentType;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.dto.IncidentDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사고 보고 입력 규칙(round2 A-3, A-4, A-6)과 사고 시점 기준 사고 전 기록(A-1).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IncidentReportRulesTest {

    private static final Long SITE_ID = 1L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private EquipmentHistoryRecaller recaller;

    @Autowired
    private HazardRepository hazardRepository;

    @Autowired
    private ActionRepository actionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    private IncidentDtos.RegisterRequest req(IncidentSeverity severity, Integer leaveDays, IncidentType type,
                                             OffsetDateTime at) {
        return new IncidentDtos.RegisterRequest(4L, null, null, at, null, severity, leaveDays, type,
                "테스트용 사고 서술", "골절", "오른발");
    }

    // ────────────────────────── 입력 규칙 ──────────────────────────

    @Test
    @DisplayName("발생형태와 재해 정도는 필수, 휴업이면 휴업예상일수도 필수")
    void requiredFields() {
        OffsetDateTime now = OffsetDateTime.now().minusMinutes(10);
        assertThatThrownBy(() -> incidentService.register(SITE_ID, req(IncidentSeverity.LOST_TIME, 5, null, now)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("발생형태");
        assertThatThrownBy(() -> incidentService.register(SITE_ID, req(null, 5, IncidentType.FALL, now)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("재해 정도");
        assertThatThrownBy(() -> incidentService.register(SITE_ID, req(IncidentSeverity.LOST_TIME, null, IncidentType.FALL, now)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("휴업예상일수");
    }

    @Test
    @DisplayName("발생 일시는 지금보다 늦을 수 없다")
    void occurredAtNotInFuture() {
        assertThatThrownBy(() -> incidentService.register(SITE_ID,
                req(IncidentSeverity.LOST_TIME, 5, IncidentType.FALL, OffsetDateTime.now().plusHours(2))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("발생 일시");
    }

    @Test
    @DisplayName("사고 발생형태는 공단 분류 라벨로 나가고, 상해 종류와 부위가 저장된다")
    void incidentTypeAndInjury() {
        IncidentDtos.RegisterResponse r = incidentService.register(SITE_ID,
                req(IncidentSeverity.LOST_TIME, 5, IncidentType.TRIP, OffsetDateTime.now().minusMinutes(10)));

        assertThat(r.incident().incidentType()).isEqualTo(IncidentType.TRIP);
        assertThat(r.incident().incidentTypeLabel()).isEqualTo("넘어짐");
        assertThat(r.incident().accidentType()).as("넘어짐은 6축 대응이 없다").isNull();
        assertThat(r.incident().injuryType()).isEqualTo("골절");
        assertThat(r.incident().injuryPart()).isEqualTo("오른발");
    }

    @Test
    @DisplayName("아차사고는 조사표 대상이 아니고 상해 칸을 남기지 않는다")
    void nearMissIsNotReportable() {
        IncidentDtos.RegisterResponse r = incidentService.register(SITE_ID,
                req(IncidentSeverity.NEAR_MISS, null, IncidentType.STRUCK, OffsetDateTime.now().minusMinutes(10)));

        assertThat(r.reportDuty().status()).isEqualTo(ReportStatus.NOT_REQUIRED);
        assertThat(r.reportDuty().dueDate()).isNull();
        assertThat(r.incident().injuryType()).isNull();
        // 수시평가 근거도 아차사고로 쓰고 휴업 표기를 하지 않는다
        assertThat(r.followUp().regraded()).filteredOn(g -> g.accidentType() == AccidentType.STRUCK)
                .allSatisfy(g -> assertThat(g.ruleTrace()).startsWith("부딪힘 아차사고").doesNotContain("휴업"));
    }

    @Test
    @DisplayName("제출 완료 처리 — 제출일이 남고, 제출 대상이 아니면 409")
    void markSubmitted() {
        IncidentDtos.RegisterResponse r = incidentService.register(SITE_ID,
                req(IncidentSeverity.LOST_TIME, 5, IncidentType.FALL, OffsetDateTime.now().minusMinutes(10)));
        IncidentDtos.IncidentListItem item = incidentService.markSubmitted(r.incident().id());
        assertThat(item.reportStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(item.reportSubmittedOn()).isEqualTo(LocalDate.now(KST));
        assertThat(incidentService.detail(r.incident().id()).reportDuty().submittedOn()).isEqualTo(LocalDate.now(KST));

        IncidentDtos.RegisterResponse nearMiss = incidentService.register(SITE_ID,
                req(IncidentSeverity.NEAR_MISS, null, IncidentType.STRUCK, OffsetDateTime.now().minusMinutes(5)));
        assertThatThrownBy(() -> incidentService.markSubmitted(nearMiss.incident().id()))
                .isInstanceOf(ConflictException.class);
    }

    // ────────────────────────── 사고 시점 기준 (A-1) ──────────────────────────

    private Action action(Hazard h, LocalDate due, OffsetDateTime createdAt, OffsetDateTime completedAt) {
        Action a = actionRepository.save(Action.builder().hazardId(h.getId()).content("대책 " + due)
                .owner("가공반장 이영희").dueDate(due)
                .status(completedAt == null ? ActionStatus.PENDING : ActionStatus.DONE).build());
        actionRepository.flush();
        jdbc.update("UPDATE action SET created_at = ?, completed_at = ? WHERE id = ?",
                Timestamp.from(createdAt.toInstant()),
                completedAt == null ? null : Timestamp.from(completedAt.toInstant()), a.getId());
        return a;
    }

    @Test
    @DisplayName("사고 전 기록의 미이행 조치는 사고 시점 기준: 사고 전에 만들고 사고 때까지 끝내지 않은 것만, 경과일도 사고일 기준")
    void unfinishedActionsAreAsOfIncident() {
        Hazard h = hazardRepository.save(Hazard.builder().siteId(SITE_ID).equipmentId(4L)
                .accidentType(AccidentType.CAUGHT).missingControl("테스트 방호덮개").description("테스트")
                .source(HazardSource.MANUAL).aiSuggested(false).build());
        OffsetDateTime incidentAt = OffsetDateTime.now().minusDays(10);
        LocalDate incidentOn = incidentAt.atZoneSameInstant(KST).toLocalDate();

        Action openThen = action(h, incidentOn.minusDays(5), incidentAt.minusDays(30), incidentAt.plusDays(3));
        Action doneBefore = action(h, incidentOn.minusDays(20), incidentAt.minusDays(40), incidentAt.minusDays(15));
        Action createdAfter = action(h, incidentOn.plusDays(10), incidentAt.plusDays(1), null);
        hazardRepository.flush();
        em.clear();   // JDBC로 바꾼 생성, 완료 시각을 다시 읽게 한다

        EquipmentHistoryRecaller.Recall recall = recaller.recall(4L, AccidentType.CAUGHT, incidentAt, incidentAt,
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);

        assertThat(recall.unfinishedActions()).extracting(EquipmentHistoryRecaller.UnfinishedAction::actionId)
                .contains(openThen.getId())
                .doesNotContain(doneBefore.getId(), createdAfter.getId());
        EquipmentHistoryRecaller.UnfinishedAction u = recall.unfinishedActions().stream()
                .filter(a -> a.actionId().equals(openThen.getId())).findFirst().orElseThrow();
        assertThat(u.overdueDays()).as("경과일은 사고일 기준").isEqualTo(5L);
        assertThat(u.status()).isEqualTo(ActionStatus.OVERDUE);
    }
}
