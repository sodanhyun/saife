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
    expect(screen.getByText("사건 a").closest("button")!.className).toContain("ring-progress-border");
  });
});
