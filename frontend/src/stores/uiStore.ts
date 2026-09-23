import { create } from "zustand";

const STORAGE_KEY = "saife.sidebar-collapsed";

function readStored(): boolean | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === null ? null : v === "true";
  } catch {
    return null; // 사생활 보호 모드 등 — 선호 없음으로 간다
  }
}

function writeStored(v: boolean) {
  try {
    localStorage.setItem(STORAGE_KEY, String(v));
  } catch {
    /* 무시 */
  }
}

interface UiState {
  /** null = 사용자가 정한 적 없음. 그때는 화면별 기본값(작업계획서만 접힘)을 따른다. */
  sidebarCollapsed: boolean | null;
  setSidebarCollapsed: (v: boolean) => void;
  /** 지금 화면에 보이는 상태(current)의 반대를 사용자 선호로 저장한다. */
  toggleSidebar: (current: boolean) => void;
}

export const useUiStore = create<UiState>()((set) => ({
  sidebarCollapsed: readStored(),
  setSidebarCollapsed: (v) => {
    writeStored(v);
    set({ sidebarCollapsed: v });
  },
  toggleSidebar: (current) => {
    writeStored(!current);
    set({ sidebarCollapsed: !current });
  },
}));
