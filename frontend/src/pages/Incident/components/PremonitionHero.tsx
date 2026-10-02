// PremonitionHero.tsx — UC2의 한 장면. 등록 직후 "이 사고는 예고되어 있었습니다"와 그 증거(사고 전 기록)를 보인다.
// 문장은 응답 사실로만 조립한다(premonition.ts). 예고 증거가 없으면 같은 자리에 무채색으로 소환 결과만 말한다.
import { useEffect, useRef } from "react";

import { Badge } from "@/components/ui/Badge";
import LinkButton from "@/components/ui/LinkButton";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { buildPremonition, plain } from "@/pages/Incident/utils/premonition";
import { ACCIDENT_LABEL, SEVERITY_LABEL } from "@/types/domain";
import type { IncidentRegisterResponse } from "@/types/incident";
import { formatDate, formatDateTime } from "@/utils/datetime";
import { toneColor } from "@/utils/statusColors";

export default function PremonitionHero({ r }: { r: IncidentRegisterResponse }) {
  const p = buildPremonition(r);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const c = toneColor(p.predicted ? "high" : "neutral");
  const inc = r.incident;
  const others = r.recall.priorHazards.filter((h) => h.hazardId !== p.hazard?.hazardId).length;

  // 결과가 뜨면 제목으로 포커스를 옮긴다: 스크린리더가 바로 읽고, 키보드 사용자는 여기서 이어간다
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: false });
  }, [inc.id]);

  const meta = [
    `사고 #${inc.id}`,
    inc.equipmentName ?? "설비 미상",
    r.recall.locationTag,
    formatDateTime(inc.occurredAt),
    [inc.accidentType && ACCIDENT_LABEL[inc.accidentType], inc.severity && SEVERITY_LABEL[inc.severity],
      inc.leaveDays !== null ? `${inc.leaveDays}일` : null].filter(Boolean).join(" "),
  ].filter(Boolean) as string[];

  return (
    <article
      aria-labelledby="incident-hero-title"
      className={cn("relative overflow-hidden rounded-xl border bg-white shadow-lift animate-rise-in", c.border)}
    >
      <span aria-hidden className={cn("absolute inset-y-0 left-0 w-1.5", c.solid)} />

      <div className="grid gap-6 px-8 pb-6 pt-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)]">
        <div className="flex min-w-0 flex-col">
          <p className={cn("text-xs font-bold tracking-wide", p.predicted ? "text-risk-high-text" : "text-slate-500")}>
            {meta.join("  /  ")}
          </p>
          <h2
            id="incident-hero-title"
            ref={headingRef}
            tabIndex={-1}
            className={`text-display ${cn("mt-3 focus:outline-none", p.predicted ? "text-risk-high-text" : "text-slate-900")}`}
          >
            {p.predicted ? "이 사고는 예고되어 있었습니다" : "사전 기록 소환 결과"}
          </h2>
          <p className="mt-3 text-stage leading-relaxed text-slate-700">{p.proof}</p>

          {p.overdue?.dueDate && (
            <GapStrip dueDate={p.overdue.dueDate} occurredAt={inc.occurredAt} days={p.overdue.overdueDays ?? 0} />
          )}
        </div>

        <section aria-label="사고 전 이 설비에 기록돼 있던 것" className="min-w-0 rounded-lg border border-slate-200 bg-page">
          <h3 className="border-b border-slate-200 px-5 py-2.5 text-xs font-bold tracking-wide text-slate-500">
            사고 전 이 설비에 기록돼 있던 것
          </h3>
          {p.hazard ? (
            <div className="flex items-start gap-4 px-5 py-4 animate-rise-in" style={{ animationDelay: "160ms" }}>
              {p.hazard.lastRiskLevel ? (
                <RiskGradeMark level={p.hazard.lastRiskLevel} size="lg" />
              ) : (
                <span className="grid h-14 w-14 shrink-0 place-items-center rounded-lg bg-panel text-xs text-slate-500">미평가</span>
              )}
              <div className="min-w-0 flex-1">
                <p className="text-xs font-semibold text-slate-500">
                  위험성평가{p.hazard.lastAssessedOn ? ` ${formatDate(p.hazard.lastAssessedOn)}` : ""}
                </p>
                <p className="mt-0.5 flex flex-wrap items-center gap-2 text-stage font-semibold text-slate-900">
                  {p.hazard.accidentType && <Badge>{ACCIDENT_LABEL[p.hazard.accidentType]}</Badge>}
                  {p.hazard.missingControl ?? p.hazard.description}
                </p>
                {p.hazard.lastRuleTrace && (
                  <p className="mt-1.5 rounded-md bg-panel px-2 py-1 font-mono text-xs text-slate-600">{plain(p.hazard.lastRuleTrace)}</p>
                )}
              </div>
            </div>
          ) : (
            <p className="px-5 py-4 text-sm text-slate-500">같은 발생형태의 위험요인 기록 없음</p>
          )}
          {p.overdue && (
            <div
              className="flex items-start gap-4 border-t border-slate-200 px-5 py-4 animate-rise-in"
              style={{ animationDelay: "280ms" }}
            >
              <span className="grid h-14 w-14 shrink-0 place-items-center rounded-lg border border-risk-high-border bg-risk-high-bg text-sm font-bold text-risk-high-text">
                미이행
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-xs font-semibold text-slate-500">감소대책{p.overdue.dueDate ? `, 기한 ${formatDate(p.overdue.dueDate)}` : ""}</p>
                <p className="mt-0.5 text-stage font-semibold text-slate-900">{p.overdue.content}</p>
                <p className="mt-1 text-sm font-semibold text-risk-high-text">사고 당시 기한 {p.overdue.overdueDays}일 경과</p>
              </div>
            </div>
          )}
          {others > 0 && (
            <p className="border-t border-slate-200 px-5 py-2 text-xs text-slate-500">
              그 밖의 위험요인 {others}건도 함께 소환됨
            </p>
          )}
        </section>
      </div>

      <footer className="flex flex-wrap items-center gap-2 border-t border-slate-100 bg-slate-50 px-8 py-3">
        <p className="mr-auto text-sm text-slate-600">
          이 사고는 {inc.equipmentName ?? "설비"} 타임라인에 기록됐고, 산업재해조사표 서식에 사고 정보와 초안 문안이 채워졌습니다.
        </p>
        {inc.equipmentId !== null && <LinkButton href={`/equipment/${inc.equipmentId}`}>설비 타임라인 보기</LinkButton>}
        <LinkButton href={`/form/incident/${inc.id}`} external>산업재해조사표 서식</LinkButton>
      </footer>
    </article>
  );
}

/** 조치 기한에서 사고까지의 공백을 선 하나로 보인다 */
function GapStrip({ dueDate, occurredAt, days }: { dueDate: string; occurredAt: string; days: number }) {
  return (
    <div className="mt-auto pt-8" aria-label={`조치 기한 ${formatDate(dueDate)}부터 사고까지 ${days}일`}>
      <div className="relative flex items-center">
        <span className="z-10 h-3 w-3 shrink-0 rounded-full border-2 border-slate-400 bg-white" />
        <span className="relative h-0.5 flex-1 bg-slate-200">
          <span
            aria-hidden
            className="absolute inset-0 origin-left bg-risk-high animate-grow-x"
            style={{ animationDelay: "350ms", animationDuration: "900ms" }}
          />
          <span className="absolute left-1/2 top-0 -translate-x-1/2 -translate-y-full pb-1.5 text-sm font-bold tabular-nums text-risk-high-text">
            {days}일 미이행
          </span>
        </span>
        <span className="z-10 h-3.5 w-3.5 shrink-0 rounded-full bg-risk-high ring-4 ring-risk-high-bg" />
      </div>
      <div className="mt-1.5 flex justify-between text-xs text-slate-500">
        <span>
          조치 기한 <span className="tabular-nums">{formatDate(dueDate)}</span>
        </span>
        <span className="font-semibold text-risk-high-text">
          사고 <span className="tabular-nums">{formatDate(occurredAt)}</span>
        </span>
      </div>
    </div>
  );
}
