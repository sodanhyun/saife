import { formUrl } from "@/api/formUrl";
import { Badge, RiskBadge } from "@/components/ui/Badge";
import EmptyState from "@/components/ui/EmptyState";
import LinkButton from "@/components/ui/LinkButton";
import cn from "@/lib/cn";
import { ACCIDENT_LABEL } from "@/types/domain";
import { EVENT_LABEL, type TimelineEvent } from "@/types/timeline";
import { formatDate } from "@/utils/datetime";
import { emphasisTone, toneColor } from "@/utils/statusColors";
import { linkedIds } from "@/pages/Timeline/utils/linkedIds";

function formHref(ev: TimelineEvent): string | null {
  if (ev.type === "ASSESSMENT") return formUrl.assessment(ev.refId);
  if (ev.type === "INCIDENT") return formUrl.incident(ev.refId);
  if (ev.type === "WORK_PLAN") return formUrl.workPlan(ev.refId);
  return null;
}

interface Props { events: TimelineEvent[]; focusId: string | null; onFocus: (id: string | null) => void }

export default function TimelineList({ events, focusId, onFocus }: Props) {
  if (events.length === 0) return <EmptyState message="이 설비에 기록된 사건이 없습니다" />;
  const linked = linkedIds(events, focusId);
  return (
    <ol className="relative ml-2 border-l-2 border-slate-300 pl-6">
      {events.map((ev) => {
        const focused = focusId === ev.id;
        const dot = toneColor(emphasisTone(ev.emphasis)).solid;
        const href = formHref(ev);
        const toggle = () => onFocus(focused ? null : ev.id);
        return (
          <li key={ev.id} className="relative mb-4">
            <span className={cn("absolute -left-8 top-5 h-3.5 w-3.5 rounded-full ring-4 ring-page", dot)} />
            {/* 카드에는 이제 대화형 자식이 없다 — 법정 서식 링크는 카드 밖 형제 요소로 뺐다
                (보조기술이 role="button" 영역 안에 갇히지 않고 링크에 곧장 접근하도록). */}
            <div role="button" tabIndex={0} aria-pressed={focused} onClick={toggle}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); }
              }}
              className={cn("w-full cursor-pointer rounded-lg border bg-white p-4 text-left shadow-card transition-colors",
                focused ? "border-progress-border ring-2 ring-progress-border" : linked.has(ev.id) ? "border-progress-border bg-progress-bg" : "border-slate-200 hover:bg-slate-50",
                !focused && "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border")}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs tabular-nums text-slate-400">{formatDate(ev.at)}</span>
                <Badge>{EVENT_LABEL[ev.type]}</Badge>
                <span className="text-stage font-semibold">{ev.title}</span>
                {ev.riskLevel && <RiskBadge level={ev.riskLevel} />}
                {ev.accidentType && <span className="text-xs text-slate-500">{ACCIDENT_LABEL[ev.accidentType]}</span>}
              </div>
              <p className="mt-1 text-sm text-slate-700">{ev.detail}</p>
              {ev.linkedLabels.length > 0 && <p className="mt-1 text-xs text-slate-500">연결된 기록: {ev.linkedLabels.join(" · ")}</p>}
            </div>
            {href && (
              <div className="mt-2 pl-4">
                <LinkButton href={href} external>법정 서식</LinkButton>
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}
