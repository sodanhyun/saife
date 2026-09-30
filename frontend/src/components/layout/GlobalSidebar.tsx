import { useState } from "react";

import { X } from "lucide-react";
import { useLocation, useNavigate } from "react-router-dom";

import { useSystemStatus } from "@/components/layout/hooks/useSystemStatus";
import cn from "@/lib/cn";
import { useUiStore } from "@/stores/uiStore";
import { LANDING_PATH, MENU_GROUPS, resolveSidebarCollapsed, SITE_NAME } from "@/components/layout/menu";
import SystemStatusLine from "@/components/layout/SystemStatusLine";

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

interface TooltipState {
  label: string;
  top: number;
  left: number;
}

/** 접힌 레일의 항목 라벨 — overflow 영향을 받지 않도록 fixed */
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

  const { status: systemStatus, loading: systemStatusLoading, refetch: refetchSystemStatus } = useSystemStatus();

  // "/"는 모든 경로의 접두어라 startsWith로 판정하면 항상 활성화된다 — 완전 일치로만 본다.
  const isActive = (path: string) => (path === "/" ? pathname === "/" : pathname.startsWith(path));
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

  const brand = (
    <button onClick={() => go(LANDING_PATH)} className="flex flex-col items-start hover:opacity-80 transition-opacity">
      <span className="text-lg font-bold tracking-tight text-white leading-none">SAIFE</span>
      {!collapsed && <span className="mt-1 text-xs text-slate-400 whitespace-nowrap">설비 ID로 잇는 안전 데이터 코어</span>}
    </button>
  );

  const nav = (railCollapsed: boolean) => (
    <nav className={cn("flex-1 space-y-1 overflow-y-auto overflow-x-hidden py-2", railCollapsed ? "px-1.5" : "px-3")}>
      {MENU_GROUPS.map((group, gi) => (
        <div key={group.key} className={gi > 0 ? "mt-3 border-t border-slate-800/60 pt-3" : undefined}>
          {group.label && !railCollapsed && (
            <p className="px-3 pb-1 text-xs font-semibold uppercase tracking-wide text-slate-500">{group.label}</p>
          )}
          {group.items.map((item) => {
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
                  "relative w-full flex items-center rounded-lg transition-colors duration-200 group",
                  railCollapsed ? "justify-center p-2.5" : "gap-3 px-3 py-2.5",
                  active ? "text-white bg-slate-800/70" : "text-slate-500 hover:text-white hover:bg-slate-800/40",
                )}
              >
                {active && !railCollapsed && (
                  <span className="absolute left-0 top-1/2 -translate-y-1/2 w-1 h-5 bg-progress rounded-r-full" />
                )}
                <Icon size={20} className={cn("shrink-0 transition-colors", active ? "text-progress-border" : "text-slate-500 group-hover:text-white")} />
                {!railCollapsed && <span className={cn("text-stage whitespace-nowrap", active ? "font-semibold" : "font-medium")}>{item.label}</span>}
              </button>
            );
          })}
        </div>
      ))}
    </nav>
  );

  const footer = (railCollapsed: boolean) => (
    <div className={cn("border-t border-slate-800/60 text-xs text-slate-500", railCollapsed ? "px-2 py-3 text-center" : "p-4")}>
      {railCollapsed ? (
        "가상"
      ) : (
        <>
          <p className="text-slate-300 font-medium">{SITE_NAME}</p>
          <p className="mt-0.5">가상 사업장 · 데이터 전부 가상</p>
        </>
      )}
    </div>
  );

  return (
    <>
      {/* 데스크톱 레일 — sticky 풀뷰포트 */}
      <aside className={cn("hidden lg:flex flex-col h-screen sticky top-0 shrink-0 z-40 overflow-hidden bg-slate-950 text-white", collapsed ? "w-16" : "w-56")}>
        <div className={cn("flex items-center border-b border-slate-800/60", collapsed ? "py-4 px-2 justify-center" : "h-16 px-4")}>{brand}</div>
        {nav(collapsed)}
        {footer(collapsed)}
        {!collapsed && (
          <div className="border-t border-slate-800/60">
            <SystemStatusLine status={systemStatus} onRefresh={refetchSystemStatus} refreshing={systemStatusLoading} />
          </div>
        )}
      </aside>

      {/* 경계선 토글 */}
      <button
        type="button"
        onClick={() => toggleSidebar(collapsed)}
        onMouseEnter={() => setEdgeHover(true)}
        onMouseLeave={() => setEdgeHover(false)}
        aria-label={collapsed ? "메뉴 펼치기" : "메뉴 접기"}
        className="hidden lg:block relative w-1.5 h-screen sticky top-0 cursor-pointer shrink-0 z-50 -ml-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border"
      >
        <div aria-hidden className={cn("absolute left-1/2 -translate-x-1/2 inset-y-0 rounded-full transition-all duration-200", edgeHover ? "w-1 bg-progress/80" : "w-0.5 bg-slate-800/30")} />
        <div aria-hidden className="absolute -left-4 -right-4 inset-y-0" />
      </button>

      <FixedTooltip tooltip={tooltip} />

      {/* 모바일 오버레이·드로어 */}
      {isOpen && <div className="fixed inset-0 z-[60] bg-black/50 lg:hidden" onClick={onClose} />}
      <aside className={cn("fixed top-0 left-0 bottom-0 z-[70] w-72 lg:hidden bg-slate-950 text-white transition-transform duration-300", isOpen ? "translate-x-0" : "-translate-x-full")}>
        <div className="h-16 px-4 flex items-center justify-between border-b border-slate-800/60">
          {brand}
          <button onClick={onClose} aria-label="닫기" className="p-1 text-white/60 hover:text-white hover:bg-white/10 rounded-md">
            <X size={22} />
          </button>
        </div>
        {nav(false)}
        {footer(false)}
        <div className="border-t border-slate-800/60">
          <SystemStatusLine status={systemStatus} onRefresh={refetchSystemStatus} refreshing={systemStatusLoading} />
        </div>
      </aside>
    </>
  );
}
