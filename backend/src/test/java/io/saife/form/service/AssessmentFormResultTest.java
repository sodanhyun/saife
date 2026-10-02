package io.saife.form.service;

import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 위험성평가표 이행 결과 칸(A-9, A-10): 출력 시점에 따라 바뀌는 값을 쓰지 않는다 */
class AssessmentFormResultTest {

    private Action action(LocalDate due, OffsetDateTime completedAt) {
        return Action.builder().hazardId(1L).content("대책").dueDate(due)
                .status(completedAt == null ? ActionStatus.OVERDUE : ActionStatus.DONE)
                .completedAt(completedAt).build();
    }

    @Test
    @DisplayName("기한 안에 완료하면 완료일만, 기한을 넘겨 완료하면 (기한 경과)를 붙이고, 미완료는 출력일과 무관하게 미완료")
    void resultLabels() {
        OffsetDateTime onTime = OffsetDateTime.of(2026, 9, 1, 3, 0, 0, 0, ZoneOffset.UTC);   // KST 09-01
        OffsetDateTime late = OffsetDateTime.of(2026, 9, 20, 3, 0, 0, 0, ZoneOffset.UTC);
        assertThat(AssessmentFormService.result(action(LocalDate.of(2026, 9, 5), onTime))).isEqualTo("완료 2026-09-01");
        assertThat(AssessmentFormService.result(action(LocalDate.of(2026, 9, 5), late))).isEqualTo("완료 2026-09-20 (기한 경과)");
        assertThat(AssessmentFormService.result(action(LocalDate.of(2020, 1, 1), null))).isEqualTo("미완료");
    }
}
