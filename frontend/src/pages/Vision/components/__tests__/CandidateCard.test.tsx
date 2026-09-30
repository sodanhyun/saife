import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import CandidateCard from "@/pages/Vision/components/CandidateCard";
import type { Evidence } from "@/types/evidence";
import type { VisionCandidate } from "@/types/vision";

function ev(no: number): Evidence {
  return {
    no,
    kind: "GUIDE",
    refId: no,
    refKey: `G:${no}`,
    title: `근거 ${no}`,
    snippet: "",
    sourceUrl: null,
    mediaUrl: null,
    thumbnailUrl: null,
    origin: "CACHE",
    score: 0.5,
    fetchedAt: "2026-09-28T00:00:00+09:00",
    meta: {},
  };
}

const candidate: VisionCandidate = {
  hazardId: 1,
  accidentType: "CAUGHT",
  accidentLabel: "협착(끼임)",
  missingControl: "방호덮개 미설치",
  evidence: "사진 좌측 롤러 노출",
  confidence: 0.8,
  riskLevel: "MEDIUM",
  ruleTrace: "R2 · 협착 · 방호덮개 없음 → 중",
  adopted: null,
  alreadyKnown: false,
  gateStatus: "PHOTO",
  gateNote: null,
};

describe("CandidateCard", () => {
  it("evidenceItems가 없으면 근거 그리드를 그리지 않는다", () => {
    render(<CandidateCard c={candidate} busy={false} onDecide={() => {}} />);
    expect(screen.queryByText(/근거 \d+건/)).toBeNull();
  });

  it("근거 3건이 접혀 있다가 펼쳐진다", () => {
    render(<CandidateCard c={{ ...candidate, evidenceItems: [ev(1), ev(2), ev(3)] }} busy={false} onDecide={() => {}} />);
    const toggle = screen.getByRole("button", { name: /근거 3건 펼치기/ });
    expect(screen.queryByLabelText("근거 #1")).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByLabelText("근거 #1")).toBeInTheDocument();
  });

  it("채택 버튼 클릭 시 onDecide가 호출된다", () => {
    const onDecide = vi.fn();
    render(<CandidateCard c={candidate} busy={false} onDecide={onDecide} />);
    fireEvent.click(screen.getByRole("button", { name: "채택" }));
    expect(onDecide).toHaveBeenCalledWith(1, true);
  });
});
