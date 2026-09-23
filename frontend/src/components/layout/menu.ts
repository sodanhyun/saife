import { Camera, ClipboardList, History, Siren, type LucideIcon } from "lucide-react";

export interface MenuItem {
  key: string;
  path: string;
  label: string;
  icon: LucideIcon;
}

/** 시연 순서와 같다. 마지막이 타임라인인 이유는 앞의 기록이 한 설비 ID 위에 쌓인 것을 마지막에 보여주기 위해서다. */
export const MENU: MenuItem[] = [
  { key: "work-plan", path: "/work-plan", label: "작업계획서", icon: ClipboardList },
  { key: "vision", path: "/vision", label: "사진 판독", icon: Camera },
  { key: "incident", path: "/incident", label: "사고 등록", icon: Siren },
  { key: "timeline", path: "/timeline", label: "설비 타임라인", icon: History },
];

export const LANDING_PATH = "/work-plan";

/** 사용자 선호가 없을 때 접힌 채로 여는 화면 — 대화+트레이스 2열이 폭을 다 쓴다. */
export const COLLAPSED_BY_DEFAULT = new Set(["/work-plan"]);

export const SITE_NAME = "가상 정밀금속 사업장";

/** 사용자 선호가 있으면 그것, 없으면 화면별 기본값(작업계획서만 접힘). */
export function resolveSidebarCollapsed(pref: boolean | null, pathname: string): boolean {
  return pref ?? COLLAPSED_BY_DEFAULT.has(pathname);
}
