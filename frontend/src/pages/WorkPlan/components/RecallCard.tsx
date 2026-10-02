// RecallCard.tsx — 설비 대장에서 불러온 이 설비의 기록. 대화가 시작되면 한 줄 막대로 접힌다.
// 진입 회상(useEntryEquipment)과 대화 중 ai.recall이 같은 RecallView를 그리며, 화면에는 항상 한 장만 뜬다.
import { Badge, RiskBadge } from "@/components/ui/Badge";
import cn from "@/lib/cn";
import type { RecallView } from "@/types/timeline";
import { formatShortDate } from "@/pages/WorkPlan/utils/format";
import { toneColor } from "@/utils/statusColors";

interface Props {
  recall: RecallView;
  /** 대화가 시작되면 한 줄로 접는다 */
  collapsed?: boolean;
  className?: string;
}

/** 최근 평가 등급, 미이행 조치 수, 사고 수. 펼침과 접힘이 같은 칩을 쓴다 */
function Chips({ recall }: { recall: RecallView }) {
  // 설비 현황 카드와 같은 기준: 위험요인별 최근 등급 중 가장 높은 것
  const rank = { HIGH: 3, MEDIUM: 2, LOW: 1 } as const;
  const latest = recall.priorHazards
    .filter((h) => h.lastRiskLevel !== null)
    .reduce<(typeof recall.priorHazards)[number] | undefined>(
      (top, h) => (!top || rank[h.lastRiskLevel!] > rank[top.lastRiskLevel!] ? h : top), undefined);
  const unfinished = recall.unfinishedActions.length;
  const incidents = recall.priorIncidents.length;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {latest?.lastRiskLevel && (
        <span className="inline-flex items-center gap-1 text-xs font-semibold text-slate-500">
          현재 등급 <RiskBadge level={latest.lastRiskLevel} />
        </span>
      )}
      {unfinished > 0 && <Badge variant="high">미이행 {unfinished}</Badge>}
      {incidents > 0 && <Badge variant="high">사고 {incidents}</Badge>}
    </div>
  );
}

/** 톤은 미이행 조치 유무로만 정한다. 화면마다 다르게 판단하면 시연에서 흔들린다. */
export default function RecallCard({ recall, collapsed = false, className }: Props) {
  const urgent = recall.unfinishedActions.length > 0;
  const c = toneColor(urgent ? "high" : "neutral");

  if (collapsed) {
    return (
      <div role="status" aria-live="polite"
        className={cn("relative flex flex-wrap items-center gap-x-3 gap-y-1 overflow-hidden rounded-lg border bg-white py-2 pl-4 pr-3 shadow-card animate-fade-in", c.border, className)}>
        <span aria-hidden className={cn("absolute inset-y-0 left-0 w-1", c.solid)} />
        <span className="text-sm font-bold text-slate-900">{recall.equipmentName}</span>
        {recall.locationTag && <span className="text-sm text-slate-500">{recall.locationTag}</span>}
        <Chips recall={recall} />
      </div>
    );
  }

  const hazards = recall.priorHazards.filter((h) => h.missingControl);
  return (
    <div role="status" aria-live="polite"
      className={cn("relative overflow-hidden rounded-xl border bg-white px-5 py-4 shadow-card animate-rise-in", c.border, className)}>
      <span aria-hidden className={cn("absolute inset-y-0 left-0 w-1", c.solid)} />
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <p className="text-lg font-bold text-slate-900">{recall.equipmentName}</p>
        {recall.locationTag && <p className="text-sm text-slate-500">{recall.locationTag}</p>}
        <div className="ml-auto"><Chips recall={recall} /></div>
      </div>

      {(hazards.length > 0 || recall.unfinishedActions.length > 0) && (
        <ul className="mt-3 space-y-1.5 border-t border-slate-100 pt-3 text-sm text-slate-700">
          {hazards.map((h) => (
            <li key={h.hazardId} className="flex flex-wrap items-center gap-2">
              <span className="w-12 shrink-0 text-xs font-semibold text-slate-500">지적</span>
              <span>{h.missingControl}</span>
              {h.lastAssessedOn && <span className="text-xs tabular-nums text-slate-400">{formatShortDate(h.lastAssessedOn)}</span>}
            </li>
          ))}
          {recall.unfinishedActions.map((a) => (
            <li key={a.actionId} className="flex flex-wrap items-center gap-2">
              <span className="w-12 shrink-0 text-xs font-semibold text-risk-high-text">미이행</span>
              <span>{a.content}</span>
              {a.overdueDays !== null && a.overdueDays > 0 && <Badge variant="high">{a.overdueDays}일 경과</Badge>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
