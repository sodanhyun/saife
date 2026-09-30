import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

vi.mock("@/api/dashboardApi", () => ({ dashboardApi: { cards: vi.fn(), today: vi.fn() } }));

import { dashboardApi } from "@/api/dashboardApi";
import EquipmentHomePage from "@/pages/EquipmentHome/EquipmentHomePage";
import type { EquipmentCard } from "@/types/timeline";

const mockCards = vi.mocked(dashboardApi.cards);
const mockToday = vi.mocked(dashboardApi.today);

function card(id: number, name: string, emphasis: EquipmentCard["emphasis"]): EquipmentCard {
  return {
    id,
    name,
    locationTag: null,
    processName: null,
    currentRiskLevel: null,
    currentRiskAxis: null,
    lastAssessedOn: null,
    unfinishedActionCount: 0,
    overdueActionCount: 0,
    upcomingWorkPlanCount: 0,
    incidentCount: 0,
    lastEventOn: null,
    emphasis,
    headline: `헤드라인 ${id}`,
  };
}

beforeEach(() => {
  mockCards.mockReset();
  mockToday.mockReset();
  // 백엔드 4a 전에는 500 — 인박스는 조용히 아무것도 그리지 않아야 하고, 이 페이지 테스트는
  // 설비 카드에만 관심이 있으므로 그 상태를 그대로 흉내낸다.
  mockToday.mockRejectedValue(new Error("dashboard/today not implemented yet"));
});

describe("EquipmentHomePage", () => {
  it("설비 카드 6장을 백엔드가 준 순서 그대로 렌더한다(프론트에서 재정렬하지 않는다)", async () => {
    // 백엔드 정렬 규칙(emphasis→lastEventOn→id)이 이미 반영된 순서를 그대로 흉내낸다 —
    // 프론트는 이 순서를 검증하는 게 아니라 "그대로 보여주는지"만 검증한다(정렬 로직은 백엔드 단위 테스트가 이미 박아 둠).
    const cards = [
      card(1, "이동식 사다리 A", "CRITICAL"),
      card(2, "지게차", "WARNING"),
      card(6, "고소작업대", "WARNING"),
      card(3, "컨베이어", "WARNING"),
      card(4, "도장부스", "NORMAL"),
      card(5, "프레스", "NORMAL"),
    ];
    mockCards.mockResolvedValue(cards);

    render(
      <MemoryRouter>
        <EquipmentHomePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(6));
    const names = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    expect(names).toEqual(["이동식 사다리 A", "지게차", "고소작업대", "컨베이어", "도장부스", "프레스"]);
  });

  it("CRITICAL 카드에는 '긴급' 배지가, WARNING 카드에는 '주의' 배지가 붙는다", async () => {
    mockCards.mockResolvedValue([card(1, "이동식 사다리 A", "CRITICAL"), card(2, "지게차", "WARNING")]);

    render(
      <MemoryRouter>
        <EquipmentHomePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("긴급")).toBeInTheDocument());
    expect(screen.getByText("주의")).toBeInTheDocument();
  });
});
