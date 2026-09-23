// Vitest 전역 셋업 — jest-dom matcher 등록 + 각 테스트 후 DOM 정리.
import "@testing-library/jest-dom/vitest";

import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

// jsdom은 matchMedia를 구현하지 않는다 — useBreakpoint가 마운트 시 호출하므로 스텁 제공.
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }) as unknown as MediaQueryList;
}

afterEach(() => {
  cleanup();
});
