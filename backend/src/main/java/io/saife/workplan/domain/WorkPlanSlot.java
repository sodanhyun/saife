package io.saife.workplan.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 되묻기 턴에서 채운 슬롯.
 *
 * <p>데이터 코어가 아는 것과 작업자 답변이 다르면 {@code conflicted}로 남긴다.
 * 불일치는 조치 이행 상태 갱신으로 되먹임된다 — 기술설명서 Feedback 칸에 쓸 재료다.
 *
 * <p>⚠️ 자기보고가 법정 등급을 낮추는 문제가 있다. "설치했습니다"가 '상'을 '중'으로 내린다.
 * 그래서 화면에는 세 출처(대장 기록 · 작업자 답변 · 사진 탐지 결과)를 나란히 표시한다.
 */
@Entity
@Table(name = "work_plan_slot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkPlanSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_plan_id", nullable = false)
    private Long workPlanId;

    /** work_height / anchor_installed / product_name … */
    @Column(name = "slot_key", nullable = false, length = 60)
    private String slotKey;

    @Column(name = "answered_value", length = 500)
    private String answeredValue;

    /** 데이터 코어가 알고 있던 값 */
    @Column(name = "ledger_value", length = 500)
    private String ledgerValue;

    @Column(nullable = false)
    private boolean conflicted;

    @Column(name = "answered_at", nullable = false)
    private OffsetDateTime answeredAt;

    @PrePersist
    void onCreate() {
        this.answeredAt = OffsetDateTime.now();
    }
}
