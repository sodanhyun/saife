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
  accidentType: "FALL",
  accidentLabel: "떨어짐",
  missingControl: "최상부 디딤대 사용",
  evidence: "작업자가 A형 사다리 최상부 발판 위에 서 있음",
  confidence: 0.8,
  riskLevel: "HIGH",
  ruleTrace: "이동식 사다리 최상부 발판 및 그 하단 디딤대 사용 금지 (안전보건규칙 제42조제4항). 사진 기준 잠정 등급, 작업높이와 빈도는 현장 확인 후 조정",
  adopted: null,
  alreadyKnown: false,
  gateStatus: "PHOTO",
  acceptable: false,
  suggestedAction: {
    content: "이동식 비계(안전난간) 또는 말비계로 작업발판 확보",
    lawRef: "산업안전보건기준에 관한 규칙 제42조제4항",
    lawTitle: "추락의 방지",
    guideRef: "B-5-2011",
    priority: "ENGINEERING",
  },
  action: null,
  priorOpenAction: null,
};

const action: ActionView = {
  id: 9, hazardId: 1, assessmentId: 36, equipmentId: 1, content: "이동식 비계 사용", owner: "생산반장 김철수",
  dueDate: "2099-10-16", status: "PENDING", guideRef: "B-5-2011", completedAt: null, createdAt: "2026-10-02T10:00:00+09:00",
  priority: "ENGINEERING",
};

const noop = () => {};
type Handlers = Partial<{ onDecide: () => void; onAcceptable: () => void; onCreateAction: () => void; onCompleteAction: () => void }>;
function renderCard(c: VisionCandidate, h: Handlers = {}) {
  return render(
    <CandidateCard
      c={c}
      busy={false}
      onDecide={h.onDecide ?? noop}
      onAcceptable={h.onAcceptable ?? noop}
      onCreateAction={h.onCreateAction ?? noop}
      onCompleteAction={h.onCompleteAction ?? noop}
    />,
  );
}

describe("CandidateCard", () => {
  it("검토 필요 상태, 등급과 등급 근거, 판독 내용을 같이 띄우고 AI 표기는 없다", () => {
    renderCard(candidate);
    expect(screen.getByText("검토 필요")).toBeInTheDocument();
    expect(screen.getByLabelText("위험성 상")).toBeInTheDocument();
    expect(screen.getByText("등급 근거")).toBeInTheDocument();
    expect(screen.getByText(candidate.ruleTrace)).toBeInTheDocument();
    expect(screen.getByText("판독 내용")).toBeInTheDocument();
    expect(screen.queryByText(/AI/)).toBeNull();
  });

  it("근거가 없으면 근거 토글을 그리지 않는다", () => {
    renderCard(candidate);
    expect(screen.queryByText(/근거 \d+건/)).toBeNull();
  });

  it("근거 3건이 접혀 있다가 펼쳐진다", () => {
    renderCard({ ...candidate, evidenceItems: [ev(1), ev(2), ev(3)] });
    const toggle = screen.getByRole("button", { name: "근거 3건" });
    expect(screen.queryByLabelText("근거 #1")).toBeNull();
    fireEvent.click(toggle);
    expect(screen.getByLabelText("근거 #1")).toBeInTheDocument();
  });

  it("반영, 제외 버튼이 onDecide를 부른다", () => {
    const onDecide = vi.fn();
    renderCard(candidate, { onDecide });
    fireEvent.click(screen.getByRole("button", { name: "반영" }));
    expect(onDecide).toHaveBeenCalledWith(1, true);
    fireEvent.click(screen.getByRole("button", { name: "제외" }));
    expect(onDecide).toHaveBeenCalledWith(1, false);
  });

  it("반영하고 허용 불가면 개선대책 폼이 초안과 우선순위로 미리 채워지고 등록하면 onCreateAction이 불린다", () => {
    const onCreateAction = vi.fn();
    renderCard({ ...candidate, adopted: true }, { onCreateAction });
    expect(screen.getByRole("button", { name: "불가" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("개선대책 내용")).toHaveValue(candidate.suggestedAction!.content);
    expect(screen.getByRole("button", { name: "공학적" })).toHaveAttribute("aria-pressed", "true");
    expect((screen.getByLabelText("기한") as HTMLInputElement).value).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(screen.getByText(/안전보건규칙 제42조제4항\(추락의 방지\), KOSHA GUIDE B-5-2011/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("담당"), { target: { value: "생산반장 김철수" } });
    fireEvent.click(screen.getByRole("button", { name: "등록" }));
    expect(onCreateAction).toHaveBeenCalledWith(
      expect.objectContaining({ hazardId: 1 }),
      expect.objectContaining({ owner: "생산반장 김철수", priority: "ENGINEERING", content: candidate.suggestedAction!.content }),
    );
  });

  it("허용 가능으로 바꾸면 onAcceptable이 불리고, 허용 가능이면 개선대책 폼이 없다", () => {
    const onAcceptable = vi.fn();
    const { rerender } = renderCard({ ...candidate, adopted: true }, { onAcceptable });
    fireEvent.click(screen.getByRole("button", { name: "가능" }));
    expect(onAcceptable).toHaveBeenCalledWith(1, true);
    rerender(<CandidateCard c={{ ...candidate, adopted: true, acceptable: true }} busy={false} onDecide={noop} onAcceptable={onAcceptable} onCreateAction={noop} onCompleteAction={noop} />);
    expect(screen.queryByLabelText("개선대책 내용")).toBeNull();
    expect(screen.getByText("현 상태 유지")).toBeInTheDocument();
  });

  it("등록 직후에는 이행 완료가 주 버튼이 아니고, 이행 완료 기록 후 확인 줄에서 한 번 더 눌러야 기록된다", () => {
    const onCompleteAction = vi.fn();
    renderCard({ ...candidate, adopted: true, action }, { onCompleteAction });
    expect(screen.getByText("이행 대기")).toBeInTheDocument();
    expect(screen.getByText("공학적")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "이행 완료" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "이행 완료 기록" }));
    expect(screen.getByRole("group", { name: "이행 완료 확인" })).toHaveTextContent(/완료일 \d{4}-\d{2}-\d{2}/);
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(onCompleteAction).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "이행 완료 기록" }));
    fireEvent.click(screen.getByRole("button", { name: "이행 완료" }));
    expect(onCompleteAction).toHaveBeenCalledWith(1, 9);
  });

  it("이행 완료면 완료일과 설비 이력 링크를 보인다", () => {
    renderCard({ ...candidate, adopted: true, action: { ...action, status: "DONE", completedAt: "2026-10-02T10:20:00+09:00" } });
    expect(screen.getByText("이행 완료 10-02")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "설비 이력" })).toHaveAttribute("href", "/equipment/1");
    expect(screen.queryByRole("button", { name: "이행 완료" })).toBeNull();
  });

  it("기존 위험요인에 기한 지난 미이행 조치가 있으면 경과일을 띄우고 기준표 1순위 대책 초안으로 폼을 연다", () => {
    renderCard({
      ...candidate, adopted: true, alreadyKnown: true,
      priorOpenAction: { ...action, id: 1, content: "차양부 천장 작업 시 이동식 비계(안전난간) 사용", dueDate: "2020-08-21", status: "OVERDUE" },
    });
    expect(screen.getByText("기존 위험요인")).toBeInTheDocument();
    expect(screen.getByText("미이행 조치")).toBeInTheDocument();
    expect(screen.getByText(/^\d+일 경과$/)).toBeInTheDocument();
    expect(screen.getByLabelText("개선대책 내용")).toHaveValue("이동식 비계(안전난간) 또는 말비계로 작업발판 확보");
    expect(screen.getByRole("button", { name: "공학적" })).toHaveAttribute("aria-pressed", "true");
  });

  it("초안이 없으면 내용 칸을 비우고 자리표시 문구만 둔다", () => {
    renderCard({ ...candidate, adopted: true, suggestedAction: null });
    const input = screen.getByLabelText("개선대책 내용");
    expect(input).toHaveValue("");
    expect(input).toHaveAttribute("placeholder", "개선대책 입력");
  });

  it("기한이 남은 기존 조치가 있으면 폼을 접어 두고 사람이 열 수 있다", () => {
    renderCard({ ...candidate, adopted: true, alreadyKnown: true, priorOpenAction: { ...action, id: 1 } });
    expect(screen.queryByLabelText("개선대책 내용")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "개선대책 추가" }));
    expect(screen.getByLabelText("개선대책 내용")).toBeInTheDocument();
  });

  it("제외하면 제외 표시와 되돌리기만 남는다", () => {
    const onDecide = vi.fn();
    renderCard({ ...candidate, adopted: false }, { onDecide });
    fireEvent.click(screen.getByRole("button", { name: "되돌리기" }));
    expect(onDecide).toHaveBeenCalledWith(1, true);
    expect(screen.queryByRole("button", { name: "반영" })).toBeNull();
  });
});
