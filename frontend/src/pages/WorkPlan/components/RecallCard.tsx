// RecallCard.tsx — 회상 카드. 모델 문장과 무관하게, 시스템이 이미 아는 것을 카드로 먼저 보여준다.
// 진입 회상(useEntryEquipment)과 대화 중 ai.recall이 같은 RecallView를 그리며, 화면에는 항상 한 장만 뜬다.
import { Badge, RiskBadge } from "@/components/ui/Badge";
import cn from "@/lib/cn";
import { ACCIDENT_LABEL } from "@/types/domain";
import type { RecallView } from "@/types/timeline";
import { formatDate } from "@/utils/datetime";
import { toneColor } from "@/utils/statusColors";

interface Props {
  recall: RecallView;
  className?: string;
}

/** 톤은 미이행 조치 유무로만 정한다. 화면마다 다르게 판단하면 시연에서 흔들린다. */
export default function RecallCard({ recall, className }: Props) {
  const urgent = recall.unfinishedActions.length > 0;
  const c = toneColor(urgent ? "high" : "neutral");

  return (
    <div role="status" aria-live="polite"
      className={cn("relative overflow-hidden rounded-xl border bg-white px-5 py-4 shadow-card animate-rise-in", c.border, className)}>
      <span aria-hidden className={cn("absolute inset-y-0 left-0 w-1", c.solid)} />
      <p className={cn("text-xs font-bold tracking-wide", urgent ? "text-risk-high-text" : "text-slate-500")}>이 설비가 기억하는 것</p>
      <p className="mt-1 text-lg font-bold text-slate-900">{recall.headline.replace(/ · /g, ", ")}</p>

      <div className="mt-3 grid gap-x-8 gap-y-2 md:grid-cols-2">
        {recall.priorHazards.length > 0 && (
          <div className="space-y-1.5">
            {recall.priorHazards.map((hazard) => (
              <div key={hazard.hazardId} className="flex flex-wrap items-center gap-2 text-sm text-slate-700">
                {hazard.lastRiskLevel && <RiskBadge level={hazard.lastRiskLevel} />}
                {hazard.accidentType && <Badge>{ACCIDENT_LABEL[hazard.accidentType]}</Badge>}
                {hazard.missingControl && <span>{hazard.missingControl}</span>}
                {hazard.lastAssessedOn && <span className="text-xs text-slate-400">{formatDate(hazard.lastAssessedOn)} 평가</span>}
              </div>
            ))}
          </div>
        )}
        {recall.unfinishedActions.length > 0 && (
          <div className="space-y-1.5">
            {recall.unfinishedActions.map((action) => (
              <div key={action.actionId} className="flex flex-wrap items-center gap-2 text-sm text-slate-700">
                <span className="font-semibold text-slate-900">미이행</span>
                <span>{action.content}</span>
                {action.overdueDays !== null && action.overdueDays > 0 && (
                  <Badge variant="high">기한 {action.overdueDays}일 경과</Badge>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {recall.knownSlots.length > 0 && (
        <div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-slate-100 pt-3">
          <span className="mr-1 text-xs font-semibold text-slate-500">묻지 않고 채운 값</span>
          {recall.knownSlots.map((s) => (
            <span key={s} className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-xs text-slate-600">{s}</span>
          ))}
        </div>
      )}
    </div>
  );
}
