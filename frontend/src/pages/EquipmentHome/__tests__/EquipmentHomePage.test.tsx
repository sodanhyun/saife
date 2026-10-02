import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

vi.mock("@/api/dashboardApi", () => ({ dashboardApi: { cards: vi.fn(), today: vi.fn() } }));

import { dashboardApi } from "@/api/dashboardApi";
import EquipmentHomePage from "@/pages/EquipmentHome/EquipmentHomePage";
import type { EquipmentCard, TodayItem } from "@/types/timeline";

const mockCards = vi.mocked(dashboardApi.cards);
const mockToday = vi.mocked(dashboardApi.today);

function card(id: number, name: string, over: Partial<EquipmentCard> = {}): EquipmentCard {
  return {
    id, name, locationTag: null, processName: null, currentRiskLevel: null, currentRiskAxis: null, lastAssessedOn: null,
    unfinishedActionCount: 0, overdueActionCount: 0, upcomingWorkPlanCount: 0, incidentCount: 0, nearMissCount: 0, lastEventOn: null,
    emphasis: "NORMAL", headline: `헤드라인 ${id}`, ...over,
  };
}

const todayItem = (o: Partial<TodayItem>): TodayItem => ({
  kind: "OVERDUE_ACTION", emphasis: "CRITICAL", title: "앵커 설치", detail: "기한 2026-08-21", equipmentId: 1,
  equipmentName: "이동식 사다리 A", dueDate: "2026-08-21", daysRemaining: -42, linkType: "EQUIPMENT", refId: 1, assessmentId: null, ...o,
});

function renderPage() {
  return render(
    <MemoryRouter>
      <EquipmentHomePage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mockCards.mockReset();
  mockToday.mockReset();
});

describe("EquipmentHomePage", () => {
  it("설비 카드는 등급 상이 먼저, 그 안에서 기한 경과가 먼저 오도록 정렬한다", async () => {
    mockToday.mockRejectedValue(new Error("down"));
    mockCards.mockResolvedValue([
      card(5, "지게차", { currentRiskLevel: "MEDIUM", emphasis: "WARNING" }),
      card(3, "컨베이어", { currentRiskLevel: "HIGH", emphasis: "WARNING" }),
      card(1, "이동식 사다리 A", { currentRiskLevel: "HIGH", overdueActionCount: 1, emphasis: "CRITICAL" }),
      card(4, "프레스"),
    ]);
    renderPage();
    await waitFor(() => expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(4));
    expect(screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent)).toEqual(["이동식 사다리 A", "컨베이어", "지게차", "프레스"]);
  });

  it("오늘 할 일이 실패하면 KPI와 인박스 없이 오류 안내와 카드만 보인다", async () => {
    mockToday.mockRejectedValue(new Error("down"));
    mockCards.mockResolvedValue([card(1, "사다리")]);
    renderPage();
    await waitFor(() => expect(screen.getByText("사다리")).toBeInTheDocument());
    expect(screen.queryByLabelText("요약")).toBeNull();
    expect(screen.queryByText("오늘 할 일")).toBeNull();
    expect(screen.getByText("불러오지 못했습니다.")).toBeInTheDocument();
  });

  it("설비 목록이 실패하면 '설비 0대'를 보이지 않는다", async () => {
    mockToday.mockRejectedValue(new Error("down"));
    mockCards.mockRejectedValue(new Error("down"));
    renderPage();
    await waitFor(() => expect(screen.getByText("불러오지 못했습니다.")).toBeInTheDocument());
    expect(screen.queryByText(/설비 0대/)).toBeNull();
    expect(screen.queryByText("등록된 설비 없음")).toBeNull();
  });

  it("고위험 설비 KPI는 설비 목록을 등급 상만 남기고, 다시 누르면 풀린다", async () => {
    mockCards.mockResolvedValue([
      card(1, "이동식 사다리 A", { currentRiskLevel: "HIGH" }),
      card(2, "지게차", { currentRiskLevel: "MEDIUM" }),
    ]);
    mockToday.mockResolvedValue({ asOf: "2026-10-02", items: [todayItem({})], criticalCount: 1, warningCount: 0 });
    renderPage();
    await waitFor(() => expect(screen.getByText("지게차")).toBeInTheDocument());
    const high = screen.getByRole("button", { name: /^고위험 설비/ });
    fireEvent.click(high);
    expect(high).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByText("지게차")).toBeNull();
    expect(screen.getByText("설비 1대")).toBeInTheDocument();
    expect(screen.getByText("앵커 설치")).toBeInTheDocument();
    fireEvent.click(high);
    expect(screen.getByText("지게차")).toBeInTheDocument();
  });

  it("KPI를 누르면 오늘 할 일이 그 종류로 좁혀지고, 다시 누르면 풀린다", async () => {
    mockCards.mockResolvedValue([card(1, "이동식 사다리 A", { currentRiskLevel: "HIGH" })]);
    mockToday.mockResolvedValue({
      asOf: "2026-10-02",
      items: [
        todayItem({}),
        todayItem({ kind: "PENDING_APPROVAL", emphasis: "WARNING", title: "천장 페인트 작업", linkType: "WORK_PLAN", refId: 30, daysRemaining: 1 }),
      ],
      criticalCount: 1,
      warningCount: 1,
    });
    renderPage();
    await waitFor(() => expect(screen.getByText("천장 페인트 작업")).toBeInTheDocument());
    const approval = screen.getByRole("button", { name: /^승인 대기/ });
    fireEvent.click(approval);
    expect(approval).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByText("앵커 설치")).toBeNull();
    fireEvent.click(approval);
    expect(screen.getByText("앵커 설치")).toBeInTheDocument();
  });
});
