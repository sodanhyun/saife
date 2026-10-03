import type { ActionListItem } from "@/types/action";

export function actionRow(over: Partial<ActionListItem> = {}): ActionListItem {
  return {
    id: 1,
    content: "차양부 천장 작업 시 이동식 비계(안전난간) 사용",
    owner: "생산반장 김철수",
    dueDate: "2026-10-12",
    status: "PENDING",
    overdueDays: null,
    completedAt: null,
    priority: "ELIMINATION",
    guideRef: "D-C-7-2026",
    hazardId: 1,
    accidentType: "FALL",
    missingControl: "작업발판 미확보",
    equipmentId: 3,
    equipmentName: "이동식 사다리 A",
    assessmentId: 1,
    ...over,
  };
}
