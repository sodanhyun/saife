import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import EquipmentCard from "@/pages/EquipmentHome/components/EquipmentCard";
import type { EquipmentCard as EquipmentCardType } from "@/types/timeline";

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => mockNavigate };
});

const card = (emphasis: EquipmentCardType["emphasis"]): EquipmentCardType => ({
  id: 1,
  name: "이동식 사다리 A",
  locationTag: "공장동 후면 차양부",
  processName: "도장",
  currentRiskLevel: "HIGH",
  currentRiskAxis: "FALL",
  lastAssessedOn: "2026-08-01",
  unfinishedActionCount: 1,
  overdueActionCount: 1,
  upcomingWorkPlanCount: 0,
  incidentCount: 0,
  lastEventOn: "2026-08-01",
  emphasis,
  headline: "최근 평가 '상'(추락) · 미이행 조치 1건(기한 38일 경과)",
});

function renderCard(c: EquipmentCardType) {
  return render(
    <MemoryRouter>
      <EquipmentCard card={c} />
    </MemoryRouter>,
  );
}

describe("EquipmentCard — 카드 테두리·배지 톤은 emphasis로만 정한다", () => {
  beforeEach(() => {
    mockNavigate.mockReset();
  });

  it("CRITICAL이면 위험 톤 배지 '긴급'과 위험 테두리를 가진다", () => {
    const { container } = renderCard(card("CRITICAL"));
    const badge = screen.getByText("긴급");
    expect(badge.className).toContain("risk-high");
    expect(container.querySelector('[role="button"]')!.className).toContain("border-risk-high-border");
  });

  it("WARNING이면 대기 톤 배지 '주의'를 가진다", () => {
    const { container } = renderCard(card("WARNING"));
    const badge = screen.getByText("주의");
    expect(badge.className).toContain("pending");
    expect(container.querySelector('[role="button"]')!.className).toContain("border-pending-border");
  });

  it("NORMAL이면 강조 배지가 없다", () => {
    renderCard(card("NORMAL"));
    expect(screen.queryByText("긴급")).toBeNull();
    expect(screen.queryByText("주의")).toBeNull();
  });

  it("카드를 클릭하면 설비 상세로 이동하고, 동사 버튼은 자기 경로로 이동하며 카드 클릭을 막는다", () => {
    renderCard(card("CRITICAL"));
    fireEvent.click(screen.getByRole("button", { name: "작업 신고" }));
    expect(mockNavigate).toHaveBeenCalledWith("/work-plan?equipmentId=1");
    mockNavigate.mockReset();
    fireEvent.click(screen.getByText("이동식 사다리 A"));
    expect(mockNavigate).toHaveBeenCalledWith("/equipment/1");
  });
});
