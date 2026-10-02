// FollowUpCards.tsx — 후속 조치 3장: 수시평가(작업 재개 전), 산업재해조사표(기한), 작업 보류. 카드마다 버튼 하나.
import { formUrl } from "@/api/formUrl";
import { RiskBadge, StatusBadge } from "@/components/ui/Badge";
import LinkButton from "@/components/ui/LinkButton";
import cn from "@/lib/cn";
import { plain, shortDate } from "@/pages/Incident/utils/priorRecord";
import type { IncidentRegisterResponse } from "@/types/incident";
import { reportDutyTone, toneColor } from "@/utils/statusColors";

export default function FollowUpCards({ r }: { r: IncidentRegisterResponse }) {
  return (
    <section aria-labelledby="follow-up-title">
      <h2 id="follow-up-title" className="mb-3 text-xs font-bold tracking-wide text-slate-500">
        후속 조치
      </h2>
      <div className="grid gap-4 lg:grid-cols-3">
        <FollowUp r={r} />
        <Report r={r} />
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
  tone?: "high" | "pending" | "neutral";
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
      {action && <div className="mt-4 flex justify-end">{action}</div>}
    </article>
  );
}

/** 수시평가: 시행규칙 제37조제2항제3호, 관련 작업 시작 전까지 */
function FollowUp({ r }: { r: IncidentRegisterResponse }) {
  const { followUp, incident } = r;
  const axis = followUp.regraded.find((g) => g.accidentType === incident.accidentType) ?? null;
  return (
    <Card
      label="수시평가"
      value={followUp.assessmentId ? "작업 재개 전" : "생성 안 됨"}
      tone={followUp.assessmentId ? "pending" : "neutral"}
      delay={120}
      action={
        followUp.assessmentId ? (
          <LinkButton href={formUrl.assessment(followUp.assessmentId)} external>
            수시평가표
          </LinkButton>
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
function Report({ r }: { r: IncidentRegisterResponse }) {
  const duty = r.reportDuty;
  const submitted = duty.status === "SUBMITTED";
  const overdue = duty.status === "OVERDUE";
  const value = submitted
    ? "제출 완료"
    : duty.dueDate
      ? `기한 ${shortDate(duty.dueDate)}`
      : duty.status === "UNDETERMINED"
        ? "판단 보류"
        : "제출 대상 아님";
  return (
    <Card
      label="산업재해조사표"
      value={value}
      tone={overdue ? "high" : "neutral"}
      delay={200}
      action={
        <LinkButton href={formUrl.incident(r.incident.id)} external>
          조사표 작성
        </LinkButton>
      }
    >
      <div className="flex items-center gap-2">
        <StatusBadge tone={reportDutyTone(duty.status)}>{duty.statusLabel}</StatusBadge>
        {duty.dueDate && !submitted && <span className="text-xs text-slate-500">발생일부터 1개월</span>}
      </div>
      {!duty.dueDate && <p className="mt-2 text-xs text-slate-500">{plain(duty.basis)}</p>}
    </Card>
  );
}

/** 작업 보류: 같은 설비의 진행 중 작업 전 점검 */
function Hold({ r }: { r: IncidentRegisterResponse }) {
  const plans = r.affectedWorkPlans ?? [];
  return (
    <Card
      label="작업 보류"
      value={`${plans.length}건`}
      tone={plans.length > 0 ? "high" : "neutral"}
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
            <li key={p.workPlanId} className="flex items-center gap-3 py-1.5">
              <span className="w-12 shrink-0 text-xs tabular-nums text-slate-500">{shortDate(p.workDate)}</span>
              <span className="min-w-0 flex-1 truncate text-slate-800">{p.workName}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
