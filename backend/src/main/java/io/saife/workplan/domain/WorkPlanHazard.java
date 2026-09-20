package io.saife.workplan.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/** 작업계획 ↔ 위험요인. 브리핑의 근거가 어디서 왔는지를 남긴다. */
@Entity
@Table(name = "work_plan_hazard")
@IdClass(WorkPlanHazard.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkPlanHazard {

    @Id
    @Column(name = "work_plan_id")
    private Long workPlanId;

    @Id
    @Column(name = "hazard_id")
    private Long hazardId;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long workPlanId;
        private Long hazardId;
    }
}
