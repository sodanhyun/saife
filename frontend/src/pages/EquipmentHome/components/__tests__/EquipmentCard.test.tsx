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
  lastEventOn: "2026-08-01",
  emphasis: "CRITICAL",
  headline: "기한이 지난 미이행 조치가 1건 있습니다 · 사고 전에 닫아야 합니다",
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

  it("등급 표식과 왼쪽 띠는 현재 등급 톤이고, 기한 경과 수를 따로 알린다", () => {
    const { container } = renderCard(card());
    expect(screen.getByLabelText("위험성 상")).toBeInTheDocument();
    expect(container.querySelector("span.bg-risk-high")).not.toBeNull();
    expect(screen.getByText("기한 경과 1")).toBeInTheDocument();
  });

  it("서버 문장의 가운뎃점은 쉼표로 바꿔 보인다", () => {
    renderCard(card());
    expect(screen.getByText("기한이 지난 미이행 조치가 1건 있습니다, 사고 전에 닫아야 합니다")).toBeInTheDocument();
  });

  it("사실이 없는 NORMAL 설비는 헤드라인 대신 흐린 '이상 없음' 문장을 쓴다", () => {
    renderCard(card({ emphasis: "NORMAL", unfinishedActionCount: 0, overdueActionCount: 0, headline: "현재 미이행 조치와 사고 이력이 없습니다." }));
    expect(screen.getByText("미이행 조치와 사고 이력 없음").className).toContain("text-slate-400");
  });

  it("제목은 설비 상세 링크이고, 동사 버튼은 설비 ID를 들고 각 화면으로 간다", () => {
    renderCard(card());
    expect(screen.getByRole("link", { name: "이동식 사다리 A" })).toHaveAttribute("href", "/equipment/1");
    fireEvent.click(screen.getByRole("button", { name: "작업 신고" }));
    expect(mockNavigate).toHaveBeenCalledWith("/work-plan?equipmentId=1");
    fireEvent.click(screen.getByRole("button", { name: "사진 점검" }));
    expect(mockNavigate).toHaveBeenCalledWith("/vision?equipmentId=1");
    fireEvent.click(screen.getByRole("button", { name: "사고 신고" }));
    expect(mockNavigate).toHaveBeenCalledWith("/incident?equipmentId=1");
  });
});
