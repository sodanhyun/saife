import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import ToastContainer from "@/components/ui/ToastContainer";
import { useToastStore } from "@/stores/useToastStore";

beforeEach(() => {
  vi.useFakeTimers();
  useToastStore.setState({ toasts: [] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("ToastContainer", () => {
  it("다른 토스트가 추가돼 다시 렌더돼도 앞 토스트의 자동 제거 타이머가 리셋되지 않는다", () => {
    render(<ToastContainer />);
    act(() => { useToastStore.getState().success("첫 번째", 1000); });
    act(() => { vi.advanceTimersByTime(600); });
    act(() => { useToastStore.getState().info("두 번째", 5000); }); // 컨테이너 재렌더
    act(() => { vi.advanceTimersByTime(500); }); // 첫 토스트는 1100ms 경과 → 퇴장 시작
    expect(screen.getByText("첫 번째").closest("[role=alert]")!.className).toContain("opacity-0");
    act(() => { vi.advanceTimersByTime(300); }); // 퇴장 애니메이션(250ms) 후 제거
    expect(screen.queryByText("첫 번째")).toBeNull();
    expect(screen.getByText("두 번째")).toBeInTheDocument();
  });
});
