import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import TimelineList from "@/components/timeline/TimelineList";
import { linkedIds } from "@/components/timeline/linkedIds";
import { buildStory, cleanTrace, gradeHistory, storyLinkedIds } from "@/components/timeline/storyModel";
import type { TimelineEvent } from "@/types/timeline";

const ev = (id: string, linked: string[] = [], over: Partial<TimelineEvent> = {}): TimelineEvent => ({
  id, type: "ASSESSMENT", at: "2026-09-21", occurredAt: null, title: `사건 ${id}`, detail: "", riskLevel: null,
  accidentType: null, status: null, refId: 1, linkedEventIds: linked, linkedLabels: [], causalOrder: 0, emphasis: "NORMAL",
  ruleTrace: null, ...over,
});

/** 고소작업대(id=6) 시드 이야기의 축약판 */
const story6: TimelineEvent[] = [
  ev("assessment-4", [], { at: "2026-07-16", title: "상시 위험성평가", riskLevel: "HIGH", accidentType: "FALL", ruleTrace: "작업높이 2.4m + 안전대 부착설비 없음 → '상'" }),
  ev("workplan-1", [], { type: "WORK_PLAN", at: "2026-08-10", title: "조명 교체", status: "CONDITIONAL" }),
  ev("incident-1", ["workplan-1", "assessment-5"], { type: "INCIDENT", at: "2026-08-15", occurredAt: "2026-08-15T10:20:00+09:00", title: "떨어짐 사고", accidentType: "FALL", status: "SUBMITTED", detail: "휴업 5일 · 조사표 기한 09-15" }),
  ev("assessment-5", ["incident-1"], { at: "2026-08-15", occurredAt: "2026-08-15T11:00:00+09:00", title: "수시평가", riskLevel: "HIGH", accidentType: "FALL", causalOrder: 4 }),
  ev("assessment-6", [], { at: "2026-09-19", title: "상시 위험성평가", riskLevel: "MEDIUM", accidentType: "FALL" }),
];

describe("storyModel", () => {
  it("등급 변화는 직전 평가와 비교해 표시한다(상 → 중은 개선)", () => {
    const s = buildStory(story6);
    expect(s.find((x) => x.ev.id === "assessment-6")!.gradeChange).toEqual({ from: "HIGH", to: "MEDIUM", improved: true });
    expect(s.find((x) => x.ev.id === "assessment-5")!.gradeChange).toBeNull();
  });

  it("사고에는 사고 전 평가와 서버 연결을 짧은 평문으로 붙인다", () => {
    const incident = buildStory(story6).find((x) => x.ev.id === "incident-1")!;
    const texts = incident.relations.map((r) => r.text);
    expect(texts[0]).toBe("사고 전 평가: 떨어짐 상 (07-16)");
    expect(texts).toContain("작업 전 점검: 조명 교체");
    expect(texts).toContain("수시평가 08-15");
    expect(incident.detail).toBe("휴업 5일, 조사표 기한 09-15");
    const followUp = buildStory(story6).find((x) => x.ev.id === "assessment-5")!;
    expect(followUp.relations.map((r) => r.text)).toEqual(["08-15 떨어짐 사고 후속"]);
  });

  it("사고 전 평가는 같은 날 그 사고가 만든 수시평가가 아니라 사고 이전 가장 최근 평가다", () => {
    const events = [
      ...story6.slice(0, 2),
      ev("assessment-9", [], { at: "2026-08-15", occurredAt: "2026-08-15T08:00:00+09:00", title: "상시평가", riskLevel: "MEDIUM", accidentType: "FALL" }),
      ...story6.slice(2),
    ];
    const incident = buildStory(events).find((x) => x.ev.id === "incident-1")!;
    expect(incident.relations[0].text).toBe("사고 전 평가: 떨어짐 중 (08-15)");
  });

  it("등급 근거는 결론 화살표와 따옴표 등급을 걷어낸 평문이다", () => {
    expect(cleanTrace("작업높이 3.2m (2m 초과) + 안전대 부착설비 없음 → '상'")).toBe("작업높이 3.2m (2m 초과), 안전대 부착설비 없음");
    expect(cleanTrace("난간 보수 완료로 강도 하향, 안전대 부착설비는 여전히 없어 '중' 유지")).toBe("난간 보수 완료로 강도 하향, 안전대 부착설비는 여전히 없어 등급 유지");
    expect(cleanTrace("발판 높이 3.5m 초과, 이동식 사다리 사용 불가 (제42조④)")).toBe("발판 높이 3.5m 초과, 이동식 사다리 사용 불가 (제42조④)");
  });

  it("ruleTrace가 없는 옛 응답은 detail의 가운뎃점 뒤를 등급 근거로 읽는다", () => {
    const [s] = buildStory([ev("a", [], { detail: "1건의 위험요인 평가 · 높이 3m → '상'", riskLevel: "HIGH" })]);
    expect(s.detail).toBe("1건의 위험요인 평가");
    expect(s.trace).toBe("높이 3m");
  });

  it("등급 이력은 같은 날 평가를 하나로 줄인다", () => {
    const history = gradeHistory(buildStory(story6));
    expect(history.map((h) => `${h.ev.at.slice(5)} ${h.ev.riskLevel}`)).toEqual(["07-16 HIGH", "08-15 HIGH", "09-19 MEDIUM"]);
    const sameDay = gradeHistory(buildStory([
      ev("x", [], { at: "2026-10-02", riskLevel: "HIGH" }),
      ev("y", [], { at: "2026-10-02", riskLevel: "MEDIUM" }),
    ]));
    expect(sameDay.map((h) => h.ev.id)).toEqual(["y"]);
  });

  it("포커스 연결은 양방향이고 사전 경고 대상도 포함한다", () => {
    const s = buildStory(story6);
    expect([...storyLinkedIds(s, "incident-1")].sort()).toEqual(["assessment-4", "assessment-5", "workplan-1"]);
    expect([...storyLinkedIds(s, "assessment-4")]).toEqual(["incident-1"]);
  });
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

  it("포커스가 있으면 이어지지 않은 사건은 물러난다", () => {
    render(<TimelineList events={[ev("a", ["b"]), ev("b"), ev("c")]} focusId="a" onFocus={vi.fn()} />);
    const wrapperOf = (t: string) => screen.getByText(t).closest("[role=button]")!.parentElement!;
    expect(wrapperOf("사건 c").className).toContain("opacity-40");
    expect(wrapperOf("사건 b").className).not.toContain("opacity-40");
  });

  it("카드에서 Enter, Space를 누르면 onFocus가 불리고, 포커스된 카드는 aria-pressed=true다", () => {
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

  it("같은 날 사건에도 날짜를 보이고, 평가 제목은 '상시평가'처럼 줄인다", () => {
    render(<TimelineList events={[ev("a", [], { title: "상시 위험성평가" }), ev("b", [], { type: "INCIDENT", title: "떨어짐 사고" })]} focusId={null} onFocus={vi.fn()} />);
    expect(screen.getAllByText("09-21")).toHaveLength(2);
    expect(screen.getByText("상시평가")).toBeInTheDocument();
  });

  it("서식 출력 링크는 role=button 카드 밖 형제 요소이고, 눌러도 카드 포커스를 바꾸지 않는다", () => {
    const onFocus = vi.fn();
    const { container } = render(<TimelineList events={[ev("a")]} focusId={null} onFocus={onFocus} />);
    expect(container.querySelector('[role="button"] a')).toBeNull();
    const link = screen.getByRole("link", { name: "서식 출력" });
    expect(link.closest("li")!.querySelector('[role="button"]')).not.toContainElement(link);
    fireEvent.click(link);
    expect(onFocus).not.toHaveBeenCalled();
  });
});
