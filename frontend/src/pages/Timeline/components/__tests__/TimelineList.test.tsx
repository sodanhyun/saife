import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import TimelineList from "@/pages/Timeline/components/TimelineList";
import { linkedIds } from "@/pages/Timeline/utils/linkedIds";
import type { TimelineEvent } from "@/types/timeline";

const ev = (id: string, linked: string[] = [], emphasis: TimelineEvent["emphasis"] = "NORMAL"): TimelineEvent => ({
  id, type: "ASSESSMENT", at: "2026-09-21", occurredAt: null, title: `사건 ${id}`, detail: "", riskLevel: null,
  accidentType: null, status: null, refId: 1, linkedEventIds: linked, linkedLabels: [], causalOrder: 0, emphasis,
});

describe("TimelineList", () => {
  it("linkedIds는 양방향 연결을 모은다", () => {
    const events = [ev("a", ["b"]), ev("b"), ev("c", ["a"])];
    expect([...linkedIds(events, "a")].sort()).toEqual(["b", "c"]);
  });

  it("사건을 누르면 onFocus에 id를 넘기고, 포커스된 카드는 progress 링을 가진다", () => {
    const onFocus = vi.fn();
    render(<TimelineList events={[ev("a", ["b"]), ev("b")]} focusId="a" onFocus={onFocus} />);
    fireEvent.click(screen.getByText("사건 b"));
    expect(onFocus).toHaveBeenCalledWith("b");
    expect(screen.getByText("사건 a").closest("[role=button]")!.className).toContain("ring-progress-border");
  });

  it("카드에서 Enter·Space를 누르면 onFocus가 불리고, 포커스된 카드는 aria-pressed=true다", () => {
    const onFocus = vi.fn();
    render(<TimelineList events={[ev("a"), ev("b")]} focusId="a" onFocus={onFocus} />);
    const cardB = screen.getByText("사건 b").closest("[role=button]")!;
    fireEvent.keyDown(cardB, { key: "Enter" });
    expect(onFocus).toHaveBeenLastCalledWith("b");
    const cardA = screen.getByText("사건 a").closest("[role=button]")!;
    expect(cardA).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(cardA, { key: " " });
    expect(onFocus).toHaveBeenLastCalledWith(null);
  });

  it("법정 서식 링크는 버튼 안에 중첩되지 않고, 눌러도 카드 포커스를 바꾸지 않는다", () => {
    const onFocus = vi.fn();
    const { container } = render(<TimelineList events={[ev("a")]} focusId={null} onFocus={onFocus} />);
    expect(container.querySelector("button a")).toBeNull();
    fireEvent.click(screen.getByRole("link", { name: "법정 서식" }));
    expect(onFocus).not.toHaveBeenCalled();
  });
});
