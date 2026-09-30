package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 작업계획서에 붙은 근거(브리핑 참고 자료). createWorkPlan 시점의 원장 스냅샷 */
@Entity
@Table(name = "work_plan_evidence")
@IdClass(WorkPlanEvidence.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkPlanEvidence {
    @Id @Column(name = "work_plan_id") private Long workPlanId;
    @Id @Column(name = "evidence_no") private int evidenceNo;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String payload;

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long workPlanId;
        private int evidenceNo;
    }
}
