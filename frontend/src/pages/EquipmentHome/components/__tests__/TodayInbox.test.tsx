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
  it("행마다 종류, 문장, 설비, D-day, 행동 버튼 하나를 보인다", () => {
    renderInbox([item({})]);
    expect(screen.getByText("기한 경과 조치")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "차양부 안전대 부착설비 설치" })).toHaveAttribute("href", "/equipment/1");
    expect(screen.getByText("D+42").className).toContain("text-risk-high-text");
    expect(screen.getByRole("link", { name: "사진 점검" })).toHaveAttribute("href", "/vision?equipmentId=1");
  });

  it("조사표 버튼은 법정 서식을 새 탭으로 연다", () => {
    renderInbox([item({ kind: "REPORT_DUE", emphasis: "WARNING", title: "사다리 추락 사고 산업재해조사표", refId: 7, daysRemaining: 20, linkType: "INCIDENT" })]);
    const link = screen.getByRole("link", { name: "조사표" });
    expect(link).toHaveAttribute("href", "/form/incident/7");
    expect(link).toHaveAttribute("target", "_blank");
  });

  it("7행이 넘으면 6행만 보이고 나머지는 펼쳐 본다", () => {
    const items = Array.from({ length: 8 }, (_, i) => item({ title: `조치 ${i}`, refId: i }));
    renderInbox(items);
    expect(screen.queryByText("조치 7")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "나머지 2건 보기" }));
    expect(screen.getByText("조치 7")).toBeInTheDocument();
  });

  it("필터가 걸려 있으면 해제 버튼을 보이고, 누르면 onClearFilter를 부른다", () => {
    const onClearFilter = vi.fn();
    renderInbox([item({})], { filterLabel: "기한 경과 조치", onClearFilter });
    fireEvent.click(screen.getByRole("button", { name: /기한 경과 조치만 보기/ }));
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
