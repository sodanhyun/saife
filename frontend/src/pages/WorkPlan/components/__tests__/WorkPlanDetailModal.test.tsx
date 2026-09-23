import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import type { WorkPlanDetail } from "@/types/workPlan";

const detail: WorkPlanDetail = {
  id: 7, siteId: 1, equipmentId: null, equipmentName: "도장 부스", conversationId: null, workName: "천장 도장",
  workPlace: null, workDate: "2026-09-23", workHours: null, method: null, notes: null, briefing: "사다리 2인 1조",
  briefingAckAt: null, status: "SUBMITTED", approvalNote: null, approvedBy: null, approvedAt: null, slots: [], workers: [],
};

const noop = () => {};

describe("WorkPlanDetailModal", () => {
  it("누른 액션 버튼만 처리 중으로 바뀌고 다른 버튼은 비활성만 된다", () => {
    render(<WorkPlanDetailModal detail={detail} busyAction="approve" onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    const ack = screen.getByRole("button", { name: "브리핑 확인 (TBM 기록)" });
    expect(ack).toBeDisabled();
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    expect(screen.getByRole("button", { name: "처리 중…" })).toBeDisabled();
  });

  it("법정 서식은 새 탭으로 여는 링크다", () => {
    render(<WorkPlanDetailModal detail={detail} busyAction={null} onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    const link = screen.getByRole("link", { name: "법정 서식" });
    expect(link.getAttribute("target")).toBe("_blank");
    expect(screen.getByRole("button", { name: "승인" })).toBeEnabled();
  });
});
