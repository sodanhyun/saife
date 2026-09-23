import { formUrl } from "@/api/formUrl";
import { Badge, RiskBadge } from "@/components/ui/Badge";
import Callout from "@/components/ui/Callout";
import Card from "@/components/ui/Card";
import KpiCell from "@/components/ui/KpiCell";
import LinkButton from "@/components/ui/LinkButton";
import { ACCIDENT_LABEL, RISK_LABEL } from "@/types/domain";
import type { IncidentRegisterResponse } from "@/types/incident";
import { dDayLabel, formatDate, formatDateTime } from "@/utils/datetime";
import { reportDutyTone } from "@/utils/statusColors";

/** 등록 결과 — ① 소환 배너 ② KPI 3개 ③ 사전 이력·수시평가 2열 ④ 조사표 초안 순서 고정 */
export default function IncidentResult({ r }: { r: IncidentRegisterResponse }) {
  const dutyTone = reportDutyTone(r.reportDuty.status);
  return (
    <div className="space-y-4">
      {/* 이 화면에서 가장 중요한 줄 */}
      <Callout tone={r.recall.predicted ? "high" : "neutral"} title={r.recall.headline}>
        {r.recall.equipmentName}
        {r.recall.locationTag ? ` · ${r.recall.locationTag}` : ""}
      </Callout>

      <div className="grid gap-3 sm:grid-cols-3">
        <KpiCell
          label="조사표 제출 기한"
          value={r.reportDuty.dueDate ? dDayLabel(r.reportDuty.daysRemaining) : r.reportDuty.statusLabel}
          subText={
            r.reportDuty.dueDate
              ? `${r.reportDuty.statusLabel} · ${formatDate(r.reportDuty.dueDate)}`
              : r.reportDuty.basis
          }
          tone={dutyTone}
          title={r.reportDuty.basis}
        />
        <KpiCell
          label="휴업일수"
          value={r.incident.leaveDays === null ? "미입력" : `${r.incident.leaveDays}일`}
          subText="3일 이상이면 조사표 제출"
        />
        <KpiCell
          label="수시평가"
          value={r.followUp.assessmentId ? `#${r.followUp.assessmentId}` : "-"}
          subText={r.followUp.kindLabel}
          tone={r.followUp.assessmentId ? "progress" : undefined}
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="사고 전 이 설비에 기록돼 있던 사항">
          <h3 className="text-xs font-semibold text-slate-500">위험요인</h3>
          <ul className="mt-1 space-y-1 text-sm">
            {r.recall.priorHazards.length === 0 && <li className="text-slate-400">없음</li>}
            {r.recall.priorHazards.map((h) => (
              <li key={h.hazardId} className={h.sameAxisAsIncident ? "font-medium" : ""}>
                <Badge className="mr-1">{h.accidentType ? ACCIDENT_LABEL[h.accidentType] : "-"}</Badge>
                {h.missingControl ?? h.description}
                {h.lastRiskLevel && (
                  <span className="ml-1 text-slate-500">
                    · 최근 평가 {RISK_LABEL[h.lastRiskLevel]} ({formatDate(h.lastAssessedOn)})
                  </span>
                )}
                {h.sameAxisAsIncident && <span className="ml-1 text-risk-high-text">— 사고와 같은 발생형태</span>}
              </li>
            ))}
          </ul>
          <h3 className="mt-3 text-xs font-semibold text-slate-500">미이행 조치</h3>
          <ul className="mt-1 space-y-1 text-sm">
            {r.recall.unfinishedActions.length === 0 && <li className="text-slate-400">없음</li>}
            {r.recall.unfinishedActions.map((a) => (
              <li key={a.actionId}>
                {a.content}
                <span className="ml-1 text-slate-500">
                  (기한 {formatDate(a.dueDate)}
                  {a.overdueDays !== null && a.overdueDays > 0 && (
                    <span className="text-risk-high-text"> · {a.overdueDays}일 경과</span>
                  )}
                  )
                </span>
              </li>
            ))}
          </ul>
          {r.recall.warnedAt && (
            <p className="mt-2 text-sm text-risk-high-text">
              작업 전 브리핑으로 경고 전달됨 — {formatDateTime(r.recall.warnedAt)}
            </p>
          )}
        </Card>

        <Card
          title={`수시평가 자동 생성${r.followUp.assessmentId ? ` — #${r.followUp.assessmentId}` : ""}`}
          description={r.followUp.legalBasis}
          actions={
            r.followUp.assessmentId ? (
              <LinkButton href={formUrl.assessment(r.followUp.assessmentId)} external>위험성평가표</LinkButton>
            ) : undefined
          }
        >
          <ul className="space-y-2 text-sm">
            {r.followUp.regraded.map((g) => (
              <li key={g.hazardId}>
                <span className="text-slate-500">{g.accidentType ? ACCIDENT_LABEL[g.accidentType] : "-"}</span>{" "}
                {g.before && g.changed ? (
                  <>
                    <RiskBadge level={g.before} /> <span className="text-slate-400">→</span>{" "}
                    <RiskBadge level={g.after} />
                  </>
                ) : (
                  <>
                    <RiskBadge level={g.after} /> <span className="text-xs text-slate-500">유지</span>
                  </>
                )}
                {/* 등급 옆에는 항상 룰 트레이스 */}
                <p className="mt-0.5 font-mono text-xs text-slate-500">{g.ruleTrace}</p>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <Card
        title={
          <span className="flex items-center gap-2">
            산업재해조사표 초안{!r.draft.aiGenerated && <Badge>AI 생성 아님</Badge>}
          </span>
        }
        actions={<LinkButton href={formUrl.incident(r.incident.id)} external>산업재해조사표</LinkButton>}
      >
        <h3 className="text-xs font-semibold text-slate-500">재해 발생 원인</h3>
        <p className="whitespace-pre-wrap text-sm">{r.draft.cause}</p>
        <h3 className="mt-3 text-xs font-semibold text-slate-500">재발 방지 계획</h3>
        <p className="whitespace-pre-wrap text-sm">{r.draft.prevention}</p>
        <p className="mt-3 text-xs text-slate-400">{r.draft.disclaimer}</p>
      </Card>
    </div>
  );
}
