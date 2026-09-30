// TodayInbox.tsx — 홈 최상단. 기억이 만든 "오늘 할 일" 인박스.
// 백엔드 4a 전에는 훅이 항상 error를 낸다 — 그 상태에서도 홈 나머지는 그대로여야 하므로
// 배너 없이 조용히 아무것도 그리지 않는다(로딩은 스켈레톤 1줄로만 알린다).
import { Link } from "react-router-dom";

import { StatusBadge } from "@/components/ui/Badge";
import EmptyState from "@/components/ui/EmptyState";
import RefreshButton from "@/components/ui/RefreshButton";
import Skeleton from "@/components/ui/Skeleton";
import cn from "@/lib/cn";
import { useToday } from "@/pages/EquipmentHome/hooks/useToday";
import { resolveTodayLink } from "@/pages/EquipmentHome/utils/resolveTodayLink";
import type { TodayItem } from "@/types/timeline";
import { dDayLabel } from "@/utils/datetime";
import { emphasisTone, toneColor } from "@/utils/statusColors";

function TodayRow({ item }: { item: TodayItem }) {
  const tone = emphasisTone(item.emphasis);
  // D-day 표기는 앱 전체가 utils/datetime.dDayLabel 한 곳으로 통일한다(fix-list 4b) —
  // D-Day/D+n 표기가 이겼다. "오늘"·"n일 경과" 같은 이 화면만의 문구는 쓰지 않는다.
  const dday = dDayLabel(item.daysRemaining);
  const link = resolveTodayLink(item);

  const body = (
    <div className="flex flex-wrap items-center gap-2 py-2.5">
      <span className={cn("h-2 w-2 shrink-0 rounded-full", toneColor(tone).solid)} aria-hidden />
      <span className="text-sm font-semibold text-slate-900">{item.title}</span>
      <span className="text-sm text-slate-500">{item.detail}</span>
      {dday && <span className="ml-auto shrink-0 text-xs tabular-nums text-slate-400">{dday}</span>}
    </div>
  );

  if (!link) return <div>{body}</div>;
  if (link.external) {
    return (
      <a href={link.href} target="_blank" rel="noreferrer" className="block rounded px-1 hover:bg-slate-50">
        {body}
      </a>
    );
  }
  return (
    <Link to={link.href} className="block rounded px-1 hover:bg-slate-50">
      {body}
    </Link>
  );
}

export default function TodayInbox() {
  const { view, loading, error, refetch } = useToday();

  // 에러는 조용히 — 인박스 자리에 배너를 남기지 않고 아무것도 그리지 않는다. 홈 나머지는 그대로다.
  if (error) return null;

  if (loading) {
    return (
      <div className="mb-4 rounded-lg border border-slate-200 bg-white p-4 shadow-card">
        <Skeleton className="h-5 w-56" />
      </div>
    );
  }

  if (!view) return null;

  return (
    <div className="mb-4 rounded-lg border border-slate-200 bg-white p-4 shadow-card">
      <div className="flex items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-base font-semibold text-slate-900">오늘 할 일 · {view.asOf}</h2>
          {view.criticalCount > 0 && <StatusBadge tone="high">긴급 {view.criticalCount}</StatusBadge>}
          {view.warningCount > 0 && <StatusBadge tone="pending">주의 {view.warningCount}</StatusBadge>}
        </div>
        <RefreshButton onClick={refetch} />
      </div>

      {view.items.length === 0 ? (
        <EmptyState message="오늘 처리할 항목이 없습니다" className="mt-3 py-10" />
      ) : (
        <ul className="mt-2 divide-y divide-slate-100">
          {view.items.map((item, idx) => (
            <li key={`${item.kind}-${item.linkType}-${item.refId ?? "none"}-${idx}`}>
              <TodayRow item={item} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
