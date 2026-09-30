import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import RecallCard from "@/pages/WorkPlan/components/RecallCard";
import type { RecallView } from "@/types/timeline";

const baseRecall: RecallView = {
  equipmentId: 1,
  equipmentName: "이동식 사다리 A",
  locationTag: "공장동 후면 차양부",
  headline: "최근 평가 '상'(추락) · 미이행 조치 1건(기한 38일 경과)",
  predicted: false,
  warnedAt: null,
  priorHazards: [
    {
      hazardId: 10,
      accidentType: "FALL",
      missingControl: "안전대 부착설비 미설치",
      description: null,
      lastRiskLevel: "HIGH",
      lastAssessedOn: "2026-08-01",
      lastRuleTrace: null,
      sameAxisAsIncident: false,
    },
  ],
  unfinishedActions: [
    { actionId: 100, content: "안전대 부착설비 설치", dueDate: "2026-07-01", status: "OVERDUE", overdueDays: 38, guideRef: null },
  ],
  priorWorkPlans: [],
  priorIncidents: [],
  knownSlots: ["장소", "설비", "최근 평가 등급", "미이행 조치"],
};

describe("RecallCard", () => {
  it("knownSlots를 칩 줄로 렌더한다(값은 RecallView에서만 온다)", () => {
    render(<RecallCard recall={baseRecall} />);
    expect(screen.getByText("이미 알고 있어 묻지 않음: 장소 · 설비 · 최근 평가 등급 · 미이행 조치")).toBeInTheDocument();
  });

  it("headline·위험요인·미이행 조치를 렌더한다", () => {
    render(<RecallCard recall={baseRecall} />);
    expect(screen.getByText(baseRecall.headline)).toBeInTheDocument();
    expect(screen.getByText("추락")).toBeInTheDocument();
    expect(screen.getByText("안전대 부착설비 미설치")).toBeInTheDocument();
    expect(screen.getByText("안전대 부착설비 설치")).toBeInTheDocument();
    expect(screen.getByText("38일 경과")).toBeInTheDocument();
  });

  it("미이행 조치가 있으면 high 톤(위험 테두리)을 쓴다", () => {
    const { container } = render(<RecallCard recall={baseRecall} />);
    expect(container.firstChild).toHaveClass("border-risk-high-border");
  });

  it("미이행 조치가 없으면 neutral 톤을 쓰고 '경과' 배지가 없다", () => {
    const noAction: RecallView = { ...baseRecall, unfinishedActions: [] };
    const { container } = render(<RecallCard recall={noAction} />);
    expect(container.firstChild).toHaveClass("border-slate-200");
    expect(screen.queryByText("38일 경과")).toBeNull();
  });

  it("스크린리더가 조용히 알 수 있도록 role=status·aria-live=polite를 갖는다", () => {
    const { container } = render(<RecallCard recall={baseRecall} />);
    expect(container.firstChild).toHaveAttribute("role", "status");
    expect(container.firstChild).toHaveAttribute("aria-live", "polite");
  });

  it("같은_설비_중복_없음 — ai.recall로 같은 equipmentId가 다시 오면 카드 1장에 최신 headline만 남는다", () => {
    const updated: RecallView = { ...baseRecall, headline: "최근 평가 '상'(추락) · 미이행 조치 0건" };
    const { rerender } = render(<RecallCard recall={baseRecall} />);
    expect(screen.getByText(baseRecall.headline)).toBeInTheDocument();

    rerender(<RecallCard recall={updated} />);
    expect(screen.queryByText(baseRecall.headline)).toBeNull();
    expect(screen.getAllByText(updated.headline)).toHaveLength(1);
  });
});
