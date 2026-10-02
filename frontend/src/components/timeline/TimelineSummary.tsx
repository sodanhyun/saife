// TimelineSummary.tsx — 설비 상세의 머리. 메타 한 줄, 현재 등급과 등급 근거, 등급 이력, 수치 다섯 개.
// 화면의 주인공 면이라 shadow-lift를 쓴다.
import { ChevronRight } from "lucide-react";

import { buildStory, gradeHistory, yearMarks } from "@/components/timeline/storyModel";
import { StatusBadge } from "@/components/ui/Badge";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { ACCIDENT_LABEL, RISK_LABEL } from "@/types/domain";
import type { EquipmentTimeline } from "@/types/timeline";
import { monthDay, plainText } from "@/utils/plainText";
import { riskColor, toneColor, type Tone } from "@/utils/statusColors";

interface Props {
  timeline: EquipmentTimeline;
  /** 페이지 제목이 설비 이름이 아닐 때(/timeline) 머리에 설비 이름을 같이 밝힌다 */
  showName?: boolean;
}

function Stat({ label, value, tone, extra }: { label: string; value: number; tone?: Tone; extra?: string | null }) {
  const active = value > 0 && tone;
  return (
    <div className="min-w-0 px-5 first:pl-0">
      <p className="whitespace-nowrap text-xs font-bold tracking-wide text-slate-500">{label}</p>
      {/* 크기와 색을 한 cn()에 넣으면 tailwind-merge가 text-display를 색으로 보고 지운다. 요소를 나눈다 */}
      <p className="mt-1 text-display tabular-nums">
        <span className={cn(value === 0 ? "text-slate-300" : active ? toneColor(tone).text : "text-slate-900")}>{value}</span>
      </p>
      {extra && <p className="whitespace-nowrap text-xs font-semibold text-pending-text">{extra}</p>}
    </div>
  );
}

export default function TimelineSummary({ timeline, showName = false }: Props) {
  const { equipment, summary, events } = timeline;
  const story = buildStory(events);
  const history = gradeHistory(story);
  const latest = history.at(-1);
  const years = yearMarks(history.map((s) => s.ev.at), String(new Date().getFullYear()));
  const level = summary.currentRiskLevel;
  const meta = [
    equipment.objectCode,
    plainText(equipment.locationTag),
    plainText(equipment.processName),
    equipment.introducedOn ? `${equipment.introducedOn} 도입` : null,
  ].filter(Boolean);

  return (
    <section aria-label="설비 요약" className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lift animate-rise-in">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b border-slate-100 px-6 py-3">
        {showName && <span className="text-base font-semibold text-slate-900">{equipment.name}</span>}
        <span className="text-sm text-slate-500">{meta.join("  /  ")}</span>
      </div>
      {/* 좁은 화면(1280 폭 포함)에서는 한 열로 쌓는다. 수치 칸이 등급 근거를 좁히지 않게 */}
      <div className="grid gap-6 px-6 py-5 2xl:grid-cols-[minmax(0,1fr)_auto]">
        <div className="flex min-w-0 items-start gap-4">
          {level ? (
            <RiskGradeMark level={level} size="lg" />
          ) : (
            <span className="grid h-14 w-14 shrink-0 place-items-center rounded-lg border border-dashed border-slate-300 text-xs font-semibold text-slate-400">미평가</span>
          )}
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <p className="text-stage font-semibold text-slate-900">
                현재 등급
                {level && <span className={cn("ml-1.5", riskColor(level).text)}>{RISK_LABEL[level]}</span>}
              </p>
              {summary.currentRiskAxis && <span className="text-sm text-slate-500">{ACCIDENT_LABEL[summary.currentRiskAxis]}</span>}
              {summary.lastAssessedOn && <span className="text-sm tabular-nums text-slate-400">{summary.lastAssessedOn} 평가</span>}
              {summary.headline && (
                <StatusBadge tone={summary.overdueActionCount > 0 || summary.incidentCount > 0 ? "high" : "pending"}>{summary.headline}</StatusBadge>
              )}
            </div>
            {latest?.trace && (
              <p className="mt-2 rounded-md bg-panel px-3 py-1.5 text-sm text-slate-700">
                <span className="mr-2 text-xs font-bold tracking-wide text-slate-500">등급 근거</span>
                {latest.trace}
              </p>
            )}
            {history.length > 1 && (
              <ol aria-label="등급 이력" className="mt-3 flex flex-wrap items-center gap-1">
                {history.map((s, i) => (
                  <li key={s.ev.id} className="flex items-center gap-1">
                    {i > 0 && <ChevronRight className="h-3.5 w-3.5 text-slate-300" aria-hidden />}
                    {years[i] && <span className="mr-0.5 text-xs font-semibold tabular-nums text-slate-400">{years[i]}</span>}
                    <span className={cn("inline-flex items-center gap-1.5 rounded border px-1.5 py-0.5 text-xs", s.gradeChange ? riskColor(s.ev.riskLevel!).chip : "border-slate-200 text-slate-600")}>
                      <span className="tabular-nums text-slate-500">{monthDay(s.ev.at)}</span>
                      <span className={cn("font-bold", riskColor(s.ev.riskLevel!).text)}>{RISK_LABEL[s.ev.riskLevel!]}</span>
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </div>
        <div className="flex items-start divide-x divide-slate-100 border-t border-slate-100 pt-4 2xl:border-l 2xl:border-t-0 2xl:pl-6 2xl:pt-0">
          <Stat label="위험성평가" value={summary.assessmentCount} />
          <Stat label="작업 전 점검" value={summary.workPlanCount} />
          <Stat label="사고" value={summary.incidentCount} tone="high"
            extra={(summary.nearMissCount ?? 0) > 0 ? `아차사고 ${summary.nearMissCount}` : null} />
          <Stat label="미이행 조치" value={summary.unfinishedActionCount} tone="pending" />
          <Stat label="기한 경과" value={summary.overdueActionCount} tone="high" />
        </div>
      </div>
    </section>
  );
}
