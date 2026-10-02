// AssessmentPage.tsx — 사고가 만든 수시평가(시행규칙 제37조제2항제3호). 참여 근로자, 위험요인별 허용 여부와 대책을
// 채우고 확정하면 같은 설비의 작업 보류가 재승인 대기로 풀린다. 작업 보류를 푸는 유일한 화면이다.
import { useState } from "react";

import { Link, useParams } from "react-router-dom";

import { formUrl } from "@/api/formUrl";
import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import { buttonClassName } from "@/components/ui/buttonStyles";
import EmptyState from "@/components/ui/EmptyState";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import LinkButton from "@/components/ui/LinkButton";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import AssessmentSkeleton from "@/pages/Assessment/AssessmentSkeleton";
import HazardCard from "@/pages/Assessment/components/HazardCard";
import LoadError from "@/pages/Assessment/components/LoadError";
import ParticipantsInput from "@/pages/Assessment/components/ParticipantsInput";
import { useFollowUp } from "@/pages/Assessment/hooks/useFollowUp";
import {
  hasErrors,
  initialFollowUpForm,
  priorStillValid,
  toFollowUpRequest,
  validateFollowUp,
  type FollowUpErrors,
  type FollowUpFormState,
} from "@/pages/Assessment/utils/followUpForm";
import { SEVERITY_LABEL, WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { FollowUpDetail } from "@/types/incident";
import { formatDate, formatDateTime } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

function todayKst(): string {
  return formatDate(new Date().toISOString());
}

function parseId(raw: string | undefined): number | null {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : null;
}

export default function AssessmentPage() {
  const { assessmentId } = useParams();
  const id = parseId(assessmentId);
  const f = useFollowUp(id);

  if (f.loading) return <AssessmentSkeleton />;
  if (f.notFound) {
    return (
      <PageLayout>
        <PageHeader title="수시평가" />
        <EmptyState message="수시평가를 찾을 수 없습니다" action={<LinkButton href="/incident">사고 보고</LinkButton>} />
      </PageLayout>
    );
  }
  if (f.error || !f.detail) {
    return (
      <PageLayout>
        <PageHeader title="수시평가" />
        <LoadError onRetry={f.refetch} />
      </PageLayout>
    );
  }
  // 확정하면 서버 응답으로 다시 그린다(입력 상태를 새 값으로 초기화)
  return <FollowUpEditor key={`${f.detail.assessmentId}-${f.detail.status}`} d={f.detail} confirm={f.confirm} confirming={f.confirming} />;
}

function FollowUpEditor({
  d,
  confirm,
  confirming,
}: {
  d: FollowUpDetail;
  confirm: ReturnType<typeof useFollowUp>["confirm"];
  confirming: boolean;
}) {
  const today = todayKst();
  const confirmed = d.status === "CONFIRMED";
  const [form, setForm] = useState<FollowUpFormState>(() => initialFollowUpForm(d, today));
  const [errors, setErrors] = useState<FollowUpErrors>({ hazards: {} });
  const [tried, setTried] = useState(false);

  const update = (next: FollowUpFormState) => {
    setForm(next);
    if (tried) setErrors(validateFollowUp(d, next, today));
  };

  const onConfirm = async () => {
    setTried(true);
    const e = validateFollowUp(d, form, today);
    setErrors(e);
    if (hasErrors(e)) return;
    await confirm(toFollowUpRequest(d, form));
  };

  const held = d.workPlans.filter((p) => p.status === "HOLD");
  const incidentTitle = [
    d.incidentTypeLabel ? `${d.incidentTypeLabel} ${d.severity === "NEAR_MISS" ? "아차사고" : "사고"}` : "사고",
    d.equipmentName ?? "설비 미상",
    formatDateTime(d.occurredAt),
  ].join(", ");

  return (
    <PageLayout>
      <PageHeader
        title="수시평가"
        actions={
          <>
            <StatusBadge tone={confirmed ? "low" : "pending"}>
              {confirmed ? `확정 ${formatDate(d.confirmedOn)}` : "작성 중"}
            </StatusBadge>
            <LinkButton href={formUrl.assessment(d.assessmentId)} external>
              위험성평가표
            </LinkButton>
            {!confirmed && (
              <Button loading={confirming} onClick={() => void onConfirm()}>
                확정
              </Button>
            )}
          </>
        }
      />

      <div className="space-y-6">
        <section aria-label="사고" className="rounded-xl border border-slate-200 bg-white px-6 py-4 shadow-card">
          <div className="flex flex-wrap items-baseline gap-x-6 gap-y-2">
            {d.incidentId !== null ? (
              <Link to={`/incident?incidentId=${d.incidentId}`} className="text-stage font-semibold text-slate-900 underline-offset-4 hover:underline">
                {incidentTitle}
              </Link>
            ) : (
              <span className="text-stage font-semibold text-slate-900">{incidentTitle}</span>
            )}
            <dl className="flex flex-wrap gap-x-6 gap-y-1 text-sm">
              {d.locationTag && <Meta label="장소" value={d.locationTag} />}
              {d.severity && <Meta label="재해 정도" value={SEVERITY_LABEL[d.severity]} />}
              <Meta label="근거" value={d.legalBasis} />
              <Meta label="생성" value={formatDate(d.assessedOn)} />
            </dl>
          </div>
        </section>

        <section aria-label="점검 정보" className="space-y-3">
          <SectionTitle>점검 정보</SectionTitle>
          <div className="grid gap-4 rounded-xl border border-slate-200 bg-white px-6 py-4 shadow-card md:grid-cols-3">
            <FormField label="담당자">
              {confirmed ? (
                <p className="py-2 text-sm text-slate-900">{form.inspector || "-"}</p>
              ) : (
                <Input value={form.inspector} placeholder="안전관리자" onChange={(e) => update({ ...form, inspector: e.target.value })} />
              )}
            </FormField>
            <FormField label="참여 근로자" required={!confirmed} error={errors.participants} className="md:col-span-2">
              <ParticipantsInput
                value={form.participants}
                disabled={confirmed}
                invalid={Boolean(errors.participants)}
                onChange={(names) => update({ ...form, participants: names })}
              />
            </FormField>
          </div>
        </section>

        <section aria-label="위험요인" className="space-y-3">
          <SectionTitle>위험요인 {d.hazards.length}</SectionTitle>
          {d.hazards.length === 0 ? (
            <EmptyState message="평가할 위험요인이 없습니다" className="py-10" />
          ) : (
            d.hazards.map((h, i) => (
              <HazardCard
                key={h.hazardId}
                h={h}
                draft={form.hazards[h.hazardId]}
                errors={errors.hazards[h.hazardId]}
                readOnly={confirmed}
                priorValid={priorStillValid(h, today)}
                today={today}
                delay={i * 60}
                onChange={(next) => update({ ...form, hazards: { ...form.hazards, [h.hazardId]: next } })}
              />
            ))
          )}
        </section>

        {d.workPlans.length > 0 && (
          <section aria-label="작업 보류" className="space-y-3">
            <SectionTitle>{held.length > 0 ? `작업 보류 ${held.length}` : "보류 해제"}</SectionTitle>
            <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white shadow-card">
              {d.workPlans.map((p) => (
                <li key={p.workPlanId} className="flex flex-wrap items-center gap-4 px-6 py-3">
                  <span className="w-24 shrink-0 text-sm tabular-nums text-slate-500">{formatDate(p.workDate)}</span>
                  <span className="min-w-0 flex-1 break-keep text-sm text-slate-900">{p.workName}</span>
                  <StatusBadge tone={workPlanStatusTone(p.status)}>
                    {p.status === "SUBMITTED" ? "재승인 대기" : WORK_PLAN_STATUS_LABEL[p.status]}
                  </StatusBadge>
                  <Link to={`/work-plan?planId=${p.workPlanId}`} className={buttonClassName("secondary", "sm")}>
                    작업 전 점검
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </PageLayout>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-1.5">
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-slate-800">{value}</dd>
    </div>
  );
}
