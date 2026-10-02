// TimelineList.tsx — 한 설비의 이야기. 날짜는 왼쪽, 내용은 오른쪽, 사이에 사건 종류별 표식.
// 사고와 등급 변화만 강하게, 나머지는 절제한다. 사건을 누르면 이어진 기록만 밝고 나머지는 물러난다.
import { AlertTriangle, Check, ClipboardList, Clock, CornerDownRight } from "lucide-react";

import { formUrl } from "@/api/formUrl";
import { plainText } from "@/components/timeline/plainText";
import { buildStory, storyLinkedIds, type StoryEvent, type StoryRelation } from "@/components/timeline/storyModel";
import { RiskBadge, StatusBadge } from "@/components/ui/Badge";
import EmptyState from "@/components/ui/EmptyState";
import LinkButton from "@/components/ui/LinkButton";
import cn from "@/lib/cn";
import { ACCIDENT_LABEL, RISK_LABEL, WORK_PLAN_STATUS_LABEL, type WorkPlanStatus } from "@/types/domain";
import { EVENT_LABEL, type TimelineEvent } from "@/types/timeline";
import { riskColor, toneColor, workPlanStatusTone, type Tone } from "@/utils/statusColors";

function formHref(ev: TimelineEvent): string | null {
  if (ev.type === "ASSESSMENT") return formUrl.assessment(ev.refId);
  if (ev.type === "INCIDENT") return formUrl.incident(ev.refId);
  if (ev.type === "WORK_PLAN") return formUrl.workPlan(ev.refId);
  return null;
}

const ACTION_STATUS: Record<string, { label: string; tone: Tone }> = {
  DONE: { label: "이행 완료", tone: "low" },
  PENDING: { label: "이행 예정", tone: "neutral" },
  OVERDUE: { label: "기한 경과", tone: "high" },
};

const REPORT_STATUS: Record<string, { label: string; tone: Tone }> = {
  SUBMITTED: { label: "조사표 제출 완료", tone: "neutral" },
  REQUIRED: { label: "조사표 제출 필요", tone: "pending" },
  OVERDUE: { label: "조사표 기한 경과", tone: "high" },
  NOT_REQUIRED: { label: "조사표 대상 아님", tone: "neutral" },
};

function statusOf(ev: TimelineEvent): { label: string; tone: Tone } | null {
  if (!ev.status) return null;
  if (ev.type === "ACTION") return ACTION_STATUS[ev.status] ?? null;
  if (ev.type === "INCIDENT") return REPORT_STATUS[ev.status] ?? null;
  if (ev.type === "WORK_PLAN" && ev.status in WORK_PLAN_STATUS_LABEL) {
    const s = ev.status as WorkPlanStatus;
    return { label: WORK_PLAN_STATUS_LABEL[s], tone: workPlanStatusTone(s) };
  }
  return null;
}

/** 사건 종류별 표식. 평가는 등급 사각, 사고는 큰 원, 나머지는 작은 원 */
function Marker({ ev }: { ev: TimelineEvent }) {
  if (ev.type === "ASSESSMENT") {
    return (
      <span className={cn("grid h-8 w-8 place-items-center rounded-md text-sm font-bold text-white ring-4 ring-page", ev.riskLevel ? riskColor(ev.riskLevel).solid : "bg-slate-400")}>
        {ev.riskLevel ? RISK_LABEL[ev.riskLevel] : "?"}
      </span>
    );
  }
  if (ev.type === "INCIDENT") {
    return (
      <span className="relative grid h-9 w-9 place-items-center">
        <span className="absolute inset-0 rounded-full bg-risk-high animate-ping-soft" aria-hidden />
        <span className="relative grid h-9 w-9 place-items-center rounded-full bg-risk-high text-white ring-4 ring-page">
          <AlertTriangle className="h-4 w-4" strokeWidth={2.4} aria-hidden />
        </span>
      </span>
    );
  }
  if (ev.type === "ACTION") {
    const done = ev.status === "DONE";
    const overdue = ev.status === "OVERDUE";
    const Icon = done ? Check : overdue ? AlertTriangle : Clock;
    return (
      <span className={cn("grid h-6 w-6 place-items-center rounded-full border-2 bg-white ring-4 ring-page",
        done ? "border-risk-low text-risk-low" : overdue ? "border-risk-high bg-risk-high text-white" : "border-slate-300 text-slate-400")}>
        <Icon className="h-3.5 w-3.5" strokeWidth={2.6} aria-hidden />
      </span>
    );
  }
  return (
    <span className="grid h-6 w-6 place-items-center rounded-full border-2 border-slate-300 bg-white text-slate-500 ring-4 ring-page">
      <ClipboardList className="h-3.5 w-3.5" strokeWidth={2.2} aria-hidden />
    </span>
  );
}

/** "이행 완료 (근거 C-11-2020)" → "근거 C-11-2020". 상태 문구만 있으면 비운다(배지가 이미 말한다) */
function actionBody(detail: string): string | null {
  const ref = detail.match(/근거\s+[^\s)]+/);
  return ref ? ref[0] : null;
}

const RELATION_TEXT: Record<StoryRelation["tone"], string> = {
  high: "font-medium text-risk-high-text",
  progress: "text-progress-text",
  neutral: "text-slate-500",
};

function StoryCard({ story, focused, linked, dimmed, onToggle }: {
  story: StoryEvent; focused: boolean; linked: boolean; dimmed: boolean; onToggle: () => void;
}) {
  const { ev, detail, trace, gradeChange, relations } = story;
  const incident = ev.type === "INCIDENT";
  const status = statusOf(ev);
  const href = formHref(ev);
  const changeTone: Tone = gradeChange ? (gradeChange.improved ? "low" : "high") : "neutral";
  // 평가의 "위험요인 n건 평가"는 제목 옆 메타로, 조치의 상태 문구는 배지와 겹치니 근거 번호만 남긴다
  const meta = ev.type === "ASSESSMENT"
    ? [ev.accidentType ? `${ACCIDENT_LABEL[ev.accidentType]} 위험` : null, detail].filter(Boolean).join(", ")
    : null;
  const body = ev.type === "ASSESSMENT" ? null : ev.type === "ACTION" ? actionBody(detail) : detail;

  return (
    <div className={cn("relative transition-opacity duration-300", dimmed && "opacity-40")}>
      <div role="button" tabIndex={0} aria-pressed={focused} onClick={onToggle}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onToggle(); }
        }}
        className={cn("w-full cursor-pointer rounded-xl border bg-white px-5 py-3 pr-32 text-left text-slate-900 shadow-card transition-colors",
          incident && "border-risk-high-border",
          gradeChange && !incident && toneColor(changeTone).border,
          !incident && !gradeChange && "border-slate-200",
          focused && "ring-2 ring-progress-border",
          !focused && linked && "border-progress-border bg-progress-bg",
          !focused && !linked && "hover:bg-slate-50",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border")}>
        <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1">
          <p className={cn("font-semibold", incident ? "text-headline" : "text-stage")}>
            <span className={cn(incident ? "text-risk-high-text" : "text-slate-900")}>{plainText(ev.title)}</span>
          </p>
          {gradeChange && (
            <StatusBadge tone={gradeChange.improved ? "low" : "high"}>
              등급 {RISK_LABEL[gradeChange.from]} → {RISK_LABEL[gradeChange.to]}
            </StatusBadge>
          )}
          {status && <StatusBadge tone={status.tone}>{status.label}</StatusBadge>}
          {meta && <span className="text-xs text-slate-400">{meta}</span>}
        </div>
        {trace && ev.riskLevel && (
          <div className="mt-1.5 flex items-start gap-2">
            <RiskBadge level={ev.riskLevel} className="mt-1" />
            <p className="min-w-0 flex-1 rounded-md bg-panel px-2.5 py-1 text-sm text-slate-700">{trace}</p>
          </div>
        )}
        {body && <p className={cn("mt-1 text-sm", incident ? "text-slate-700" : "text-slate-500")}>{body}</p>}
        {relations.length > 0 && (
          <ul className="mt-1.5 space-y-0.5">
            {relations.map((r) => (
              <li key={`${r.targetId}-${r.text}`} className={cn("flex items-start gap-1.5 text-sm", RELATION_TEXT[r.tone])}>
                <CornerDownRight className="mt-0.5 h-3.5 w-3.5 shrink-0 opacity-70" aria-hidden />
                <span>{r.text}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      {/* 법정 서식 링크는 role=button 카드 밖 형제 요소다(보조기술이 버튼 안에 갇히지 않게) */}
      {href && <LinkButton href={href} external className="absolute right-4 top-4">법정 서식</LinkButton>}
    </div>
  );
}

interface Props { events: TimelineEvent[]; focusId: string | null; onFocus: (id: string | null) => void }

export default function TimelineList({ events, focusId, onFocus }: Props) {
  if (events.length === 0) return <EmptyState message="이 설비에 기록된 사건이 없습니다" />;
  const story = buildStory(events);
  const linked = storyLinkedIds(story, focusId);
  return (
    <ol aria-label="설비 이력" className="relative">
      {story.map((s, i) => {
        const ev = s.ev;
        const prev = story[i - 1]?.ev;
        const year = ev.at.slice(0, 4);
        const showYear = !prev || prev.at.slice(0, 4) !== year;
        const sameDay = prev?.at === ev.at;
        const focused = focusId === ev.id;
        const isLinked = linked.has(ev.id);
        return (
          <li key={ev.id} className="grid grid-cols-[5.5rem_3.5rem_minmax(0,1fr)] animate-rise-in" style={{ animationDelay: `${120 + i * 70}ms` }}>
            <div className="pt-3 text-right">
              {showYear && <p className="text-xs tabular-nums text-slate-400">{year}</p>}
              {!sameDay && (
                <p className="text-stage font-semibold tabular-nums">
                  <span className={cn(ev.type === "INCIDENT" ? "text-risk-high-text" : "text-slate-700")}>{ev.at.slice(5)}</span>
                </p>
              )}
              <p className={cn("text-xs", ev.type === "INCIDENT" ? "font-semibold text-risk-high-text" : "text-slate-400")}>{EVENT_LABEL[ev.type]}</p>
            </div>
            <div className="relative flex justify-center">
              <span className={cn("absolute w-px bg-slate-200", i === 0 ? "top-5" : "top-0", i === story.length - 1 ? "h-5" : "bottom-0")} aria-hidden />
              <span className="relative mt-3"><Marker ev={ev} /></span>
            </div>
            <div className="pb-3">
              <StoryCard story={s} focused={focused} linked={isLinked} dimmed={focusId !== null && !focused && !isLinked}
                onToggle={() => onFocus(focused ? null : ev.id)} />
            </div>
          </li>
        );
      })}
    </ol>
  );
}
