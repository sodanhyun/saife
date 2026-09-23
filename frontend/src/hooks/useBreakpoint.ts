// src/hooks/useBreakpoint.ts

import { useState, useEffect } from "react";

/** Tailwind 브레이크포인트 기준값 */
const SM_BREAKPOINT = 640;
const LG_BREAKPOINT = 1024;

interface BreakpointState {
  /** < 640px (sm 미만) */
  isMobile: boolean;
  /** 640px ~ 1023px */
  isTablet: boolean;
  /** >= 1024px (lg 이상) */
  isDesktop: boolean;
  /** 현재 뷰포트 너비 */
  width: number;
}

function getState(width: number): BreakpointState {
  return {
    isMobile: width < SM_BREAKPOINT,
    isTablet: width >= SM_BREAKPOINT && width < LG_BREAKPOINT,
    isDesktop: width >= LG_BREAKPOINT,
    width,
  };
}

/**
 * Tailwind 브레이크포인트 기준 반응형 상태 훅.
 * CSS `sm:`/`lg:` 접두사로 충분하면 이 훅 대신 Tailwind 클래스를 사용할 것.
 * JS 로직 분기가 필요한 경우에만 사용.
 */
export function useBreakpoint(): BreakpointState {
  const [state, setState] = useState<BreakpointState>(() =>
    getState(typeof window !== "undefined" ? window.innerWidth : LG_BREAKPOINT)
  );

  useEffect(() => {
    const smQuery = window.matchMedia(`(min-width: ${SM_BREAKPOINT}px)`);
    const lgQuery = window.matchMedia(`(min-width: ${LG_BREAKPOINT}px)`);

    const update = () => setState(getState(window.innerWidth));

    smQuery.addEventListener("change", update);
    lgQuery.addEventListener("change", update);

    return () => {
      smQuery.removeEventListener("change", update);
      lgQuery.removeEventListener("change", update);
    };
  }, []);

  return state;
}
