package io.saife.workplan.domain;

/** 작업계획서 상태. 관리부 승인은 조건부가 가능하다. */
public enum WorkPlanStatus {
    DRAFT,        // 대화로 채우는 중
    SUBMITTED,    // 관리부 승인 큐 등록
    APPROVED,     // 승인
    CONDITIONAL,  // 조건부 승인 (approvalNote에 조건)
    REJECTED,
    HOLD,         // 같은 설비에 산업재해가 발생해 수시평가 완료 전까지 작업 보류
    CLOSED        // 완료 보고까지 끝
}
