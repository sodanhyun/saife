// 데스크톱 경계선 토글이 키보드로 접근 가능한 네이티브 button인지 확인한다.
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

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
});
