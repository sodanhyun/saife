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

/** 톤은 미이행 조치 유무로만 정한다 — 화면마다 다르게 판단하면 시연에서 흔들린다. */
export default function RecallCard({ recall, className }: Props) {
  const tone = recall.unfinishedActions.length > 0 ? "high" : "neutral";
  const c = toneColor(tone);

  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        "rounded-lg border p-4 transition-colors duration-200 motion-reduce:transition-none",
        c.bg,
        c.border,
        className,
      )}
    >
      <p className="text-stage font-semibold text-slate-900">{recall.headline}</p>

      {recall.priorHazards.length > 0 && (
        <div className="mt-3 space-y-1.5">
          {recall.priorHazards.map((hazard) => (
            <div key={hazard.hazardId} className="flex flex-wrap items-center gap-2 text-sm text-slate-700">
              {hazard.accidentType && <Badge>{ACCIDENT_LABEL[hazard.accidentType]}</Badge>}
              {hazard.missingControl && <span>{hazard.missingControl}</span>}
              {hazard.lastRiskLevel && <RiskBadge level={hazard.lastRiskLevel} />}
              {hazard.lastAssessedOn && <span className="text-xs text-slate-400">{formatDate(hazard.lastAssessedOn)}</span>}
            </div>
          ))}
        </div>
      )}

      {recall.unfinishedActions.length > 0 && (
        <div className="mt-3 space-y-1.5">
          {recall.unfinishedActions.map((action) => (
            <div key={action.actionId} className="flex flex-wrap items-center gap-2 text-sm text-slate-700">
              <span>{action.content}</span>
              {action.dueDate && <span className="text-xs text-slate-400">기한 {formatDate(action.dueDate)}</span>}
              {action.overdueDays !== null && action.overdueDays > 0 && (
                <Badge variant="high">{action.overdueDays}일 경과</Badge>
              )}
            </div>
          ))}
        </div>
      )}

      {recall.knownSlots.length > 0 && (
        <p className={cn("mt-3 inline-block rounded border px-2 py-1 text-xs font-semibold", c.chip)}>
          이미 알고 있어 묻지 않음: {recall.knownSlots.join(" · ")}
        </p>
      )}
    </div>
  );
}
