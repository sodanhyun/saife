import { useState } from "react";

import { Building2, ChevronsUpDown, X } from "lucide-react";
import { useLocation, useNavigate } from "react-router-dom";

import { useSystemStatus } from "@/components/layout/hooks/useSystemStatus";
import cn from "@/lib/cn";
import { useUiStore } from "@/stores/uiStore";
import { CURRENT_USER, LANDING_PATH, MENU_GROUPS, resolveSidebarCollapsed, SITE_NAME } from "@/components/layout/menu";

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

interface TooltipState {
  label: string;
  top: number;
  left: number;
}

/** 접힌 레일의 항목 라벨. overflow 영향을 받지 않도록 fixed */
function FixedTooltip({ tooltip }: { tooltip: TooltipState | null }) {
  if (!tooltip) return null;
  return (
    <div
      className="fixed z-[200] px-2.5 py-1.5 bg-slate-800 text-white text-xs font-medium rounded-md whitespace-nowrap shadow-modal pointer-events-none"
      style={{ top: tooltip.top, left: tooltip.left, transform: "translateY(-50%)" }}
    >
      {tooltip.label}
    </div>
  );
}

export default function GlobalSidebar({ isOpen, onClose }: Props) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const pref = useUiStore((s) => s.sidebarCollapsed);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);
  const collapsed = resolveSidebarCollapsed(pref, pathname);
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);
  const [edgeHover, setEdgeHover] = useState(false);
  const { status } = useSystemStatus();

  // 설비 상세(/equipment/:id)는 설비 현황 아래에 있다
  const isActive = (path: string) => (path === "/" ? pathname === "/" || pathname.startsWith("/equipment") || pathname === "/timeline" : pathname.startsWith(path));
  const go = (path: string) => {
    navigate(path);
    onClose();
    setTooltip(null);
  };

  const showTooltip = (e: React.MouseEvent, label: string) => {
    if (!collapsed) return;
    const r = e.currentTarget.getBoundingClientRect();
    setTooltip({ label, top: r.top + r.height / 2, left: r.right + 10 });
  };

  const brand = (rail: boolean) => (
    <button onClick={() => go(LANDING_PATH)} aria-label="SAIFE 홈" className="flex items-center transition-opacity hover:opacity-90">
      <img src={rail ? "/brand/saife-mark.svg" : "/brand/saife-logo-inverse.svg"} alt="SAIFE" className={rail ? "h-8 w-8" : "h-8"} />
    </button>
  );

  const nav = (rail: boolean) => (
    <nav className={cn("flex-1 space-y-1 overflow-y-auto overflow-x-hidden py-4", rail ? "px-1.5" : "px-3")}>
      {MENU_GROUPS.flatMap((g) => g.items).map((item) => {
        const Icon = item.icon;
        const active = isActive(item.path);
        return (
          <button
            key={item.key}
            onClick={() => go(item.path)}
            onMouseEnter={(e) => showTooltip(e, item.label)}
            onMouseLeave={() => setTooltip(null)}
            aria-label={item.label}
            aria-current={active ? "page" : undefined}
            className={cn(
              "group relative flex w-full items-center rounded-lg transition-colors duration-200",
              rail ? "justify-center p-2.5" : "gap-3 px-3 py-2.5",
              active ? "bg-white/10 text-white" : "text-slate-400 hover:bg-white/5 hover:text-white",
            )}
          >
            <Icon size={19} className={cn("shrink-0 transition-colors", active ? "text-white" : "text-slate-500 group-hover:text-white")} />
            {!rail && <span className={cn("whitespace-nowrap text-stage", active ? "font-semibold" : "font-medium")}>{item.label}</span>}
          </button>
        );
      })}
    </nav>
  );

  // 사업장 전환과 사용자. 실제 SaaS처럼 하단에 둔다
  const footer = (rail: boolean) => (
    <div className={cn("space-y-1 border-t border-white/10", rail ? "px-1.5 py-3" : "p-3")}>
      {status?.demoMode && !rail && (
        <p className="mb-2 rounded-md bg-pending/20 px-2.5 py-1.5 text-xs font-medium text-pending-border">오프라인 모드</p>
      )}
      <button type="button" className={cn("flex w-full items-center rounded-lg text-left text-slate-300 hover:bg-white/5", rail ? "justify-center p-2.5" : "gap-3 px-3 py-2")}>
        <Building2 size={18} className="shrink-0 text-slate-400" aria-hidden />
        {!rail && (
          <>
            <span className="flex-1 truncate text-sm font-medium">{SITE_NAME}</span>
            <ChevronsUpDown size={15} className="text-slate-500" aria-hidden />
          </>
        )}
      </button>
      <div className={cn("flex items-center rounded-lg", rail ? "justify-center p-1.5" : "gap-3 px-3 py-2")}>
        <span aria-hidden className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-white/15 text-xs font-semibold text-white">{CURRENT_USER.name.slice(0, 1)}</span>
        {!rail && (
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-white">{CURRENT_USER.name}</span>
            <span className="block truncate text-xs text-slate-400">{CURRENT_USER.role}</span>
          </span>
        )}
      </div>
    </div>
  );

  return (
    <>
      <aside className={cn("sticky top-0 z-40 hidden h-screen shrink-0 flex-col overflow-hidden bg-brand-ink text-white lg:flex", collapsed ? "w-16" : "w-60")}>
        <div className={cn("flex h-16 items-center border-b border-white/10", collapsed ? "justify-center px-2" : "px-5")}>{brand(collapsed)}</div>
        {nav(collapsed)}
        {footer(collapsed)}
      </aside>

      {/* 경계선 토글 */}
      <button
        type="button"
        onClick={() => toggleSidebar(collapsed)}
        onMouseEnter={() => setEdgeHover(true)}
        onMouseLeave={() => setEdgeHover(false)}
        aria-label={collapsed ? "메뉴 펼치기" : "메뉴 접기"}
        className="relative sticky top-0 z-50 -ml-1.5 hidden h-screen w-1.5 shrink-0 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border lg:block"
      >
        <div aria-hidden className={cn("absolute inset-y-0 left-1/2 -translate-x-1/2 rounded-full transition-all duration-200", edgeHover ? "w-1 bg-progress/80" : "w-0.5 bg-slate-800/30")} />
        <div aria-hidden className="absolute inset-y-0 -left-4 -right-4" />
      </button>

      <FixedTooltip tooltip={tooltip} />

      {/* 모바일 오버레이와 드로어 */}
      {isOpen && <div className="fixed inset-0 z-[60] bg-black/50 lg:hidden" onClick={onClose} />}
      <aside className={cn("fixed bottom-0 left-0 top-0 z-[70] flex w-72 flex-col bg-brand-ink text-white transition-transform duration-300 lg:hidden", isOpen ? "translate-x-0" : "-translate-x-full")}>
        <div className="flex h-16 items-center justify-between border-b border-white/10 px-5">
          {brand(false)}
          <button onClick={onClose} aria-label="닫기" className="rounded-md p-1 text-white/60 hover:bg-white/10 hover:text-white">
            <X size={22} />
          </button>
        </div>
        {nav(false)}
        {footer(false)}
      </aside>
    </>
  );
}
