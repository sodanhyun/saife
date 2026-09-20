package io.saife.workplan.domain;

import jakarta.persistence.*;
import lombok.*;

/** 작업 인원 — 성명·직책·담당임무 (회사 서식 항목) */
@Entity
@Table(name = "work_plan_worker")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkPlanWorker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_plan_id", nullable = false)
    private Long workPlanId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String position;

    @Column(length = 200)
    private String duty;
}
