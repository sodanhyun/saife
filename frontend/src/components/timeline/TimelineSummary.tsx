// TimelineSummary.tsx — 설비 상세/타임라인의 머리. 지금 등급(+룰 근거), 등급이 지나온 길, 수치 다섯 개.
// 화면의 주인공 면이라 shadow-lift를 쓴다. 헤드라인 한 줄만 읽혀도 논지가 전달돼야 한다(프로젝터).
import { ArrowRight } from "lucide-react";

import { plainText, monthDay } from "@/components/timeline/plainText";
import { buildStory } from "@/components/timeline/storyModel";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { ACCIDENT_LABEL, RISK_LABEL } from "@/types/domain";
import type { EquipmentTimeline } from "@/types/timeline";
import { riskColor, toneColor, type Tone } from "@/utils/statusColors";

interface Props {
  timeline: EquipmentTimeline;
  /** 설비 선택기 화면(/timeline)처럼 페이지 제목이 설비 이름이 아닐 때 머리에 설비를 밝힌다 */
  showIdentity?: boolean;
}

function Stat({ label, value, tone, note }: { label: string; value: number; tone?: Tone; note?: string }) {
  const active = value > 0 && tone;
  return (
    <div className="min-w-0 px-5 first:pl-0">
      <p className="text-xs font-bold tracking-wide text-slate-500">{label}</p>
      {/* 크기와 색을 한 cn()에 넣으면 tailwind-merge가 text-display를 색으로 보고 지운다. 요소를 나눈다 */}
      <p className="mt-1 text-display tabular-nums">
        <span className={cn(value === 0 ? "text-slate-300" : active ? toneColor(tone).text : "text-slate-900")}>{value}</span>
      </p>
      {note && value > 0 && <p className={cn("text-xs", active ? toneColor(tone).text : "text-slate-400")}>{note}</p>}
    </div>
  );
}

export default function TimelineSummary({ timeline, showIdentity = false }: Props) {
  const { equipment, summary, events } = timeline;
  const story = buildStory(events);
  const assessments = story.filter((s) => s.ev.type === "ASSESSMENT" && s.ev.riskLevel);
  const latest = assessments.at(-1);
  const level = summary.currentRiskLevel;
  const where = [equipment.locationTag, equipment.processName].filter(Boolean).map(plainText).join(" / ");

  return (
    <section aria-label="설비 요약" className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lift animate-rise-in">
      {showIdentity && (
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b border-slate-100 px-6 py-3">
          <span className="text-base font-semibold text-slate-900">{equipment.name}</span>
          {equipment.objectCode && <span className="font-mono text-xs text-slate-400">{equipment.objectCode}</span>}
          {where && <span className="text-sm text-slate-500">{where}</span>}
        </div>
      )}
      <div className="grid gap-6 px-6 py-5 lg:grid-cols-[minmax(0,1fr)_auto]">
        <div className="flex min-w-0 items-start gap-4">
          {level ? (
            <RiskGradeMark level={level} size="lg" />
          ) : (
            <span className="grid h-14 w-14 shrink-0 place-items-center rounded-lg border border-dashed border-slate-300 text-xs font-semibold text-slate-400">미평가</span>
          )}
          <div className="min-w-0 flex-1">
            <p className="text-xs font-bold tracking-wide text-slate-500">
              현재 등급
              {level && <span className={cn("ml-1", riskColor(level).text)}>{RISK_LABEL[level]}</span>}
              {summary.currentRiskAxis && <span className="ml-1">({ACCIDENT_LABEL[summary.currentRiskAxis]})</span>}
              {summary.lastAssessedOn && <span className="ml-2 font-medium text-slate-400">{summary.lastAssessedOn} 평가</span>}
            </p>
            <h2 className="mt-1 text-headline text-slate-900">{plainText(summary.headline)}</h2>
            {latest?.trace && (
              <p className="mt-2 rounded-md bg-panel px-3 py-1.5 text-sm text-slate-700">
                <span className="mr-2 text-xs font-bold tracking-wide text-slate-500">룰 근거</span>
                {latest.trace}
              </p>
            )}
            {assessments.length > 1 && (
              <ol aria-label="등급 이력" className="mt-3 flex flex-wrap items-center gap-1.5">
                {assessments.map((s, i) => (
                  <li key={s.ev.id} className="flex items-center gap-1.5">
                    {i > 0 && <ArrowRight className="h-3.5 w-3.5 text-slate-300" aria-hidden />}
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
        <div className="flex items-start divide-x divide-slate-100 lg:border-l lg:border-slate-100 lg:pl-6">
          <Stat label="위험성평가" value={summary.assessmentCount} />
          <Stat label="작업계획서" value={summary.workPlanCount} />
          <Stat label="사고" value={summary.incidentCount} tone="high" />
          <Stat label="미이행 조치" value={summary.unfinishedActionCount} tone="pending" />
          <Stat label="기한 경과" value={summary.overdueActionCount} tone="high" note="즉시 조치" />
        </div>
      </div>
    </section>
  );
}
