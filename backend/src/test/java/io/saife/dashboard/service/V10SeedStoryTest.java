package io.saife.dashboard.service;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.RiskLevel;
import io.saife.incident.domain.Incident;
import io.saife.incident.repository.IncidentRepository;
import io.saife.incident.service.EquipmentHistoryRecaller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 — V10(고소작업대 이야기) 시드가 {@link EquipmentHistoryRecaller}의
 * knownAsOf 순환 방지 로직과 맞는지 확인한다.
 *
 * <p>V10의 사고(incident id=1)는 자신의 수시평가(assessment id=5)를 만든다.
 * 그 수시평가의 {@code created_at}은 사고의 {@code created_at}보다 <b>2시간 늦다</b>
 * (V10 마이그레이션이 명시적으로 그렇게 심었다). 사고를 등록하던 바로 그 순간을
 * 재현해 소환하면({@code knownAsOf = incident.createdAt}) 그 수시평가가 "사고 전 기록"으로
 * 잡혀서는 안 된다 — 잡히면 "사고가 스스로 만든 평가를 사고 전에 알고 있었다"는
 * 순환 논증이 된다(2026-09-20 스모크 테스트에서 실제로 한 번 터졌던 버그).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class V10SeedStoryTest {

    /** V10 시드의 고소작업대 */
    private static final Long AERIAL_PLATFORM = 6L;
    /** V10 시드의 위험요인 — "작업발판 안전난간 미설치"(FALL) */
    private static final Long HAZARD_GUARD_RAIL = 7L;

    @Autowired
    private EquipmentHistoryRecaller recaller;
    @Autowired
    private IncidentRepository incidentRepository;

    @Test
    @DisplayName("사고 등록 시점(knownAsOf=incident.createdAt)으로 소환하면, "
            + "그 사고가 만든 수시평가(사고보다 2시간 늦게 생성됨)는 '사고 전 기록'에 잡히지 않는다")
    void followUpAssessmentIsNotRecalledAsPriorRecord() {
        Incident incident = incidentRepository.findById(1L).orElseThrow();
        assertThat(incident.getEquipmentId()).isEqualTo(AERIAL_PLATFORM);
        assertThat(incident.getFollowUpAssessmentId())
                .as("V10 시드는 이 사고에 수시평가를 연결해 둔다")
                .isNotNull();

        // 사고를 "지금 막 등록하는 순간"을 재현한다 — occurredAt·knownAsOf 둘 다
        // 사고 자신의 시각을 쓴다(IncidentService가 실제로 하는 것과 같은 호출 모양).
        EquipmentHistoryRecaller.Recall recall = recaller.recall(
                AERIAL_PLATFORM, AccidentType.FALL,
                incident.getOccurredAt(), incident.getCreatedAt(),
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);

        EquipmentHistoryRecaller.PriorHazard guardRailHazard = recall.priorHazards().stream()
                .filter(h -> h.hazardId().equals(HAZARD_GUARD_RAIL))
                .findFirst().orElseThrow();

        // 사고 전 마지막으로 "알려진" 등급은 -75일 상시평가(assessment id=4, HIGH)다.
        // 사고가 만든 수시평가(id=5, 역시 HIGH지만 rule_trace가 다르다)나 그 뒤의
        // 재평가(id=6, MEDIUM)가 섞여 들어오면 안 된다.
        assertThat(guardRailHazard.lastRiskLevel())
                .as("사고 전 마지막 등급은 -75일 상시평가의 '상'이다")
                .isEqualTo(RiskLevel.HIGH);
        assertThat(guardRailHazard.lastRuleTrace())
                .as("사고가 만든 수시평가(id=5)의 근거 문구('사고 발생으로 빈도 상향')가 섞이면 안 된다")
                .doesNotContain("사고 발생으로 빈도 상향");
        assertThat(guardRailHazard.lastRuleTrace())
                .as("사고 이후 재평가(id=6, MEDIUM)의 근거 문구가 섞이면 더더욱 안 된다")
                .doesNotContain("난간 보수 완료로 강도 하향");
    }
}
