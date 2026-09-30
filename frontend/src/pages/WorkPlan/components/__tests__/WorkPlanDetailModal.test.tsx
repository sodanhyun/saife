import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import type { Evidence } from "@/types/evidence";
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

  it("evidence가 없으면 참고 자료 섹션을 그리지 않는다", () => {
    render(<WorkPlanDetailModal detail={detail} busyAction={null} onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    expect(screen.queryByText(/참고 자료/)).toBeNull();
  });

  it("evidence가 빈 배열이면 참고 자료 섹션을 그리지 않는다", () => {
    render(<WorkPlanDetailModal detail={{ ...detail, evidence: [] }} busyAction={null} onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    expect(screen.queryByText(/참고 자료/)).toBeNull();
  });

  it("evidence가 있으면 참고 자료 그리드를 그린다", () => {
    const evidence: Evidence[] = [{
      no: 1, kind: "GUIDE", refId: 1, refKey: "G:1", title: "근거 1", snippet: "",
      sourceUrl: null, mediaUrl: null, thumbnailUrl: null, origin: "CACHE", score: 0.5,
      fetchedAt: "2026-09-28T00:00:00+09:00", meta: {},
    }];
    render(<WorkPlanDetailModal detail={{ ...detail, evidence }} busyAction={null} onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    expect(screen.getByText("참고 자료 1건 펼치기")).toBeInTheDocument();
  });
});
