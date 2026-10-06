import { describe, expect, it } from "vitest";

import { COLLAPSED_BY_DEFAULT, MENU_GROUPS, resolveSidebarCollapsed } from "@/components/layout/menu";

describe("resolveSidebarCollapsed — 사이드바 접힘 기본값 해석", () => {
  it("선호가 없으면(null) 작업계획서 화면도 펼쳐진다(영상 해상도에서 메뉴 이름이 보여야 한다)", () => {
    expect(resolveSidebarCollapsed(null, "/work-plan")).toBe(false);
  });

  it("선호가 없으면(null) 사진 판독 화면은 펼쳐진다", () => {
    expect(resolveSidebarCollapsed(null, "/vision")).toBe(false);
  });

  it("선호가 있으면(false) 화면별 기본값보다 우선한다", () => {
    expect(resolveSidebarCollapsed(false, "/work-plan")).toBe(false);
  });
});

// §1-7 "메뉴 테스트" — IA 뒤집기의 데이터 구조를 렌더링과 무관하게 고정한다.
// 렌더링(그룹 라벨 표시, 활성 항목 강조 등)은 GlobalSidebar.test.tsx가 별도로 본다.
describe("MENU_GROUPS: 현장 사진 분석이 첫 항목, 기록은 뒤 그룹", () => {
  it("메뉴는 현장 사진 분석, 작업 전 점검, 그다음 기록 그룹(설비 현황, 사고 보고, 개선대책) 순이다", () => {
    expect(MENU_GROUPS.map((g) => g.label)).toEqual([null, "기록"]);
    const items = MENU_GROUPS.flatMap((g) => g.items);
    expect(items.map((i) => [i.label, i.path])).toEqual([
      ["현장 사진 분석", "/"],
      ["작업 전 점검", "/work-plan"],
      ["설비 현황", "/equipment"],
      ["사고 보고", "/incident"],
      ["개선대책", "/action"],
    ]);
  });

  it("'/'는 COLLAPSED_BY_DEFAULT에 들어있지 않다(홈은 기본적으로 펼쳐진다)", () => {
    expect(COLLAPSED_BY_DEFAULT.has("/")).toBe(false);
  });
});
