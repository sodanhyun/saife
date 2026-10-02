import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import CandidateCard from "@/pages/Vision/components/CandidateCard";
import type { ActionView } from "@/types/action";
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
  accidentType: "PPE",
  accidentLabel: "보호구",
  missingControl: "안전대 미착용",
  evidence: "사다리 최상단 작업자가 안전그네 미착용",
  confidence: 0.8,
  riskLevel: "HIGH",
  ruleTrace: "사진 판독 기준 잠정 등급: 안전대 미착용",
  adopted: null,
  alreadyKnown: false,
  gateStatus: "PHOTO",
  gateNote: null,
  suggestedAction: { content: "안전대(안전그네) 지급, 착용 지도와 작업 전 부착설비 체결 확인", lawRef: "산업안전보건기준에 관한 규칙 제32조", lawTitle: "보호구의 지급", guideRef: "B-5-2011" },
  action: null,
  priorOpenAction: null,
};

const action: ActionView = {
  id: 9, hazardId: 1, assessmentId: 36, equipmentId: 1, content: "안전대 지급", owner: "관리부",
  dueDate: "2099-10-16", status: "PENDING", guideRef: "B-5-2011", completedAt: null, createdAt: "2026-10-02T10:00:00+09:00",
};

const noop = () => {};
function renderCard(c: VisionCandidate, handlers: Partial<{ onDecide: () => void; onCreateAction: () => void; onCompleteAction: () => void }> = {}) {
  return render(<CandidateCard c={c} busy={false} onDecide={handlers.onDecide ?? noop} onCreateAction={handlers.onCreateAction ?? noop} onCompleteAction={handlers.onCompleteAction ?? noop} />);
}

describe("CandidateCard", () => {
  it("AI 후보 표시, 등급과 룰 트레이스를 같이 띄운다", () => {
    renderCard(candidate);
    expect(screen.getByText("AI 후보")).toBeInTheDocument();
    expect(screen.getByLabelText("위험성 상")).toBeInTheDocument();
    expect(screen.getByText(candidate.ruleTrace)).toBeInTheDocument();
  });

  it("evidenceItems가 없으면 근거 그리드를 그리지 않는다", () => {
    renderCard(candidate);
    expect(screen.queryByText(/근거 \d+건/)).toBeNull();
  });

  it("근거 3건이 접혀 있다가 펼쳐진다", () => {
    renderCard({ ...candidate, evidenceItems: [ev(1), ev(2), ev(3)] });
    const toggle = screen.getByRole("button", { name: /근거 3건 펼치기/ });
    expect(screen.queryByLabelText("근거 #1")).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByLabelText("근거 #1")).toBeInTheDocument();
  });

  it("채택 버튼 클릭 시 onDecide가 호출된다", () => {
    const onDecide = vi.fn();
    renderCard(candidate, { onDecide });
    fireEvent.click(screen.getByRole("button", { name: "채택" }));
    expect(onDecide).toHaveBeenCalledWith(1, true);
  });

  it("채택하면 감소대책 폼이 룰 표 초안, 담당 관리부로 미리 채워지고 등록하면 onCreateAction이 불린다", () => {
    const onCreateAction = vi.fn();
    renderCard({ ...candidate, adopted: true }, { onCreateAction });
    expect(screen.getByLabelText("감소대책 내용")).toHaveValue(candidate.suggestedAction!.content);
    expect(screen.getByLabelText("담당")).toHaveValue("관리부");
    expect((screen.getByLabelText("기한") as HTMLInputElement).value).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(screen.getByText(/KOSHA GUIDE B-5-2011/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "감소대책 등록" }));
    expect(onCreateAction).toHaveBeenCalledWith(expect.objectContaining({ hazardId: 1 }), expect.objectContaining({ owner: "관리부", content: candidate.suggestedAction!.content }));
  });

  it("조치가 등록되면 이행 대기와 이행 완료 버튼을 보인다", () => {
    const onCompleteAction = vi.fn();
    renderCard({ ...candidate, adopted: true, action }, { onCompleteAction });
    expect(screen.getByText("조치 등록, 이행 대기")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "이행 완료" }));
    expect(onCompleteAction).toHaveBeenCalledWith(1, 9);
  });

  it("이행 완료면 완료 시각과 설비 타임라인 링크를 보인다", () => {
    renderCard({ ...candidate, adopted: true, action: { ...action, status: "DONE", completedAt: "2026-10-02T10:20:00+09:00" } });
    expect(screen.getByText(/이행 완료 2026-10-02 10:20/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "이 설비 타임라인에 기록됨, 보기" })).toHaveAttribute("href", "/equipment/1");
    expect(screen.queryByRole("button", { name: "이행 완료" })).toBeNull();
  });

  it("재확인 후보에 미이행 조치가 있으면 경고를 띄우고 폼은 접어 둔다", () => {
    renderCard({ ...candidate, adopted: true, alreadyKnown: true, priorOpenAction: { ...action, id: 1, content: "차양부 천장에 앵커 설치", dueDate: "2026-08-21", status: "OVERDUE" } });
    expect(screen.getByText("이 위험요인에 끝나지 않은 조치가 있습니다")).toBeInTheDocument();
    expect(screen.queryByLabelText("감소대책 내용")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "감소대책 추가" }));
    expect(screen.getByLabelText("감소대책 내용")).toBeInTheDocument();
  });
});
