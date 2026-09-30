import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import CascadeList from "@/pages/Incident/components/CascadeList";
import { CASCADE_ANCHOR_BY_KIND } from "@/pages/Incident/utils/cascadeAnchor";
import type { CascadeStep } from "@/types/incident";

function step(order: number, kind: CascadeStep["kind"], title: string): CascadeStep {
  return { order, kind, title, detail: `상세 ${order}`, emphasis: "NORMAL", refId: null, refType: null };
}

// 입력 순서를 일부러 뒤섞는다 — 렌더 순서는 항상 order 오름차순이어야 한다(백엔드가 순서를 정한다)
const steps: CascadeStep[] = [
  step(3, "REPORT", "산업재해조사표 기한"),
  step(1, "RECALL", "사전 기록 소환"),
  step(4, "WORK_PLAN", "작업계획서 경고"),
  step(2, "FOLLOW_UP", "수시평가 자동 생성"),
];

describe("CascadeList", () => {
  let scrollIntoViewMock: ReturnType<typeof vi.fn<(arg?: boolean | ScrollIntoViewOptions) => void>>;

  beforeEach(() => {
    scrollIntoViewMock = vi.fn<(arg?: boolean | ScrollIntoViewOptions) => void>();
    // jsdom은 scrollIntoView를 구현하지 않는다
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

  it("4스텝을 order 순서대로(1→4) 렌더한다", () => {
    render(<CascadeList steps={steps} />);
    const titles = screen.getAllByRole("button").map((b) => b.textContent);
    expect(titles[0]).toContain("사전 기록 소환");
    expect(titles[1]).toContain("수시평가 자동 생성");
    expect(titles[2]).toContain("산업재해조사표 기한");
    expect(titles[3]).toContain("작업계획서 경고");
  });

  it("prefers-reduced-motion이면 마운트 즉시 전부 visible이다(등장 지연 없음)", () => {
    vi.spyOn(window, "matchMedia").mockReturnValue({
      matches: true,
      media: "(prefers-reduced-motion: reduce)",
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    } as unknown as MediaQueryList);

    render(<CascadeList steps={steps} />);
    const buttons = screen.getAllByRole("button");
    // 타이머를 한 틱도 진행하지 않은 시점에 전부 opacity-100(visible) 상태여야 한다
    for (const b of buttons) {
      expect(b.className).toContain("opacity-100");
    }
  });

  it("클릭 시 해당 kind의 앵커 요소에 scrollIntoView가 호출된다", () => {
    render(<CascadeList steps={steps} />);
    fireEvent.click(screen.getByText("작업계획서 경고"));
    const target = document.getElementById(CASCADE_ANCHOR_BY_KIND.WORK_PLAN);
    expect(scrollIntoViewMock).toHaveBeenCalled();
    expect(scrollIntoViewMock.mock.instances[0]).toBe(target);
  });

  // 네이티브 <button>은 Enter/Space에서 브라우저가 스스로 click을 낸다 — onKeyDown으로
  // jumpTo를 한 번 더 부르면 같은 동작이 중복된다(scrollIntoView 두 번 호출, 결과는 멱등이라
  // 무해하지만 코드가 거짓 정보를 준다). 그래서 onKeyDown을 두지 않는다 — jsdom은 이 네이티브
  // 활성화를 흉내 내지 않으므로 여기서 키보드 입력을 별도로 재현하지 않는다.

  it("workPlanAnchorOnSelf가 true면 WORK_PLAN 스텝의 li가 앵커 id를 갖는다", () => {
    render(<CascadeList steps={[step(1, "WORK_PLAN", "작업계획서 경고")]} workPlanAnchorOnSelf />);
    const li = screen.getByText("작업계획서 경고").closest("li")!;
    expect(li.id).toBe(CASCADE_ANCHOR_BY_KIND.WORK_PLAN);
  });

  it("workPlanAnchorOnSelf 기본값(false)이면 WORK_PLAN 스텝의 li에 앵커 id를 달지 않는다 — 표 쪽 래퍼가 이미 그 id를 갖기 때문(중복 id 방지)", () => {
    render(<CascadeList steps={[step(1, "WORK_PLAN", "작업계획서 경고")]} />);
    const li = screen.getByText("작업계획서 경고").closest("li")!;
    expect(li.id).toBe("");
  });

  it("cascade가 빈 배열이면 아무것도 렌더하지 않는다", () => {
    render(<CascadeList steps={[]} />);
    expect(screen.queryByRole("list")).toBeNull();
  });
});
