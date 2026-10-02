import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import CitationChip from "@/components/evidence/CitationChip";

describe("CitationChip", () => {
  beforeEach(() => {
    // jsdom은 scrollIntoView를 구현하지 않는다 — 먼저 정의한 뒤에 감시한다.
    HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = "";
  });

  it("known — 버튼으로 그려지고 클릭하면 해당 scope의 카드로 스크롤한다", () => {
    const card = document.createElement("div");
    card.id = "evidence-chat-2";
    document.body.appendChild(card);

    render(<CitationChip no={2} known scope="chat" />);
    const btn = screen.getByRole("button", { name: "근거 #2" });
    expect(btn).toHaveTextContent("2");
    expect(btn).not.toHaveTextContent("#");

    fireEvent.click(btn);
    expect(card.scrollIntoView).toHaveBeenCalledWith(expect.objectContaining({ block: "center" }));
    expect(card.classList.contains("ring-progress-border")).toBe(true);
  });

  it("다른 scope의 같은 번호 카드는 건드리지 않는다", () => {
    const wrongScope = document.createElement("div");
    wrongScope.id = "evidence-incident-2";
    document.body.appendChild(wrongScope);

    render(<CitationChip no={2} known scope="chat" />);
    fireEvent.click(screen.getByRole("button", { name: "근거 #2" }));
    expect(wrongScope.scrollIntoView).not.toHaveBeenCalled();
  });

  it("known인데 카드가 접혀 있으면(DOM에 없으면) evidence:reveal을 쏘고 다음 프레임에 다시 찾는다", async () => {
    const onReveal = vi.fn();
    window.addEventListener("evidence:reveal", onReveal);
    render(<CitationChip no={5} known scope="chat" />);

    fireEvent.click(screen.getByRole("button", { name: "근거 #5" }));
    expect(onReveal).toHaveBeenCalledWith(expect.objectContaining({ detail: { no: 5, scope: "chat" } }));

    // 그리드가 이벤트를 받아 펼치고 카드를 그린 뒤 상황을 흉내 낸다
    const card = document.createElement("div");
    card.id = "evidence-chat-5";
    document.body.appendChild(card);

    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    expect(card.scrollIntoView).toHaveBeenCalled();
    window.removeEventListener("evidence:reveal", onReveal);
  });

  it("known이어도 펼친 뒤에도 카드가 없으면 조용히 무시한다", async () => {
    render(<CitationChip no={9} known scope="chat" />);
    const btn = screen.getByRole("button", { name: "근거 #9" });
    expect(() => fireEvent.click(btn)).not.toThrow();
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  });

  it("unknown — 버튼이 아니라 평문 [#n]으로 그려진다", () => {
    render(<CitationChip no={7} known={false} scope="chat" />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText("[#7]")).toBeInTheDocument();
  });
});
