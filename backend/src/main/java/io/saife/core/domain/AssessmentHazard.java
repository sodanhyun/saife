package io.saife.core.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 평가 ↔ 위험요인. 등급이 여기 붙는다.
 * 같은 위험요인도 평가 시점마다 등급이 다를 수 있다.
 *
 * <p>{@code ruleTrace}는 어떤 룰로 이 등급이 나왔는지를 문자열로 남긴 것이다.
 * 화면에 그대로 띄운다 — 심사위원이 "이 등급은 AI가 정한 겁니까"라고 물으면
 * 룰 테이블을 보여줄 수 있어야 한다.
 */
@Entity
@Table(name = "assessment_hazard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AssessmentHazard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    @Column(name = "hazard_id", nullable = false)
    private Long hazardId;

    /** 빈도 */
    private Short frequency;

    /** 강도 */
    private Short severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;

    /** 판정 근거. 룰 엔진이 채운다 */
    @Column(name = "rule_trace", columnDefinition = "text")
    private String ruleTrace;
}
