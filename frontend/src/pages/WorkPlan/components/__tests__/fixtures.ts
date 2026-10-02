// 작업 전 점검 화면 테스트의 공통 픽스처. 시연 입력(3.2m, 맨 위 바로 아래 칸, 잡아주는 사람 없음)의 결과와 같다.
import type { WorkPlanDetail } from "@/types/workPlan";

export const ladderDetail: WorkPlanDetail = {
  id: 31, siteId: 1, equipmentId: 1, equipmentName: "이동식 사다리 A", conversationId: "c1", workName: "천장 페인트 작업",
  workPlace: "공장동 후면 차양부", workDate: "2026-10-03", workHours: null, method: null, notes: null, briefing: "TBM",
  briefingAckAt: null, status: "SUBMITTED", approvalNote: null, approvedBy: null, approvedAt: null,
  documentType: "TBM_CHECKLIST", documentTitle: "작업 전 안전점검표 (TBM)",
  slots: [
    { slotKey: "work_height", label: "발판 높이", displayValue: "3.2 m", question: "사다리 발판 높이가 바닥에서 몇 m입니까?", ledgerValue: null, answeredValue: "3.2m요", conflicted: false, answeredAt: null },
    { slotKey: "top_step", label: "최상부 디딤대", displayValue: "사용", question: "맨 위 발판이나 그 바로 아래 칸에 올라섭니까?", ledgerValue: null, answeredValue: "맨 위 바로 아래 칸까지 올라가요", conflicted: false, answeredAt: null },
  ],
  workers: [{ id: 1, name: "김철수", position: "반장", duty: null }],
  briefingView: {
    pendingActions: [{ content: "차양부 천장 작업 시 이동식 비계(안전난간) 사용", dueDate: "2026-09-02", overdueDays: 30, lastGrade: "HIGH" }],
    decisions: [{
      accidentType: "FALL", label: "떨어짐", riskLevel: "HIGH", frequency: 3, severity: 3,
      ruleTrace: "발판 높이 3.2m, 최상부 발판 또는 그 하단 디딤대 사용, 넘어짐 방지(아웃트리거, 고정, 지지자) 없음 (제42조④)",
      recommendation: "이동식 비계(안전난간) 또는 말비계로 작업발판 확보 (제42조①)",
    }],
    msds: { chemName: "톨루엔", productName: "노루 유성페인트", inferred: true, lines: [{ item: "유해성", text: "H225 고인화성 액체 및 증기" }] },
    riskPoints: ["사다리 맨 위나 바로 아래 칸에 서면 중심을 잃고 떨어질 수 있습니다"],
    keepPoints: ["사다리 대신 이동식 비계(안전난간)나 말비계를 씁니다"],
    interimRequired: true,
  },
};
