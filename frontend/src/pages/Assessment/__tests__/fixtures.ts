import type { FollowUpDetail } from "@/types/incident";

/** 사다리 떨어짐 사고가 만든 수시평가(작성 중). 떨어짐 상(기존 대책 기한 경과), 넘어짐 하 */
export function followUpFixture(overrides: Partial<FollowUpDetail> = {}): FollowUpDetail {
  return {
    assessmentId: 100,
    incidentId: 7,
    status: "DRAFT",
    assessedOn: "2026-10-02",
    confirmedOn: null,
    equipmentId: 10,
    equipmentName: "이동식 사다리 A",
    locationTag: "공장동 후면 차양부",
    occurredAt: "2026-10-02T10:20:00+09:00",
    incidentType: "FALL",
    incidentTypeLabel: "떨어짐",
    severity: "LOST_TIME",
    legalBasis: "시행규칙 제37조제2항제3호",
    inspector: null,
    participants: [],
    hazards: [
      {
        hazardId: 1,
        accidentType: "FALL",
        missingControl: "작업발판 미확보",
        description: "차양부 천장 도장 시 이동식 사다리 최상부 디딤대에서 작업",
        before: "HIGH",
        riskLevel: "HIGH",
        ruleTrace: "떨어짐 사고 발생, 휴업예상 5일, 사고 전 상",
        sameAxis: true,
        acceptable: false,
        action: null,
        priorAction: {
          actionId: 3,
          content: "차양부 천장 작업 시 이동식 비계(안전난간) 사용",
          owner: "생산반장 김철수",
          dueDate: "2026-09-02",
          status: "OVERDUE",
          completedOn: null,
        },
        suggestion: { content: "이동식 비계(안전난간) 또는 말비계로 작업발판 확보", lawRef: "산업안전보건기준에 관한 규칙 제42조제1항" },
      },
      {
        hazardId: 2,
        accidentType: "PPE",
        missingControl: "안전모 미착용",
        description: "안전모 미착용",
        before: "LOW",
        riskLevel: "LOW",
        ruleTrace: "안전모 착용 확인 (제32조)",
        sameAxis: false,
        acceptable: true,
        action: null,
        priorAction: null,
        suggestion: null,
      },
    ],
    workPlans: [
      { workPlanId: 41, workName: "차양부 천장 도장", workDate: "2026-10-03", status: "HOLD", warning: "작업 보류: 수시평가 완료 전 작업 재개 금지" },
    ],
    ...overrides,
  };
}
