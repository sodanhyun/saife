// ReportDutyCard.tsx — 사고 연쇄 3단계 상세. 산업재해조사표 제출 기한과 판정 근거.
import { formUrl } from "@/api/formUrl";
import { StatusBadge } from "@/components/ui/Badge";
import LinkButton from "@/components/ui/LinkButton";
import cn from "@/lib/cn";
import { DetailCard } from "@/pages/Incident/components/DetailCard";
import { plain } from "@/pages/Incident/utils/premonition";
import type { ReportDuty } from "@/types/incident";
import { dDayLabel, formatDate } from "@/utils/datetime";
import { reportDutyTone, toneColor } from "@/utils/statusColors";

export default function ReportDutyCard({ duty, incidentId, id }: { duty: ReportDuty; incidentId: number; id?: string }) {
  const tone = reportDutyTone(duty.status);
  const c = toneColor(tone);
  return (
    <DetailCard
      id={id}
      label="산업재해조사표 기한"
      title={duty.dueDate ? `${formatDate(duty.dueDate)}까지 제출` : duty.statusLabel}
      actions={<LinkButton href={formUrl.incident(incidentId)} external>산업재해조사표 서식</LinkButton>}
    >
      <div className="flex items-end gap-3">
        <span className={`text-display ${cn("tabular-nums", tone === "neutral" ? "text-slate-900" : c.text)}`}>
          {duty.dueDate ? dDayLabel(duty.daysRemaining) : "의무 없음"}
        </span>
        <StatusBadge tone={tone} className="mb-1.5">{duty.statusLabel}</StatusBadge>
      </div>
      <p className="mt-3 rounded-md bg-panel px-3 py-2 text-xs leading-relaxed text-slate-600">{plain(duty.basis)}</p>
    </DetailCard>
  );
}
