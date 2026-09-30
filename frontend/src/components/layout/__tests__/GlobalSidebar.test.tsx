// 데스크톱 경계선 토글이 키보드로 접근 가능한 네이티브 button인지 확인한다.
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

// 사이드바 하단 SystemStatusLine이 마운트 시 조회한다 — 실제 네트워크 호출 금지.
vi.mock("@/api/systemApi", () => ({ systemApi: { status: vi.fn().mockResolvedValue(null) } }));

import GlobalSidebar from "@/components/layout/GlobalSidebar";
import { useUiStore } from "@/stores/uiStore";

beforeEach(() => {
  useUiStore.setState({ sidebarCollapsed: null });
  localStorage.clear();
});

describe("GlobalSidebar", () => {
  it("경계선 토글이 button 요소이고 클릭하면 sidebarCollapsed가 뒤집힌다", () => {
    render(
      <MemoryRouter initialEntries={["/vision"]}>
        <GlobalSidebar isOpen={false} onClose={() => {}} />
      </MemoryRouter>,
    );

    const toggle = screen.getByRole("button", { name: "메뉴 접기" });
    expect(toggle.tagName).toBe("BUTTON");

    fireEvent.click(toggle);

    expect(useUiStore.getState().sidebarCollapsed).toBe(true);
  });

  it("접힌 상태에서 누르면 펼쳐지고 이름이 '메뉴 접기'로 바뀐다", () => {
    useUiStore.setState({ sidebarCollapsed: true });
    render(
      <MemoryRouter initialEntries={["/vision"]}>
        <GlobalSidebar isOpen={false} onClose={() => {}} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole("button", { name: "메뉴 펼치기" }));

    expect(useUiStore.getState().sidebarCollapsed).toBe(false);
    expect(screen.getByRole("button", { name: "메뉴 접기" })).toBeInTheDocument();
  });
});
