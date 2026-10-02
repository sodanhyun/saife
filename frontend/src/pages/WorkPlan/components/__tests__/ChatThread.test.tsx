import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import ChatThread from "@/pages/WorkPlan/components/ChatThread";
import { ladderDetail } from "@/pages/WorkPlan/components/__tests__/fixtures";
import type { Turn } from "@/pages/WorkPlan/hooks/useAgentStream";
import type { Evidence } from "@/types/evidence";

const original = HTMLElement.prototype.scrollIntoView;

afterEach(() => {
  HTMLElement.prototype.scrollIntoView = original;
});

describe("ChatThread", () => {
  it("턴이 늘어나면 마지막 줄로 따라간다", () => {
    const scroll = vi.fn();
    HTMLElement.prototype.scrollIntoView = scroll;
    const turns: Turn[] = [{ role: "user", text: "질문", evidence: [] }, { role: "assistant", text: "답", evidence: [] }];
    render(<ChatThread turns={turns} streaming={false} onOpenDetail={() => {}} />);
    expect(scroll).toHaveBeenCalled();
  });

  it("스레드는 새 답변을 알리는 polite 라이브 영역이고 이름이 '대화 내용'이다", () => {
    const { getByLabelText } = render(<ChatThread turns={[{ role: "user", text: "안녕", evidence: [] }]} streaming={false} onOpenDetail={() => {}} />);
    expect(getByLabelText("대화 내용")).toHaveAttribute("aria-live", "polite");
  });

  it("기다리는 동안 진행 중인 단계 이름을 보이고 aria-busy가 true다", () => {
    const { getByLabelText } = render(<ChatThread turns={[{ role: "user", text: "안녕", evidence: [] }]} streaming activity="위험요인 도출 중" onOpenDetail={() => {}} />);
    expect(getByLabelText("대화 내용")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("위험요인 도출 중")).toBeInTheDocument();
  });

  it("답하는 쪽에 이름표를 달지 않는다", () => {
    render(<ChatThread turns={[{ role: "user", text: "안녕", evidence: [] }, { role: "assistant", text: "네", evidence: [] }]} streaming={false} onOpenDetail={() => {}} />);
    expect(screen.queryByText(/에이전트/)).toBeNull();
    expect(screen.queryByText(/SAIFE/)).toBeNull();
  });

  it("근거가 있는 assistant 턴에만 근거 목록을 그린다", () => {
    const evidence: Evidence[] = [{
      no: 1, kind: "GUIDE", refId: 1, refKey: "G:1", title: "근거 1", snippet: "",
      sourceUrl: null, mediaUrl: null, thumbnailUrl: null, origin: "CACHE", score: 0.5,
      fetchedAt: "2026-09-28T00:00:00+09:00", meta: {},
    }];
    const turns: Turn[] = [
      { role: "user", text: "질문", evidence: [] },
      { role: "assistant", text: "근거 없는 답", evidence: [] },
      { role: "assistant", text: "근거 있는 답 [#1]", evidence },
    ];
    render(<ChatThread turns={turns} streaming={false} knownNos={new Set([1])} onOpenDetail={() => {}} />);
    expect(screen.getAllByText(/근거 1건/)).toHaveLength(1);
  });

  it("점검표가 제출된 턴은 결과 카드를 그린다", () => {
    const turns: Turn[] = [
      { role: "user", text: "따로 잡아주는 사람은 없어요", evidence: [] },
      { role: "assistant", text: "점검표를 작성했습니다.", evidence: [], workPlan: ladderDetail },
    ];
    render(<ChatThread turns={turns} streaming={false} onOpenDetail={() => {}} />);
    expect(screen.getByText("작업 전 안전점검표 (TBM)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "검토 및 승인" })).toBeInTheDocument();
  });
});
