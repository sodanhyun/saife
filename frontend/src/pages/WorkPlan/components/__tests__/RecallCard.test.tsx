import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import RecallCard from "@/pages/WorkPlan/components/RecallCard";
import type { RecallView } from "@/types/timeline";

const baseRecall: RecallView = {
  equipmentId: 1,
  equipmentName: "이동식 사다리 A",
  locationTag: "공장동 후면 차양부",
  headline: "백엔드 요약 문장",
  predicted: false,
  warnedAt: null,
  priorHazards: [
    {
      hazardId: 10,
      accidentType: "FALL",
      missingControl: "작업발판 미확보",
      description: null,
      lastRiskLevel: "HIGH",
      lastAssessedOn: "2026-09-02",
      lastRuleTrace: null,
      sameAxisAsIncident: false,
    },
  ],
  unfinishedActions: [
    { actionId: 100, content: "차양부 천장 작업 시 이동식 비계(안전난간) 사용", dueDate: "2026-09-02", status: "OVERDUE", overdueDays: 30, guideRef: null },
  ],
  priorWorkPlans: [],
  priorIncidents: [],
  knownSlots: ["장소", "설비", "최근 평가 등급", "미이행 조치"],
};

describe("RecallCard", () => {
  it("설비명과 위치, 최근 평가 등급, 미이행 수 칩을 보인다", () => {
    render(<RecallCard recall={baseRecall} />);
    expect(screen.getByText("이동식 사다리 A")).toBeInTheDocument();
    expect(screen.getByText("공장동 후면 차양부")).toBeInTheDocument();
    expect(screen.getByText("상")).toBeInTheDocument();
    expect(screen.getByText("미이행 1")).toBeInTheDocument();
  });

  it("지적 사항과 미이행 조치를 경과일과 함께 보이고 설명 문장은 쓰지 않는다", () => {
    render(<RecallCard recall={baseRecall} />);
    expect(screen.getByText("작업발판 미확보")).toBeInTheDocument();
    expect(screen.getByText("09-02")).toBeInTheDocument();
    expect(screen.getByText("차양부 천장 작업 시 이동식 비계(안전난간) 사용")).toBeInTheDocument();
    expect(screen.getByText("30일 경과")).toBeInTheDocument();
    expect(screen.queryByText("백엔드 요약 문장")).toBeNull();
    expect(screen.queryByText(/묻지 않고 채운 값|기억하는/)).toBeNull();
  });

  it("사고가 있으면 사고 칩을 보인다", () => {
    render(<RecallCard recall={{ ...baseRecall, priorIncidents: [{ incidentId: 1, occurredAt: "2026-08-18T10:20:00+09:00", accidentType: "FALL", description: "d" }] }} />);
    expect(screen.getByText("사고 1")).toBeInTheDocument();
  });

  it("대화가 시작되면 한 줄 막대로 접혀 목록을 감춘다", () => {
    render(<RecallCard recall={baseRecall} collapsed />);
    expect(screen.getByText("이동식 사다리 A")).toBeInTheDocument();
    expect(screen.getByText("미이행 1")).toBeInTheDocument();
    expect(screen.queryByText("작업발판 미확보")).toBeNull();
  });

  it("미이행 조치가 있으면 high 톤, 없으면 neutral 톤이다", () => {
    const { container, rerender } = render(<RecallCard recall={baseRecall} />);
    expect(container.firstChild).toHaveClass("border-risk-high-border");
    rerender(<RecallCard recall={{ ...baseRecall, unfinishedActions: [] }} />);
    expect(container.firstChild).toHaveClass("border-slate-200");
    expect(screen.queryByText("30일 경과")).toBeNull();
  });

  it("스크린리더가 조용히 알 수 있도록 role=status, aria-live=polite를 갖는다", () => {
    const { container } = render(<RecallCard recall={baseRecall} />);
    expect(container.firstChild).toHaveAttribute("role", "status");
    expect(container.firstChild).toHaveAttribute("aria-live", "polite");
  });
});
