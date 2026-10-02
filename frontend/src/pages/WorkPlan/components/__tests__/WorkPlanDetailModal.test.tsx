import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import { ladderDetail } from "@/pages/WorkPlan/components/__tests__/fixtures";
import type { WorkPlanDetail } from "@/types/workPlan";

const noop = () => {};
const noInterim: WorkPlanDetail = {
  ...ladderDetail,
  briefingView: ladderDetail.briefingView ? { ...ladderDetail.briefingView, interimRequired: false } : null,
};

describe("WorkPlanDetailModal", () => {
  it("제목은 작업 전 안전점검표이고 TBM 실시 확인과 서식 출력이 있다", () => {
    render(<WorkPlanDetailModal detail={noInterim} busyAction={null} onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    expect(screen.getByRole("heading", { name: "작업 전 안전점검표" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "TBM 실시 확인" })).toBeEnabled();
    expect(screen.getByRole("link", { name: "서식 출력" })).toHaveAttribute("target", "_blank");
    expect(screen.getByRole("button", { name: "승인" })).toBeEnabled();
  });

  it("상 판정에 대책이 미이행이면 잠정조치를 적어야 조건부 승인할 수 있다", () => {
    const onApprove = vi.fn();
    render(<WorkPlanDetailModal detail={ladderDetail} busyAction={null} onClose={noop} onAcknowledge={noop} onApprove={onApprove} />);
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    const button = screen.getByRole("button", { name: "조건부 승인" });
    expect(button).toBeDisabled();

    fireEvent.change(screen.getByLabelText("잠정조치"), { target: { value: "  이동식 비계 설치 전 사다리 작업 금지 " } });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(onApprove).toHaveBeenCalledWith(31, "이동식 비계 설치 전 사다리 작업 금지");
  });

  it("누른 액션 버튼만 처리 중으로 바뀌고 다른 버튼은 비활성만 된다", () => {
    render(<WorkPlanDetailModal detail={noInterim} busyAction="approve" onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    expect(screen.getByRole("button", { name: "TBM 실시 확인" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "처리 중…" })).toBeDisabled();
  });

  it("승인된 점검표에는 승인 버튼이 없고 TBM 실시 시각을 보인다", () => {
    render(<WorkPlanDetailModal detail={{ ...noInterim, status: "APPROVED", briefingAckAt: "2026-10-03T07:40:00+09:00" }}
      busyAction={null} onClose={noop} onAcknowledge={noop} onApprove={noop} />);
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    expect(screen.queryByRole("button", { name: "TBM 실시 확인" })).toBeNull();
    expect(screen.getByText("TBM 실시 10-03 07:40")).toBeInTheDocument();
  });
});
