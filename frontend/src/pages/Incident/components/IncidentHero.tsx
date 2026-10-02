// IncidentHero.tsx — 결과 화면의 주인공 면. 사실형 제목, 서식 버튼, 중대재해 안내, 사고 전 기록.
import { useEffect, useRef } from "react";

import { formUrl } from "@/api/formUrl";
import { Badge } from "@/components/ui/Badge";
import { buttonClassName } from "@/components/ui/buttonStyles";
import Callout from "@/components/ui/Callout";
import LinkButton from "@/components/ui/LinkButton";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { buildPriorRecord, incidentTitle, plain, reportRequired } from "@/pages/Incident/utils/priorRecord";
import { ACCIDENT_LABEL, SEVERITY_LABEL } from "@/types/domain";
import type { IncidentRegisterResponse } from "@/types/incident";
import { formatDate } from "@/utils/datetime";

export default function IncidentHero({ r }: { r: IncidentRegisterResponse }) {
  const inc = r.incident;
  const { hazard, action } = buildPriorRecord(r);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const overdue = action !== null && action.overdueDays !== null && action.overdueDays > 0;
  const nearMiss = inc.severity === "NEAR_MISS";
  const required = reportRequired(r.reportDuty);
  // 조사표 버튼: 제출할 것이 있으면 주 버튼, 제출을 마쳤거나 판단 보류면 보조 버튼, 제출 대상이 아니면 없다
  const showReportForm = !nearMiss && r.reportDuty.status !== "NOT_REQUIRED";

  // 결과가 뜨면 제목으로 포커스를 옮긴다: 스크린리더가 바로 읽고, 키보드 사용자는 여기서 이어간다
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
  }, [inc.id]);

  const meta = [
    r.recall.locationTag,
    inc.severity === "FATALITY"
      ? SEVERITY_LABEL.FATALITY
      : nearMiss
        ? null
        : inc.leaveDays !== null
          ? `휴업예상 ${inc.leaveDays}일`
          : null,
    [inc.injuryType, inc.injuryPart].filter(Boolean).join(", ") || null,
  ].filter(Boolean) as string[];

  return (
    <article
      aria-labelledby="incident-hero-title"
      className="rounded-xl border border-slate-200 bg-white shadow-lift animate-rise-in"
    >
      <header className="flex flex-wrap items-start gap-4 px-7 pb-5 pt-6">
        <div className="min-w-0 flex-1">
          <h2
            id="incident-hero-title"
            ref={headingRef}
            tabIndex={-1}
            className="text-headline font-bold text-slate-900 focus:outline-none"
          >
            {incidentTitle(inc)}
          </h2>
          {meta.length > 0 && <p className="mt-1 text-sm text-slate-500">{meta.join(", ")}</p>}
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          {showReportForm && (
            <a
              href={formUrl.incident(inc.id)}
              target="_blank"
              rel="noreferrer"
              className={buttonClassName(required ? "primary" : "secondary", "sm")}
            >
              산업재해조사표
            </a>
          )}
          <LinkButton href={formUrl.incidentReview(inc.id)} external>
            {nearMiss ? "사고 기록" : "재발방지 검토서"}
          </LinkButton>
          {inc.equipmentId !== null && <LinkButton href={`/equipment/${inc.equipmentId}`}>설비 이력</LinkButton>}
        </div>
      </header>

      {r.reportDuty.seriousAccidentPossible && (
        <div className="px-7 pb-5">
          <Callout tone="high" title="중대재해 해당 가능, 지체 없이 관할 지방고용노동관서 보고" />
        </div>
      )}

      <section aria-labelledby="prior-record-title" className="border-t border-slate-100 px-7 pb-6 pt-4">
        <h3 id="prior-record-title" className="text-xs font-bold tracking-wide text-slate-500">
          사고 전 기록
        </h3>

        {hazard === null && action === null ? (
          <p className="mt-3 text-sm text-slate-500">같은 발생형태의 위험요인 기록 없음</p>
        ) : (
          <>
            <div className="mt-3 grid gap-4 lg:grid-cols-2">
              {hazard && (
                <div className="flex items-start gap-4 rounded-lg border border-slate-200 px-5 py-4">
                  {hazard.lastRiskLevel ? (
                    <RiskGradeMark level={hazard.lastRiskLevel} size="lg" />
                  ) : (
                    <span className="grid h-14 w-14 shrink-0 place-items-center rounded-lg bg-panel text-xs font-semibold text-slate-500">
                      미평가
                    </span>
                  )}
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-semibold text-slate-500">
                      사고 전 평가{hazard.lastAssessedOn ? ` ${formatDate(hazard.lastAssessedOn)}` : ""}
                    </p>
                    <p className="mt-1 flex flex-wrap items-center gap-2 text-stage font-semibold text-slate-900">
                      {hazard.accidentType && <Badge>{ACCIDENT_LABEL[hazard.accidentType]}</Badge>}
                      {hazard.missingControl ?? hazard.description}
                    </p>
                    {hazard.lastRuleTrace && (
                      <p className="mt-2 rounded-md bg-panel px-2.5 py-1.5 text-sm text-slate-700">{plain(hazard.lastRuleTrace)}</p>
                    )}
                  </div>
                </div>
              )}
              {action && (
                <div className={cn("rounded-lg border px-5 py-4", overdue ? "border-risk-high-border" : "border-slate-200")}>
                  <p className="text-xs font-semibold text-slate-500">감소대책</p>
                  <p className="mt-1 text-stage font-semibold text-slate-900">{action.content}</p>
                  <dl className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-sm">
                    {action.owner && (
                      <div className="flex gap-1.5">
                        <dt className="text-slate-500">담당</dt>
                        <dd className="text-slate-800">{action.owner}</dd>
                      </div>
                    )}
                    {action.dueDate && (
                      <div className="flex gap-1.5">
                        <dt className="text-slate-500">기한</dt>
                        <dd className="tabular-nums text-slate-800">{formatDate(action.dueDate)}</dd>
                      </div>
                    )}
                    <div className="flex gap-1.5">
                      <dt className="text-slate-500">사고 시점</dt>
                      <dd className={cn("font-semibold", overdue ? "text-risk-high-text" : "text-slate-800")}>
                        {overdue || action.overdueDays === null ? "미이행" : "미이행, 기한 전"}
                      </dd>
                    </div>
                  </dl>
                </div>
              )}
            </div>
            {overdue && action.dueDate && (
              <GapStrip dueDate={action.dueDate} occurredAt={inc.occurredAt} days={action.overdueDays ?? 0} />
            )}
          </>
        )}
      </section>
    </article>
  );
}

/**
 * 감소대책 기한에서 사고까지의 공백. 한 줄의 선 위에 두 점을 얹는다(선은 끊기지 않고 점 아래로 지나간다).
 * 경과일은 이 선 위에만 쓴다(감소대책 칸에서는 반복하지 않는다).
 */
function GapStrip({ dueDate, occurredAt, days }: { dueDate: string; occurredAt: string; days: number }) {
  return (
    <div className="mt-7 px-1.5" aria-label={`감소대책 기한 ${formatDate(dueDate)}부터 사고까지 ${days}일`}>
      <div className="relative h-4">
        <span aria-hidden className="absolute inset-x-0 top-1/2 h-0.5 -translate-y-1/2 bg-slate-200" />
        <span
          aria-hidden
          className="absolute inset-x-0 top-1/2 h-0.5 -translate-y-1/2 origin-left bg-risk-high animate-grow-x"
          style={{ animationDelay: "350ms", animationDuration: "900ms" }}
        />
        <span
          aria-hidden
          className="absolute left-0 top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-slate-400 bg-white"
        />
        <span
          aria-hidden
          className="absolute right-0 top-1/2 h-3.5 w-3.5 -translate-y-1/2 translate-x-1/2 rounded-full bg-risk-high ring-4 ring-risk-high-bg"
        />
        <span className="absolute bottom-full left-1/2 -translate-x-1/2 pb-1 text-sm font-bold tabular-nums text-risk-high-text">
          {days}일 경과
        </span>
      </div>
      <div className="mt-1.5 flex justify-between text-xs text-slate-500">
        <span>
          기한 <span className="tabular-nums">{formatDate(dueDate)}</span>
        </span>
        <span className="font-semibold text-risk-high-text">
          사고 <span className="tabular-nums">{formatDate(occurredAt)}</span>
        </span>
      </div>
    </div>
  );
}
