import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import CascadeList from "@/pages/Incident/components/CascadeList";
import { CASCADE_ANCHOR_BY_KIND } from "@/pages/Incident/utils/cascadeAnchor";
import type { CascadeCard } from "@/pages/Incident/utils/premonition";
import type { CascadeStep } from "@/types/incident";

const cards: CascadeCard[] = [
  { kind: "RECALL", order: 1, label: "설비 이력 소환", value: "42일 경과", detail: "위험요인 2건" },
  { kind: "FOLLOW_UP", order: 2, label: "수시평가 자동 생성", value: "#29", detail: "유지" },
  { kind: "REPORT", order: 3, label: "산업재해조사표 기한", value: "D-31", detail: "2026-11-02까지" },
  { kind: "WORK_PLAN", order: 4, label: "작업계획서 경고 부착", value: "4건", detail: "천장 페인트 작업 외 3건" },
];
const steps: CascadeStep[] = [
  { order: 1, kind: "RECALL", title: "", detail: "", emphasis: "CRITICAL", refId: null, refType: null },
];

function mockReducedMotion(matches: boolean) {
  vi.spyOn(window, "matchMedia").mockReturnValue({
    matches,
    media: "(prefers-reduced-motion: reduce)",
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as unknown as MediaQueryList);
}

describe("CascadeList", () => {
  let scrollIntoViewMock: ReturnType<typeof vi.fn<(arg?: boolean | ScrollIntoViewOptions) => void>>;

  beforeEach(() => {
    scrollIntoViewMock = vi.fn<(arg?: boolean | ScrollIntoViewOptions) => void>();
    Element.prototype.scrollIntoView = scrollIntoViewMock;
    for (const id of Object.values(CASCADE_ANCHOR_BY_KIND)) {
      const el = document.createElement("div");
      el.id = id;
      document.body.appendChild(el);
    }
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("4단계를 순서대로, 스크린리더가 읽을 이름(단계, 라벨, 값, 설명)과 함께 렌더한다", () => {
    render(<CascadeList cards={cards} steps={steps} />);
    const names = screen.getAllByRole("button").map((b) => b.getAttribute("aria-label"));
    expect(names).toEqual([
      "1단계 설비 이력 소환: 42일 경과. 위험요인 2건",
      "2단계 수시평가 자동 생성: #29. 유지",
      "3단계 산업재해조사표 기한: D-31. 2026-11-02까지",
      "4단계 작업계획서 경고 부착: 4건. 천장 페인트 작업 외 3건",
    ]);
  });

  it("카드가 차례로 뜬다: 350ms 간격의 등장 지연", () => {
    mockReducedMotion(false);
    render(<CascadeList cards={cards} />);
    const delays = screen.getAllByRole("button").map((b) => b.style.animationDelay);
    expect(delays).toEqual(["500ms", "850ms", "1200ms", "1550ms"]);
  });

  it("prefers-reduced-motion이면 애니메이션 없이 바로 보인다", () => {
    mockReducedMotion(true);
    render(<CascadeList cards={cards} />);
    for (const b of screen.getAllByRole("button")) {
      expect(b.className).not.toContain("animate-rise-in");
      expect(b.style.animationDelay).toBe("");
    }
  });

  it("CRITICAL 단계는 위험 톤으로 값이 칠해진다", () => {
    render(<CascadeList cards={cards} steps={steps} />);
    expect(screen.getByText("42일 경과").className).toContain("text-risk-high-text");
    expect(screen.getByText("#29").className).toContain("text-slate-900");
  });

  it("클릭하면 해당 상세 카드로 스크롤한다", () => {
    render(<CascadeList cards={cards} />);
    fireEvent.click(screen.getByText("4건"));
    expect(scrollIntoViewMock).toHaveBeenCalled();
    expect(scrollIntoViewMock.mock.instances[0]).toBe(document.getElementById(CASCADE_ANCHOR_BY_KIND.WORK_PLAN));
  });

  it("카드가 없으면 아무것도 렌더하지 않는다", () => {
    const { container } = render(<CascadeList cards={[]} />);
    expect(container.innerHTML).toBe("");
  });
});
