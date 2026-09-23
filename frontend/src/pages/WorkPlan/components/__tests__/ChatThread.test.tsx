import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import ChatThread from "@/pages/WorkPlan/components/ChatThread";
import type { Turn } from "@/pages/WorkPlan/hooks/useAgentStream";

// jsdom엔 레이아웃이 없어 scrollHeight가 항상 0이다 — 값 자체가 아니라
// "턴이 바뀌면 scrollTop 세터가 호출된다(=맨 아래로 내리려 시도한다)"만 검증한다.
const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollTop");

afterEach(() => {
  if (original) Object.defineProperty(HTMLElement.prototype, "scrollTop", original);
});

describe("ChatThread", () => {
  it("턴이 늘어나면 스크롤 컨테이너의 scrollTop을 다시 설정한다", () => {
    const setter = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollTop", {
      configurable: true,
      get: () => 0,
      set: setter,
    });
    const turns: Turn[] = Array.from({ length: 20 }, (_, i) => ({
      role: i % 2 === 0 ? "user" : "assistant",
      text: `턴 ${i}`,
    }));
    render(<ChatThread turns={turns} streaming={false} restoring={false} />);
    expect(setter).toHaveBeenCalled();
  });

  it("스레드는 새 답변을 알리는 polite 라이브 영역이고 이름이 '대화 내용'이다", () => {
    const { getByLabelText } = render(<ChatThread turns={[{ role: "user", text: "안녕" }]} streaming={false} restoring={false} />);
    expect(getByLabelText("대화 내용")).toHaveAttribute("aria-live", "polite");
  });

  it("streaming 중에는 aria-busy가 true여서 토큰마다 읽어주지 않는다", () => {
    const { getByLabelText, rerender } = render(<ChatThread turns={[{ role: "user", text: "안녕" }]} streaming={true} restoring={false} />);
    expect(getByLabelText("대화 내용")).toHaveAttribute("aria-busy", "true");

    rerender(<ChatThread turns={[{ role: "user", text: "안녕" }]} streaming={false} restoring={false} />);
    expect(getByLabelText("대화 내용")).toHaveAttribute("aria-busy", "false");
  });
});
