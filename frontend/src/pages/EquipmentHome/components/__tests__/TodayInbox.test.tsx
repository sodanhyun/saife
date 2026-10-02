import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import TodayInbox from "@/pages/EquipmentHome/components/TodayInbox";
import { buildTodayRows } from "@/pages/EquipmentHome/utils/todayModel";
import type { TodayItem, TodayView } from "@/types/timeline";

function item(overrides: Partial<TodayItem>): TodayItem {
  return {
    kind: "OVERDUE_ACTION",
    emphasis: "CRITICAL",
    title: "차양부 안전대 부착설비 설치",
    detail: "기한 2026-08-21",
    equipmentId: 1,
    equipmentName: "이동식 사다리 A",
    dueDate: "2026-08-21",
    daysRemaining: -42,
    linkType: "EQUIPMENT",
    refId: 1,
    assessmentId: null,
    ...overrides,
  };
}

function view(items: TodayItem[]): TodayView {
  return {
    asOf: "2026-10-02",
    items,
    criticalCount: items.filter((i) => i.emphasis === "CRITICAL").length,
    warningCount: items.filter((i) => i.emphasis === "WARNING").length,
  };
}

function renderInbox(items: TodayItem[], extra: Partial<React.ComponentProps<typeof TodayInbox>> = {}) {
  return render(
    <MemoryRouter>
      <TodayInbox view={view(items)} rows={buildTodayRows(items)} filterLabel={null} onClearFilter={vi.fn()} onRefresh={vi.fn()} {...extra} />
    </MemoryRouter>,
  );
}

describe("TodayInbox", () => {
  it("행마다 종류, 문장, 설비, 경과일, 행동 버튼 하나를 보인다", () => {
    renderInbox([item({})]);
    expect(screen.getByText("기한 경과 조치")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "차양부 안전대 부착설비 설치" })).toHaveAttribute("href", "/equipment/1");
    expect(screen.getByText("42일 경과").className).toContain("text-risk-high-text");
    expect(screen.getByRole("link", { name: "조치 확인" })).toHaveAttribute("href", "/equipment/1?focus=action-1");
  });

  it("작업 보류 행은 '수시평가' 버튼(수시평가 화면)을 달고, 날짜 칸에 작업일을 두며, 버튼은 모두 같은 모양이다", () => {
    renderInbox([
      item({ kind: "WORK_HOLD", title: "차양부 천장 도장", detail: "수시평가 완료 전 작업 재개 금지", daysRemaining: null, dueDate: "2026-10-04", linkType: "WORK_PLAN", refId: 3, assessmentId: 12 }),
      item({ kind: "PENDING_APPROVAL", emphasis: "WARNING", title: "조명 교체", linkType: "WORK_PLAN", refId: 4, daysRemaining: 1 }),
    ]);
    expect(screen.getByText("작업 보류")).toBeInTheDocument();
    expect(screen.getByText("수시평가 완료 전 작업 재개 금지")).toBeInTheDocument();
    const hold = screen.getByRole("link", { name: "수시평가" });
    const review = screen.getByRole("link", { name: "검토" });
    expect(hold).toHaveAttribute("href", "/assessment/12");
    expect(screen.getByText("작업일 10-04")).toBeInTheDocument();
    expect(review).toHaveAttribute("href", "/work-plan?planId=4");
    expect(hold.className).toBe(review.className);
  });

  it("조사표는 기한을 날짜로 보이고, 버튼은 그 사고 화면을 연다", () => {
    renderInbox([item({ kind: "REPORT_DUE", emphasis: "WARNING", title: "떨어짐 사고 10-02", refId: 7, dueDate: "2026-11-02", daysRemaining: 31, linkType: "INCIDENT" })]);
    expect(screen.getByText("기한 11-02")).toBeInTheDocument();
    expect(screen.queryByText("D-31")).toBeNull();
    const link = screen.getByRole("link", { name: "조사표 작성" });
    expect(link).toHaveAttribute("href", "/incident?incidentId=7");
  });

  it("수시평가 id가 없는 작업 보류는 사고 보고 화면으로 간다", () => {
    renderInbox([item({ kind: "WORK_HOLD", daysRemaining: null, linkType: "WORK_PLAN", refId: 3, assessmentId: null })]);
    expect(screen.getByRole("link", { name: "수시평가" })).toHaveAttribute("href", "/incident");
  });

  it("7행이 넘으면 6행만 보이고 나머지는 펼쳐 보며, 펼친 뒤에는 접는다", () => {
    const items = Array.from({ length: 8 }, (_, i) => item({ title: `조치 ${i}`, refId: i }));
    renderInbox(items);
    expect(screen.queryByText("조치 7")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "2건 더 보기" }));
    expect(screen.getByText("조치 7")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "접기" }));
    expect(screen.queryByText("조치 7")).toBeNull();
  });

  it("잠정조치가 걸린 기한 경과 조치는 보조 줄에 '잠정조치 중'을 그대로 보인다", () => {
    renderInbox([item({ detail: "담당 생산반장 김철수, 잠정조치 중" })]);
    expect(screen.getByText("담당 생산반장 김철수, 잠정조치 중")).toBeInTheDocument();
  });

  it("필터가 걸려 있으면 해제 버튼을 보이고, 누르면 onClearFilter를 부른다", () => {
    const onClearFilter = vi.fn();
    renderInbox([item({})], { filterLabel: "기한 경과 조치", onClearFilter });
    fireEvent.click(screen.getByRole("button", { name: /기한 경과 조치/ }));
    expect(onClearFilter).toHaveBeenCalled();
  });

  it("view가 없으면 아무것도 그리지 않는다", () => {
    const { container } = render(
      <MemoryRouter>
        <TodayInbox view={null} rows={[]} filterLabel={null} onClearFilter={vi.fn()} onRefresh={vi.fn()} />
      </MemoryRouter>,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
