// TodayInbox.tsx — 홈의 주인공 "오늘 할 일". 행마다 무엇이, 어느 설비에서, 언제까지, 그래서 무엇을.
// 긴급도는 행 왼쪽 색 막대로만 말하고, 버튼은 모두 같은 모양이다.
// 데이터는 페이지가 내려준다(KPI와 같은 응답을 공유). 에러면 조용히 아무것도 그리지 않는다.
import { useState } from "react";

import { X } from "lucide-react";
import { Link } from "react-router-dom";

import { StatusBadge } from "@/components/ui/Badge";
import { buttonClassName } from "@/components/ui/buttonStyles";
import EmptyState from "@/components/ui/EmptyState";
import RefreshButton from "@/components/ui/RefreshButton";
import cn from "@/lib/cn";
import { KIND_LABEL, type TodayRowModel } from "@/pages/EquipmentHome/utils/todayModel";
import type { TodayView } from "@/types/timeline";
import { emphasisTone, toneColor } from "@/utils/statusColors";

const COLLAPSED_ROWS = 6;

function TodayRow({ row, index }: { row: TodayRowModel; index: number }) {
  const { item, action, due } = row;
  const tone = emphasisTone(item.emphasis);
  const btn = buttonClassName("secondary", "sm");
  return (
    <li className="relative grid grid-cols-[8rem_minmax(0,1fr)_11rem_6.5rem_7rem] items-center gap-x-5 py-3 pl-6 pr-5 animate-rise-in"
      style={{ animationDelay: `${80 + index * 50}ms` }}>
      <span className={cn("absolute inset-y-2 left-0 w-1 rounded-r", item.emphasis === "NORMAL" ? "bg-slate-200" : toneColor(tone).solid)} aria-hidden />
      <span className={cn("text-xs font-bold tracking-wide", item.emphasis === "NORMAL" ? "text-slate-500" : toneColor(tone).text)}>{KIND_LABEL[item.kind]}</span>
      <div className="min-w-0">
        <p className="flex min-w-0 items-center gap-2">
          {row.equipmentHref ? (
            <Link to={row.equipmentHref} className="truncate text-stage font-semibold text-slate-900 hover:underline">{row.title}</Link>
          ) : (
            <span className="truncate text-stage font-semibold text-slate-900">{row.title}</span>
          )}
          {row.awaitingApproval && <StatusBadge tone="pending">승인 대기</StatusBadge>}
        </p>
        {row.detail && <p className="mt-0.5 truncate text-sm text-slate-500">{row.detail}</p>}
      </div>
      <span className="truncate text-right text-sm text-slate-600">{item.equipmentName ?? "사업장 전체"}</span>
      <span className="text-right">
        {due && (
          <span className={cn("text-sm font-bold tabular-nums", due.tone === "neutral" ? "text-slate-500" : toneColor(due.tone).text)}>{due.text}</span>
        )}
      </span>
      <span className="flex justify-end">
        {action && (action.external ? (
          <a href={action.href} target="_blank" rel="noreferrer" className={btn}>{action.label}</a>
        ) : (
          <Link to={action.href} className={btn}>{action.label}</Link>
        ))}
      </span>
    </li>
  );
}

interface Props {
  view: TodayView | null;
  rows: TodayRowModel[];
  /** KPI로 좁힌 상태의 이름. null이면 전체 */
  filterLabel: string | null;
  onClearFilter: () => void;
  onRefresh: () => void;
}

export default function TodayInbox({ view, rows, filterLabel, onClearFilter, onRefresh }: Props) {
  const [expanded, setExpanded] = useState(false);
  if (!view) return null;
  const visible = expanded || filterLabel ? rows : rows.slice(0, COLLAPSED_ROWS);
  const hidden = rows.length - visible.length;

  return (
    <section aria-label="오늘 할 일" className="mb-6 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lift">
      <header className="flex items-center justify-between gap-3 border-b border-slate-100 px-6 py-4">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <h2 className="text-headline text-slate-900">오늘 할 일</h2>
          {view.criticalCount > 0 && <StatusBadge tone="high">긴급 {view.criticalCount}</StatusBadge>}
          {view.warningCount > 0 && <StatusBadge tone="pending">주의 {view.warningCount}</StatusBadge>}
          {filterLabel && (
            <button type="button" onClick={onClearFilter}
              className="inline-flex items-center gap-1 rounded border border-brand-line bg-brand-soft px-1.5 py-0.5 text-xs font-bold text-brand hover:bg-white">
              {filterLabel}
              <X className="h-3 w-3" aria-label="필터 해제" />
            </button>
          )}
        </div>
        <RefreshButton onClick={onRefresh} />
      </header>

      {rows.length === 0 ? (
        <EmptyState message={filterLabel ? "해당 항목 없음" : "오늘 처리할 항목 없음"} className="py-10" />
      ) : (
        <ul className="divide-y divide-slate-100">
          {visible.map((row, i) => <TodayRow key={row.key} row={row} index={i} />)}
        </ul>
      )}
      {hidden > 0 && (
        <button type="button" onClick={() => setExpanded(true)}
          className="w-full border-t border-slate-100 py-2.5 text-sm font-semibold text-slate-500 hover:bg-slate-50 hover:text-slate-800">
          {hidden}건 더 보기
        </button>
      )}
    </section>
  );
}
