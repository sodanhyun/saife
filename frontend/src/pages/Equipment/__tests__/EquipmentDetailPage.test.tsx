import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import type { EquipmentTimeline, TimelineEvent } from "@/types/timeline";

const ev = (id: string, over: Partial<TimelineEvent> = {}): TimelineEvent => ({
  id, type: "ASSESSMENT", at: "2026-08-21", occurredAt: null, title: "상시평가", detail: "위험요인 1건 평가", riskLevel: "HIGH",
  accidentType: "FALL", status: "CONFIRMED", refId: 1, linkedEventIds: [], linkedLabels: [], causalOrder: 0, emphasis: "WARNING",
  ruleTrace: "작업높이 3.2m (2m 초과) + 안전대 부착설비 없음 → '상'", imageUrl: null, ...over,
});

const timeline: EquipmentTimeline = {
  equipment: { id: 1, name: "이동식 사다리 A", locationTag: "공장동 후면 차양부", processName: "표면처리 라인", objectCode: "M-0412", introducedOn: "2023-04-11" },
  summary: {
    currentRiskLevel: "HIGH", currentRiskAxis: "FALL", lastAssessedOn: "2026-08-21", assessmentCount: 1, workPlanCount: 0,
    incidentCount: 0, nearMissCount: 0, unfinishedActionCount: 1, overdueActionCount: 1, headline: "기한 경과 1",
  },
  events: [
    ev("assessment-1"),
    ev("action-1", { type: "ACTION", title: "차양부 천장 작업 시 이동식 비계(안전난간) 사용", detail: "기한 경과, 미이행", riskLevel: null, accidentType: null, status: "OVERDUE", linkedEventIds: ["assessment-1"], ruleTrace: null }),
  ],
};

const state = { notFound: false };
vi.mock("@/pages/Equipment/hooks/useEquipmentDetail", () => ({
  useEquipmentDetail: () => ({
    timeline: state.notFound ? null : timeline, notFound: state.notFound, loading: false, loadError: false, refetch: vi.fn(),
  }),
}));

import EquipmentDetailPage from "@/pages/Equipment/EquipmentDetailPage";

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/equipment/:equipmentId" element={<EquipmentDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("EquipmentDetailPage", () => {
  beforeEach(() => { state.notFound = false; });

  it("없는 설비는 '설비를 찾을 수 없습니다'와 설비 현황 버튼만 보인다", () => {
    state.notFound = true;
    renderAt("/equipment/999");
    expect(screen.getByText("설비를 찾을 수 없습니다")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "설비 현황" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "작업 전 점검" })).toBeNull();
  });

  it("제목은 설비명, 섹션은 '이력', 행동은 작업 전 점검/순회점검/사고 보고다", () => {
    renderAt("/equipment/1");
    expect(screen.getByRole("heading", { level: 1, name: "이동식 사다리 A" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "이력" })).toBeInTheDocument();
    ["작업 전 점검", "순회점검", "사고 보고"].forEach((name) => expect(screen.getByRole("button", { name })).toBeInTheDocument());
    expect(screen.getByText("M-0412 / 공장동 후면 차양부 / 표면처리 라인 / 2023-04-11 도입")).toBeInTheDocument();
    expect(screen.getByText("기한 경과 1")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/기억|룰|→|'상'/);
  });

  it("?focus=로 들어오면 그 사건을 강조한 채로 연다", () => {
    renderAt("/equipment/1?focus=action-1");
    const card = screen.getByText("차양부 천장 작업 시 이동식 비계(안전난간) 사용").closest("[role=button]")!;
    expect(card).toHaveAttribute("aria-pressed", "true");
  });
});
