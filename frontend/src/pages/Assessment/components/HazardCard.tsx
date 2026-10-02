// HazardCard.tsx — 수시평가 위험요인 한 건: 등급과 근거, 허용 가능 여부, 허용 불가면 개선대책(무엇을, 담당, 기한).
import DateInput from "@/components/ui/DateInput";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import SegmentedControl from "@/components/ui/SegmentedControl";
import cn from "@/lib/cn";
import type { HazardDraft, HazardErrors } from "@/pages/Assessment/utils/followUpForm";
import { ACCIDENT_LABEL, RISK_LABEL } from "@/types/domain";
import type { FollowUpAction, FollowUpHazard } from "@/types/incident";
import { formatDate } from "@/utils/datetime";
import { plainText } from "@/utils/plainText";

interface Props {
  h: FollowUpHazard;
  draft: HazardDraft;
  onChange: (next: HazardDraft) => void;
  errors?: HazardErrors;
  /** 확정된 평가는 읽기 전용 */
  readOnly: boolean;
  /** 기한이 남은 기존 대책이 있어 새 대책이 선택인가 */
  priorValid: boolean;
  today: string;
  delay?: number;
}

export default function HazardCard({ h, draft, onChange, errors = {}, readOnly, priorValid, today, delay = 0 }: Props) {
  const title = h.missingControl ?? h.description ?? "위험요인";
  const sub = [
    h.accidentType ? ACCIDENT_LABEL[h.accidentType] : null,
    h.before ? `사고 전 ${RISK_LABEL[h.before]}` : "신규",
  ].filter(Boolean) as string[];

  return (
    <article
      aria-label={title}
      className={cn(
        "rounded-xl border bg-white px-5 py-4 shadow-card animate-rise-in",
        h.sameAxis ? "border-risk-high-border" : "border-slate-200",
      )}
      style={{ animationDelay: `${delay}ms` }}
    >
      <div className="flex flex-wrap items-start gap-4">
        <RiskGradeMark level={h.riskLevel} />
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold text-slate-500">{sub.join(", ")}</p>
          <p className="mt-0.5 text-stage font-semibold text-slate-900">{plainText(title)}</p>
          {h.ruleTrace && (
            <p className="mt-2 rounded-md bg-panel px-2.5 py-1.5 text-sm text-slate-700">{plainText(h.ruleTrace)}</p>
          )}
        </div>
        <div className="shrink-0">
          {readOnly ? (
            <span className={cn("text-sm font-semibold", draft.acceptable ? "text-slate-700" : "text-risk-high-text")}>
              {draft.acceptable ? "허용 가능" : "허용 불가"}
            </span>
          ) : (
            <SegmentedControl<boolean>
              ariaLabel={`${title} 허용 가능 여부`}
              size="sm"
              value={draft.acceptable}
              onChange={(v) => onChange({ ...draft, acceptable: v })}
              options={[
                { value: true, label: "허용 가능" },
                { value: false, label: "허용 불가" },
              ]}
            />
          )}
        </div>
      </div>

      {!draft.acceptable && (
        <div className="mt-4 border-t border-slate-100 pt-4">
          {h.action ? (
            <ActionLine label="개선대책" action={h.action} today={today} />
          ) : readOnly ? (
            <p className="text-sm text-slate-500">(미수립)</p>
          ) : (
            <div className="grid gap-x-3 gap-y-2 md:grid-cols-12">
              <FormField label="개선대책" required={!priorValid} error={errors.content} className="md:col-span-6">
                <Input
                  value={draft.content}
                  error={Boolean(errors.content)}
                  onChange={(e) => onChange({ ...draft, content: e.target.value })}
                />
              </FormField>
              <FormField label="담당" required={!priorValid} error={errors.owner} className="md:col-span-3">
                <Input
                  placeholder="직책 이름"
                  value={draft.owner}
                  error={Boolean(errors.owner)}
                  onChange={(e) => onChange({ ...draft, owner: e.target.value })}
                />
              </FormField>
              <FormField label="기한" required={!priorValid} error={errors.dueDate} className="md:col-span-3">
                <DateInput
                  type="date"
                  aria-label={`${title} 개선대책 기한`}
                  value={draft.dueDate}
                  onChange={(v) => onChange({ ...draft, dueDate: v })}
                />
              </FormField>
            </div>
          )}
          {h.priorAction && <ActionLine label="기존 대책" action={h.priorAction} today={today} muted className="mt-3" />}
        </div>
      )}
    </article>
  );
}

/** 대책 한 줄: 내용, 담당, 기한(지났으면 경과일), 이행 상태 */
function ActionLine({
  label,
  action,
  today,
  muted = false,
  className,
}: {
  label: string;
  action: FollowUpAction;
  today: string;
  muted?: boolean;
  className?: string;
}) {
  const done = action.status === "DONE";
  const late = !done && action.dueDate !== null && action.dueDate < today;
  const lateDays = late && action.dueDate ? Math.round((Date.parse(today) - Date.parse(action.dueDate)) / 86_400_000) : 0;
  return (
    <div className={cn("flex flex-wrap items-baseline gap-x-4 gap-y-1 text-sm", className)}>
      <span className="text-xs font-semibold text-slate-500">{label}</span>
      <span className={cn("min-w-0 flex-1", muted ? "text-slate-600" : "font-semibold text-slate-900")}>{action.content}</span>
      {action.owner && <span className="text-slate-600">{action.owner}</span>}
      {action.dueDate && (
        <span className={cn("tabular-nums", late ? "font-semibold text-risk-high-text" : "text-slate-600")}>
          기한 {formatDate(action.dueDate)}
          {late ? `, ${lateDays}일 경과` : ""}
        </span>
      )}
      {done && <span className="text-slate-600">완료 {formatDate(action.completedOn)}</span>}
    </div>
  );
}
