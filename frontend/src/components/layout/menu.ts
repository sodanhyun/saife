import { Camera, ClipboardList, LayoutGrid, ListChecks, Siren, type LucideIcon } from "lucide-react";

export interface MenuItem {
  key: string;
  path: string;
  label: string;
  icon: LucideIcon;
}

export interface MenuGroup {
  key: string;
  /** null이면 그룹 라벨을 표시하지 않는다(첫 항목 '설비 현황'은 그 자체로 그룹이 아니다) */
  label: string | null;
  items: MenuItem[];
}

/**
 * 첫 화면은 현장 사진 분석(사진 한 장으로 안전 문제와 예방 방법), 그다음 작업 전 점검.
 * 설비 현황, 사고 보고, 개선대책은 "기록" 그룹으로 뒤에 둔다.
 */
export const MENU_GROUPS: MenuGroup[] = [
  {
    key: "main",
    label: null,
    items: [
      { key: "vision", path: "/", label: "현장 사진 분석", icon: Camera },
      { key: "work-plan", path: "/work-plan", label: "작업 전 점검", icon: ClipboardList },
    ],
  },
  {
    key: "records",
    label: "기록",
    items: [
      { key: "home", path: "/equipment", label: "설비 현황", icon: LayoutGrid },
      { key: "incident", path: "/incident", label: "사고 보고", icon: Siren },
      { key: "action", path: "/action", label: "개선대책", icon: ListChecks },
    ],
  },
];

/** 평평한 목록이 필요한 곳(활성 경로 판정 등)을 위한 파생 — 그룹 정의를 다시 하지 않는다. */
export const MENU: MenuItem[] = MENU_GROUPS.flatMap((g) => g.items);

/** 현장 사진 분석이 첫 진입 화면이다 */
export const LANDING_PATH = "/";

/** 사용자 선호가 없을 때 접힌 채로 여는 화면 — 대화+트레이스 2열이 폭을 다 쓴다. */
export const COLLAPSED_BY_DEFAULT = new Set<string>();

export const SITE_NAME = "(주)샘플정밀 데모공장";

/** 로그인 사용자(인증 범위 밖이라 고정). 화면 하단 사용자 행에 쓴다 */
export const CURRENT_USER = { name: "홍길동", role: "안전관리자" };

/** 사용자 선호가 있으면 그것, 없으면 화면별 기본값(작업계획서만 접힘). */
export function resolveSidebarCollapsed(pref: boolean | null, pathname: string): boolean {
  return pref ?? COLLAPSED_BY_DEFAULT.has(pathname);
}
