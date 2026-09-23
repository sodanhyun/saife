import { beforeEach, describe, expect, it } from "vitest";

import { useUiStore } from "@/stores/uiStore";

describe("uiStore — 사이드바 접힘 선호", () => {
  beforeEach(() => {
    localStorage.clear();
    useUiStore.setState({ sidebarCollapsed: null });
  });

  it("초기값은 null(선호 없음)이다", () => {
    expect(useUiStore.getState().sidebarCollapsed).toBeNull();
  });

  it("toggleSidebar(current)는 현재 표시 상태의 반대를 저장한다", () => {
    useUiStore.getState().toggleSidebar(true);
    expect(useUiStore.getState().sidebarCollapsed).toBe(false);
    expect(localStorage.getItem("saife.sidebar-collapsed")).toBe("false");
  });
});
