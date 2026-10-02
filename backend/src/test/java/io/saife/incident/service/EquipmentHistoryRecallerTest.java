package io.saife.incident.service;

import io.saife.core.domain.AccidentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Purpose}(PRE_WORK / POST_INCIDENT)가 headline 문구만 바꾸고
 * 소환 로직(대상·정렬·predicted 판정)은 그대로인지 확인한다.
 *
 * <p>기존 호출부({@code IncidentService})는 전부 {@code POST_INCIDENT}를 쓰므로
 * 그 출력은 이 파라미터 추가 전후로 달라지면 안 된다 — 그 회귀를 여기서 잡는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EquipmentHistoryRecallerTest {

    @Autowired
    private EquipmentHistoryRecaller recaller;

    @Test
    @DisplayName("POST_INCIDENT — 사고 전 평가와 감소대책 경과일을 사실형으로 말한다(판단 문장 없음)")
    void postIncidentHeadlineNamesThePrediction() {
        // 이동식 사다리 A: hazard1(FALL)이 미이행 조치(action1, OVERDUE)와 함께 등록돼 있다
        EquipmentHistoryRecaller.Recall recall = recaller.recall(1L, AccidentType.FALL,
                OffsetDateTime.now(), OffsetDateTime.now(),
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);

        assertThat(recall.predicted()).isTrue();
        assertThat(recall.headline()).startsWith("사고 전 평가 떨어짐").doesNotContain("예고").doesNotContain("'");
    }

    @Test
    @DisplayName("PRE_WORK — axis가 없어 predicted는 항상 false이고, '예고' 표현을 쓰지 않는다")
    void preWorkHeadlineNeverPredictsBeforeAnIncidentExists() {
        EquipmentHistoryRecaller.Recall recall = recaller.recall(1L, null,
                OffsetDateTime.now(), OffsetDateTime.now(),
                EquipmentHistoryRecaller.Purpose.PRE_WORK);

        assertThat(recall.predicted())
                .as("axis=null이면 sameAxisAsIncident가 전부 false라 predicted도 false다")
                .isFalse();
        assertThat(recall.headline()).doesNotContain("예고되어");
        assertThat(recall.headline()).contains("최근 평가").doesNotContain("'");
    }

    @Test
    @DisplayName("두 Purpose가 소환 대상 자체(위험요인·미이행 조치 수)는 바꾸지 않는다")
    void bothPurposesRecallTheSameUnderlyingFacts() {
        OffsetDateTime now = OffsetDateTime.now();
        EquipmentHistoryRecaller.Recall preWork =
                recaller.recall(1L, null, now, now, EquipmentHistoryRecaller.Purpose.PRE_WORK);
        EquipmentHistoryRecaller.Recall postIncident = recaller.recall(1L, AccidentType.FALL, now, now,
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);

        assertThat(preWork.unfinishedActions()).hasSameSizeAs(postIncident.unfinishedActions());
        assertThat(preWork.priorHazards()).hasSameSizeAs(postIncident.priorHazards());
        assertThat(preWork.equipmentName()).isEqualTo(postIncident.equipmentName());
    }
}
