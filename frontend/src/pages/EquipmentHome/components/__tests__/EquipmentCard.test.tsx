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

const card = (over: Partial<EquipmentCardType> = {}): EquipmentCardType => ({
  id: 1,
  name: "이동식 사다리 A",
  locationTag: "공장동 후면 차양부",
  processName: "절단·가공 라인",
  currentRiskLevel: "HIGH",
  currentRiskAxis: "FALL",
  lastAssessedOn: "2026-08-01",
  unfinishedActionCount: 1,
  overdueActionCount: 1,
  upcomingWorkPlanCount: 0,
  incidentCount: 0,
  nearMissCount: 0,
  lastEventOn: "2026-08-01",
  emphasis: "CRITICAL",
  headline: "기한 경과 1",
  ...over,
});

function renderCard(c: EquipmentCardType) {
  return render(
    <MemoryRouter>
      <EquipmentCard card={c} />
    </MemoryRouter>,
  );
}

describe("EquipmentCard", () => {
  beforeEach(() => mockNavigate.mockReset());

  it("등급 표식과 왼쪽 띠는 현재 등급 톤이고, 상태는 칩 하나로 말한다", () => {
    const { container } = renderCard(card());
    expect(screen.getByLabelText("위험성 상")).toBeInTheDocument();
    expect(container.querySelector("span.bg-risk-high")).not.toBeNull();
    expect(screen.getByText("기한 경과 1").className).toContain("text-risk-high-text");
  });

  it("우상단은 '최근 평가 떨어짐 08-01' 한 줄이고, 아차사고는 사고 수와 따로 보인다", () => {
    renderCard(card({ incidentCount: 0, nearMissCount: 1 }));
    expect(screen.getByText("최근 평가 떨어짐 08-01")).toBeInTheDocument();
    expect(screen.getByText("아차사고 1")).toBeInTheDocument();
  });

  it("바로가기 줄은 칩과 날짜 줄과 겹치지 않는 자기 줄이다(절대 위치로 덮지 않는다)", () => {
    renderCard(card());
    const bar = screen.getByRole("button", { name: "작업 전 점검" }).parentElement!;
    expect(bar.className).not.toContain("absolute");
    expect(screen.getByText("기한 경과 1")).toBeVisible();
  });

  it("평가 기록이 없으면 등급 상자는 '미평가', 칩은 '최초 평가 필요'다", () => {
    renderCard(card({ currentRiskLevel: null, currentRiskAxis: null, lastAssessedOn: null, unfinishedActionCount: 0, overdueActionCount: 0, headline: "최초 평가 필요" }));
    expect(screen.getByText("미평가")).toBeInTheDocument();
    expect(screen.getByText("최초 평가 필요")).toBeInTheDocument();
  });

  it("사실이 없는 설비에는 칩도 서술 문장도 없다", () => {
    const { container } = renderCard(card({ currentRiskLevel: "LOW", emphasis: "NORMAL", unfinishedActionCount: 0, overdueActionCount: 0, headline: "" }));
    expect(container.textContent).not.toMatch(/습니다|없음/);
  });

  it("제목은 설비 상세 링크이고, 동사 버튼은 설비 ID를 들고 각 화면으로 간다", () => {
    renderCard(card());
    expect(screen.getByRole("link", { name: "이동식 사다리 A" })).toHaveAttribute("href", "/equipment/1");
    fireEvent.click(screen.getByRole("button", { name: "작업 전 점검" }));
    expect(mockNavigate).toHaveBeenCalledWith("/work-plan?equipmentId=1");
    fireEvent.click(screen.getByRole("button", { name: "순회점검" }));
    expect(mockNavigate).toHaveBeenCalledWith("/vision?equipmentId=1");
    fireEvent.click(screen.getByRole("button", { name: "사고 보고" }));
    expect(mockNavigate).toHaveBeenCalledWith("/incident?equipmentId=1");
  });
});
