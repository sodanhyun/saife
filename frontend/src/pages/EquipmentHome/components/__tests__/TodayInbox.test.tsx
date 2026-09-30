import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

vi.mock("@/api/dashboardApi", () => ({ dashboardApi: { today: vi.fn() } }));

import { dashboardApi } from "@/api/dashboardApi";
import TodayInbox from "@/pages/EquipmentHome/components/TodayInbox";
import type { TodayItem, TodayView } from "@/types/timeline";

const mockToday = vi.mocked(dashboardApi.today);

function item(overrides: Partial<TodayItem>): TodayItem {
  return {
    kind: "OVERDUE_ACTION",
    emphasis: "CRITICAL",
    title: "기한 초과 조치 — 차양부 안전대 부착설비 설치",
    detail: "32일 경과",
    equipmentId: 10,
    equipmentName: "이동식 사다리 A",
    dueDate: "2026-08-28",
    daysRemaining: -32,
    linkType: "EQUIPMENT",
    refId: null,
    ...overrides,
  };
}

function view(items: TodayItem[], overrides: Partial<TodayView> = {}): TodayView {
  const criticalCount = items.filter((i) => i.emphasis === "CRITICAL").length;
  const warningCount = items.filter((i) => i.emphasis === "WARNING").length;
  return { asOf: "2026-09-29", items, criticalCount, warningCount, ...overrides };
}

function renderInbox() {
  return render(
    <MemoryRouter>
      <TodayInbox />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mockToday.mockReset();
});

describe("TodayInbox — 기억이 만든 오늘 할 일 인박스", () => {
  it("항목의 톤·title·detail·D-day를 렌더한다", async () => {
    mockToday.mockResolvedValue(
      view([
        item({ title: "기한 초과 조치 A", detail: "차양부 안전대 부착설비", daysRemaining: -32 }),
        item({
          kind: "DUE_ACTION",
          emphasis: "WARNING",
          title: "조치 기한 D-7 — 고소작업대 안전난간 보수",
          detail: "곧 도래",
          daysRemaining: 7,
          equipmentId: 11,
        }),
      ]),
    );

    renderInbox();

    expect(await screen.findByText("기한 초과 조치 A")).toBeInTheDocument();
    expect(screen.getByText("차양부 안전대 부착설비")).toBeInTheDocument();
    // D-day는 daysRemaining으로만 파생한다(utils/datetime.dDayLabel, 앱 전체 공통 표기) —
    // 음수는 "D+n", 양수는 "D-n"
    expect(screen.getByText("D+32")).toBeInTheDocument();
    expect(screen.getByText("D-7")).toBeInTheDocument();
  });

  it("긴급·주의 배지는 각 카운트가 0보다 클 때만 보인다", async () => {
    mockToday.mockResolvedValue(view([item({ emphasis: "CRITICAL" })]));
    renderInbox();

    expect(await screen.findByText("긴급 1")).toBeInTheDocument();
    expect(screen.queryByText(/^주의/)).toBeNull();
  });

  it("배지 카운트가 둘 다 0이면 배지를 그리지 않는다(0건 EmptyState)", async () => {
    mockToday.mockResolvedValue(view([]));
    renderInbox();

    expect(await screen.findByText("오늘 처리할 항목이 없습니다")).toBeInTheDocument();
    expect(screen.queryByText(/^긴급/)).toBeNull();
    expect(screen.queryByText(/^주의/)).toBeNull();
  });

  it("에러(백엔드 500)여도 아무것도 깨지지 않는다 — 배너 없이 조용히 빈다", async () => {
    mockToday.mockRejectedValue(new Error("500"));
    const { container } = renderInbox();

    await waitFor(() => expect(container.textContent).toBe(""));
    expect(screen.queryByText(/오늘 할 일/)).toBeNull();
  });

  it.each([
    ["EQUIPMENT", { linkType: "EQUIPMENT" as const, equipmentId: 10, refId: null }, "/equipment/10"],
    ["WORK_PLAN(딥링크 없음 → 설비 상세로 폴백)", { linkType: "WORK_PLAN" as const, equipmentId: 12, refId: 3 }, "/equipment/12"],
    ["INCIDENT", { linkType: "INCIDENT" as const, equipmentId: 13, refId: 4 }, "/incident"],
    ["ASSESSMENT(refId 있음 → 법정 서식)", { linkType: "ASSESSMENT" as const, equipmentId: 14, refId: 99 }, "/form/assessment/99"],
  ])("linkType=%s 이면 그에 맞는 경로로 이동한다", async (_label, overrides, expectedHref) => {
    mockToday.mockResolvedValue(view([item(overrides)]));
    renderInbox();

    const link = await screen.findByRole("link");
    expect(link.getAttribute("href")).toBe(expectedHref);
  });

  it("ASSESSMENT인데 refId가 없으면 설비 상세로 폴백한다", async () => {
    mockToday.mockResolvedValue(view([item({ linkType: "ASSESSMENT", equipmentId: 15, refId: null })]));
    renderInbox();

    const link = await screen.findByRole("link");
    expect(link.getAttribute("href")).toBe("/equipment/15");
  });
});
