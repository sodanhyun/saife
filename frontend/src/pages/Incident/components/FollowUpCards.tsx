// FollowUpCards.tsx — 후속 조치 3장: 수시평가(작성 중, 확정일), 산업재해조사표(기한, 제출일), 작업 보류.
import { Link } from "react-router-dom";

import { formUrl } from "@/api/formUrl";
import { RiskBadge, StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import { buttonClassName } from "@/components/ui/buttonStyles";
import LinkButton from "@/components/ui/LinkButton";
import cn from "@/lib/cn";
import { plain, reportRequired, reportValue, shortDate } from "@/pages/Incident/utils/priorRecord";
import type { WorkPlanStatus } from "@/types/domain";
import type { IncidentRegisterResponse } from "@/types/incident";
import { formatDate } from "@/utils/datetime";
import { reportDutyTone, toneColor, workPlanStatusTone, type Tone } from "@/utils/statusColors";

interface Props {
  r: IncidentRegisterResponse;
  onMarkSubmitted?: () => void;
  submitting?: boolean;
}

export default function FollowUpCards({ r, onMarkSubmitted, submitting = false }: Props) {
  return (
    <section aria-labelledby="follow-up-title">
      <h2 id="follow-up-title" className="mb-3 text-base font-semibold text-slate-900">
        후속 조치
      </h2>
      <div className="grid gap-4 lg:grid-cols-3">
        <FollowUp r={r} />
        <Report r={r} onMarkSubmitted={onMarkSubmitted} submitting={submitting} />
        <Hold r={r} />
      </div>
    </section>
  );
}

function Card({
  label,
  value,
  tone,
  action,
  children,
  delay,
}: {
  label: string;
  value: string;
  tone?: Tone;
  action?: React.ReactNode;
  children?: React.ReactNode;
  delay: number;
}) {
  return (
    <article
      aria-label={label}
      className="flex flex-col rounded-xl border border-slate-200 bg-white px-5 py-4 shadow-card animate-rise-in"
      style={{ animationDelay: `${delay}ms` }}
    >
      <p className="text-xs font-bold tracking-wide text-slate-500">{label}</p>
      <p className={cn("mt-1.5 text-headline font-bold tabular-nums", tone && tone !== "neutral" ? toneColor(tone).text : "text-slate-900")}>
        {value}
      </p>
      <div className="mt-2 flex-1 text-sm text-slate-600">{children}</div>
      {action && <div className="mt-4 flex flex-wrap justify-end gap-2">{action}</div>}
    </article>
  );
}

/** 수시평가: 시행규칙 제37조제2항제3호, 관련 작업 시작 전까지. 작성 중이면 그 화면으로 간다 */
function FollowUp({ r }: { r: IncidentRegisterResponse }) {
  const { followUp, incident } = r;
  const confirmed = followUp.status === "CONFIRMED";
  const axis = followUp.regraded.find((g) => g.accidentType !== null && g.accidentType === incident.accidentType) ?? null;
  const value = !followUp.assessmentId
    ? "생성 안 됨"
    : confirmed
      ? `확정 ${formatDate(followUp.confirmedOn)}`
      : "작성 중";
  return (
    <Card
      label="수시평가"
      value={value}
      tone={!followUp.assessmentId || confirmed ? "neutral" : "pending"}
      delay={120}
      action={
        followUp.assessmentId ? (
          <Link to={`/assessment/${followUp.assessmentId}`} className={buttonClassName("secondary", "sm")}>
            {confirmed ? "수시평가 보기" : "수시평가"}
          </Link>
        ) : undefined
      }
    >
      {axis && (
        <div className="flex items-start gap-2">
          <RiskBadge level={axis.after} />
          <span className="min-w-0">
            {axis.missingControl ?? "사고로 확인된 위험요인"}
            <span className="block text-xs text-slate-500">{plain(axis.ruleTrace)}</span>
          </span>
        </div>
      )}
      <p className="mt-2 text-xs text-slate-500">{plain(followUp.legalBasis)}</p>
    </Card>
  );
}

/** 산업재해조사표: 사망 또는 3일 이상 휴업, 발생일부터 1개월. 기한은 날짜로 말한다(D-n 숫자 강조 없음) */
function Report({ r, onMarkSubmitted, submitting }: Props) {
  const duty = r.reportDuty;
  const required = reportRequired(duty);
  return (
    <Card
      label="산업재해조사표"
      value={reportValue(duty)}
      tone={duty.status === "OVERDUE" ? "high" : "neutral"}
      delay={200}
      action={
        required ? (
          <>
            {onMarkSubmitted && (
              <Button variant="ghost" size="sm" loading={submitting} onClick={onMarkSubmitted}>
                제출 완료
              </Button>
            )}
            <LinkButton href={formUrl.incident(r.incident.id)} external>
              조사표 작성
            </LinkButton>
          </>
        ) : undefined
      }
    >
      <div className="flex items-center gap-2">
        <StatusBadge tone={reportDutyTone(duty.status)}>{duty.statusLabel}</StatusBadge>
        {duty.dueDate && required && <span className="text-xs text-slate-500">발생일부터 1개월</span>}
      </div>
      {!duty.dueDate && duty.status !== "SUBMITTED" && <p className="mt-2 text-xs text-slate-500">{plain(duty.basis)}</p>}
    </Card>
  );
}

/** 작업 보류: 같은 설비의 진행 중 작업 전 점검. 수시평가 확정으로 풀리면 재승인 대기로 보인다 */
function Hold({ r }: { r: IncidentRegisterResponse }) {
  const plans = r.affectedWorkPlans ?? [];
  const held = plans.filter((p) => p.status === "HOLD");
  const released = plans.length > 0 && held.length === 0;
  return (
    <Card
      label="작업 보류"
      value={released ? `해제 ${plans.length}건` : `${held.length}건`}
      tone={held.length > 0 ? "high" : "neutral"}
      delay={280}
      action={
        plans.length > 0 ? <LinkButton href={`/work-plan?planId=${plans[0].workPlanId}`}>작업 전 점검</LinkButton> : undefined
      }
    >
      {plans.length === 0 ? (
        <p className="text-slate-500">보류 대상 없음</p>
      ) : (
        <ul className="divide-y divide-slate-100">
          {plans.map((p) => (
            <li key={p.workPlanId} className="flex items-start gap-3 py-1.5">
              <span className="w-12 shrink-0 pt-0.5 text-xs tabular-nums text-slate-500">{shortDate(p.workDate)}</span>
              <span className="min-w-0 flex-1 break-keep text-slate-800">{p.workName}</span>
              <StatusBadge tone={workPlanStatusTone(p.status)}>{holdStatusLabel(p.status)}</StatusBadge>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/** 보류된 작업의 지금 상태. 수시평가 확정 뒤에는 재승인 대기(SUBMITTED)로 돌아간다 */
function holdStatusLabel(status: WorkPlanStatus): string {
  switch (status) {
    case "HOLD":
      return "작업 보류";
    case "SUBMITTED":
      return "재승인 대기";
    case "APPROVED":
    case "CONDITIONAL":
      return "승인";
    case "CLOSED":
      return "완료";
    default:
      return "작성 중";
  }
}
