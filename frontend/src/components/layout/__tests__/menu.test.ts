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
describe("MENU_GROUPS — 설비 현황이 첫 항목이고 기존 4개는 문서별 보기 그룹이다", () => {
  it("첫 그룹의 첫 항목은 설비 현황(path=/, icon=LayoutGrid)이다", () => {
    const [homeGroup] = MENU_GROUPS;
    expect(homeGroup.items).toEqual([
      { key: "home", path: "/", label: "설비 현황", icon: LayoutGrid },
    ]);
  });

  it("두 번째 그룹 라벨은 '문서별 보기'이고, 항목 4개가 동사 라벨·경로 순서 그대로다", () => {
    const [, documentsGroup] = MENU_GROUPS;
    expect(documentsGroup.label).toBe("문서별 보기");
    expect(documentsGroup.items.map((item) => [item.label, item.path])).toEqual([
      ["작업 신고", "/work-plan"],
      ["사진 점검", "/vision"],
      ["사고 신고", "/incident"],
      ["설비 타임라인", "/timeline"],
    ]);
  });

  it("'/'는 COLLAPSED_BY_DEFAULT에 들어있지 않다(홈은 기본적으로 펼쳐진다)", () => {
    expect(COLLAPSED_BY_DEFAULT.has("/")).toBe(false);
  });
});
