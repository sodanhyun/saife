// EvidenceGrid.test.tsx — F3(칩이 접힌 그리드를 펼친다)·F18(scope로 id·이벤트를 가른다) 회귀.
import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import CitationChip from "@/components/evidence/CitationChip";
import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import type { Evidence } from "@/types/evidence";

function ev(no: number): Evidence {
  return {
    no, kind: "GUIDE", refId: no, refKey: `G:${no}`, title: `근거 ${no}`, snippet: "",
    sourceUrl: null, mediaUrl: null, thumbnailUrl: null, origin: "CACHE", score: 0.5,
    fetchedAt: "2026-09-28T00:00:00+09:00", meta: {},
  };
}

describe("EvidenceGrid", () => {
  beforeEach(() => {
    HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  it("collapsedByDefault면 카드가 DOM에 없다", () => {
    render(<EvidenceGrid items={[ev(1)]} scope="chat" collapsedByDefault />);
    expect(document.getElementById("evidence-chat-1")).toBeNull();
    expect(screen.getByText("근거 1건 펼치기")).toBeInTheDocument();
  });

  it("칩을 클릭하면 접힌 그리드가 펼쳐지고 카드가 강조된다(F3)", async () => {
    render(
      <>
        <CitationChip no={1} known scope="chat" />
        <EvidenceGrid items={[ev(1)]} scope="chat" collapsedByDefault />
      </>,
    );
    expect(document.getElementById("evidence-chat-1")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "근거 #1" }));
    // 그리드가 evidence:reveal을 받아 setOpen(true)하는 리렌더 + 칩의 requestAnimationFrame이
    // 순서대로 처리될 시간을 준다.
    await act(async () => {
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    });

    const card = document.getElementById("evidence-chat-1");
    expect(card).not.toBeNull();
    expect(card).toHaveClass("ring-progress-border");
    expect(screen.getByText("근거 1건 접기")).toBeInTheDocument();
  });

  it("다른 scope의 reveal 이벤트는 무시한다(F18)", () => {
    render(<EvidenceGrid items={[ev(1)]} scope="chat" collapsedByDefault />);
    act(() => {
      window.dispatchEvent(new CustomEvent("evidence:reveal", { detail: { no: 1, scope: "incident" } }));
    });
    expect(document.getElementById("evidence-chat-1")).toBeNull();
  });

  it("같은 scope라도 이 그리드에 없는 번호면 펼치지 않는다", () => {
    render(<EvidenceGrid items={[ev(1)]} scope="chat" collapsedByDefault />);
    act(() => {
      window.dispatchEvent(new CustomEvent("evidence:reveal", { detail: { no: 99, scope: "chat" } }));
    });
    expect(document.getElementById("evidence-chat-1")).toBeNull();
  });
});
