import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import WorkPlanResultCard from "@/pages/WorkPlan/components/WorkPlanResultCard";
import type { WorkPlanDetail } from "@/types/workPlan";

const detail: WorkPlanDetail = {
  id: 31, siteId: 1, equipmentId: 1, equipmentName: "이동식 사다리 A", conversationId: "c1", workName: "천장 페인트 작업",
  workPlace: "공장동 후면 차양부", workDate: "2026-10-03", workHours: null, method: null, notes: null, briefing: "브리핑",
  briefingAckAt: null, status: "SUBMITTED", approvalNote: null, approvedBy: null, approvedAt: null,
  slots: [{ slotKey: "work_height", question: "작업 높이는 대략 몇 m인가요?", ledgerValue: null, answeredValue: "3.2", conflicted: false, answeredAt: null }],
  workers: [{ id: 1, name: "김철수", position: "반장", duty: null }],
  briefingView: {
    pendingActions: [{ content: "안전대 부착설비(앵커) 설치", dueDate: "2026-08-21", overdueDays: 42, lastGrade: "HIGH" }],
    decisions: [{ accidentType: "FALL", label: "추락", riskLevel: "HIGH", frequency: 3, severity: 3, ruleTrace: "작업높이 3.2m (2m 초과) + 안전대 부착설비 없음 → '상'" }],
    msds: { chemName: "톨루엔", productName: "유성페인트", lines: [{ item: "유해성", text: "인화성 액체" }] },
  },
};

describe("WorkPlanResultCard", () => {
  it("룰 엔진 등급 옆에 룰 근거를 같이 보인다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByLabelText("위험성 상")).toBeInTheDocument();
    expect(screen.getByText("작업높이 3.2m (2m 초과) + 안전대 부착설비 없음 → '상'")).toBeInTheDocument();
  });

  it("미이행 조치와 경과일, MSDS, 현장 확인 값을 보인다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByText("기한 42일 경과")).toBeInTheDocument();
    expect(screen.getByText("인화성 액체")).toBeInTheDocument();
    expect(screen.getByText("작업 높이")).toBeInTheDocument();
    expect(screen.getByText("3.2")).toBeInTheDocument();
  });

  it("결과 카드에는 승인 화면 버튼과 법정 서식 링크가 있다", () => {
    render(<WorkPlanResultCard detail={detail} evidence={[]} onOpenDetail={() => {}} />);
    expect(screen.getByRole("button", { name: "승인 화면 열기" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "법정 서식 보기" })).toHaveAttribute("target", "_blank");
  });
});
