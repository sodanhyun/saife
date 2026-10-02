import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import WorkPlanRecords from "@/pages/WorkPlan/components/WorkPlanRecords";
import type { WorkPlanListItem } from "@/types/workPlan";

const row = (id: number): WorkPlanListItem => ({
  id, equipmentId: 16, equipmentName: "천장크레인 1호", workName: `금형 인양 ${id}`, workPlace: null,
  workDate: "2026-10-04", status: "HOLD", briefingAcknowledged: false, briefingAckAt: null, documentType: "WORK_PLAN",
});

const base = {
  plans: [row(1), row(2)], total: 12, hasMore: true, keyword: "", status: null, loadError: false,
  onKeyword: () => {}, onStatus: () => {}, onMore: () => {}, onOpen: () => {}, onRetry: () => {},
};

describe("WorkPlanRecords", () => {
  it("검색어와 상태 필터를 넘기고 더 보기를 보인다", () => {
    const onKeyword = vi.fn();
    const onStatus = vi.fn();
    const onMore = vi.fn();
    render(<WorkPlanRecords {...base} onKeyword={onKeyword} onStatus={onStatus} onMore={onMore} />);

    fireEvent.change(screen.getByLabelText("작업명, 설비 검색"), { target: { value: "크레인" } });
    expect(onKeyword).toHaveBeenCalledWith("크레인");
    fireEvent.change(screen.getByLabelText("상태"), { target: { value: "HOLD" } });
    expect(onStatus).toHaveBeenCalledWith("HOLD");
    fireEvent.click(screen.getByRole("button", { name: "더 보기 (2/12)" }));
    expect(onMore).toHaveBeenCalled();
    expect(screen.getAllByText("작업계획서")).toHaveLength(2);
  });

  it("오류면 빈 상태 대신 불러오지 못했다는 안내와 새로고침만 보인다", () => {
    render(<WorkPlanRecords {...base} plans={[]} total={0} hasMore={false} loadError />);
    expect(screen.getByText("불러오지 못했습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새로고침" })).toBeInTheDocument();
    expect(screen.queryByText("점검 기록 없음")).toBeNull();
  });

  it("검색 결과가 없으면 조건에 맞는 기록이 없다고 말한다", () => {
    render(<WorkPlanRecords {...base} plans={[]} total={0} hasMore={false} keyword="없는 작업" />);
    expect(screen.getByText("조건에 맞는 점검 기록 없음")).toBeInTheDocument();
  });
});
