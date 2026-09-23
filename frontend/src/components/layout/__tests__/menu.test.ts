import { describe, expect, it } from "vitest";

import { resolveSidebarCollapsed } from "@/components/layout/menu";

describe("resolveSidebarCollapsed — 사이드바 접힘 기본값 해석", () => {
  it("선호가 없으면(null) 작업계획서 화면은 접힌다", () => {
    expect(resolveSidebarCollapsed(null, "/work-plan")).toBe(true);
  });

  it("선호가 없으면(null) 사진 판독 화면은 펼쳐진다", () => {
    expect(resolveSidebarCollapsed(null, "/vision")).toBe(false);
  });

  it("선호가 있으면(false) 화면별 기본값보다 우선한다", () => {
    expect(resolveSidebarCollapsed(false, "/work-plan")).toBe(false);
  });
});
