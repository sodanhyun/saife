import { LayoutGrid } from "lucide-react";
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
describe("MENU_GROUPS: 한 줄 목록, 설비 현황이 첫 항목", () => {
  it("메뉴는 설비 현황, 작업 전 점검, 순회점검, 사고 보고 순이다", () => {
    const items = MENU_GROUPS.flatMap((g) => g.items);
    expect(items.map((i) => [i.label, i.path])).toEqual([
      ["설비 현황", "/"],
      ["작업 전 점검", "/work-plan"],
      ["순회점검", "/vision"],
      ["사고 보고", "/incident"],
    ]);
  });

  it("'/'는 COLLAPSED_BY_DEFAULT에 들어있지 않다(홈은 기본적으로 펼쳐진다)", () => {
    expect(COLLAPSED_BY_DEFAULT.has("/")).toBe(false);
  });
});
