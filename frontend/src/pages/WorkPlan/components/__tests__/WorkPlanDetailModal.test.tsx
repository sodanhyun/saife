import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import { ladderDetail } from "@/pages/WorkPlan/components/__tests__/fixtures";
import type { WorkPlanDetail } from "@/types/workPlan";

const noop = () => {};
const noInterim: WorkPlanDetail = {
  ...ladderDetail,
  briefingView: ladderDetail.briefingView ? { ...ladderDetail.briefingView, interimRequired: false } : null,
};

const renderModal = (detail: WorkPlanDetail, extra: Partial<React.ComponentProps<typeof WorkPlanDetailModal>> = {}) =>
  render(
    <MemoryRouter>
      <WorkPlanDetailModal detail={detail} busyAction={null} onClose={noop} onApprove={noop} {...extra} />
    </MemoryRouter>,
  );

describe("WorkPlanDetailModal", () => {
  it("제목은 문서 종류이고, TBM 단계가 없다", () => {
    renderModal(noInterim);
    expect(screen.getByRole("heading", { name: "작업 전 안전점검표" })).toBeInTheDocument();
    expect(screen.queryByText(/TBM/)).toBeNull();
    expect(screen.getByRole("link", { name: "서식 출력" })).toHaveAttribute("target", "_blank");
    expect(screen.getByRole("button", { name: "승인" })).toBeEnabled();
  });

  it("작업계획서면 모달 제목도 작업계획서다", () => {
    renderModal({ ...noInterim, documentType: "WORK_PLAN", documentTitle: "작업계획서" });
    expect(screen.getByRole("heading", { name: "작업계획서" })).toBeInTheDocument();
  });

  it("승인자 기본값은 작업 담당 반장이고 승인할 때 넘긴다", () => {
    const onApprove = vi.fn();
    renderModal(noInterim, { onApprove });
    expect(screen.getByLabelText("승인자 (관리감독자)")).toHaveValue("김철수");
    fireEvent.click(screen.getByRole("button", { name: "승인" }));
    expect(onApprove).toHaveBeenCalledWith(31, "김철수");
  });

  it("상 판정에 대책이 미이행이면 잠정조치를 적어야 조건부 승인할 수 있다", () => {
    const onApprove = vi.fn();
    renderModal(ladderDetail, { onApprove });
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    const button = screen.getByRole("button", { name: "조건부 승인" });
    expect(button).toBeDisabled();

    fireEvent.change(screen.getByLabelText("잠정조치"), { target: { value: "  2인 1조, 아웃트리거 고정 " } });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(onApprove).toHaveBeenCalledWith(31, "김철수", "2인 1조, 아웃트리거 고정");
  });

  it("잠정조치가 작업 금지면 승인 대신 작업 보류로 안내한다", () => {
    const onHold = vi.fn();
    renderModal(ladderDetail, { onHold });
    fireEvent.change(screen.getByLabelText("잠정조치"), { target: { value: "이동식 비계 설치 전까지 사다리 작업 금지" } });
    expect(screen.queryByRole("button", { name: "조건부 승인" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "작업 보류" }));
    expect(onHold).toHaveBeenCalledWith(31, "이동식 비계 설치 전까지 사다리 작업 금지");
  });

  it("승인 후에는 승인 버튼 없이 서식 출력만 남는다", () => {
    renderModal({ ...noInterim, status: "APPROVED", approvedBy: "김철수", approvedAt: "2026-10-03T07:30:00+09:00" });
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    expect(screen.getByRole("link", { name: "서식 출력" })).toBeInTheDocument();
  });

  it("작업 보류면 승인 대신 수시평가가 주 버튼이다", () => {
    renderModal({ ...noInterim, status: "HOLD", holdAssessmentId: 12 });
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    expect(screen.getAllByRole("link", { name: "수시평가" })[0]).toHaveAttribute("href", "/assessment/12");
  });
});
