// EquipmentCard.tsx — 설비 한 대의 현재 상태 한 장. 왼쪽 띠와 표식이 등급, 상태 칩 하나, 마지막 기록 날짜.
// 카드 전체가 설비 상세로 가는 링크다(제목 링크를 카드 크기로 늘림). 작업은 설비 상세의 버튼에서 시작한다.
import { Link } from "react-router-dom";

import { StatusBadge } from "@/components/ui/Badge";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { assessedLabel, cardChip } from "@/pages/EquipmentHome/utils/todayModel";
import type { EquipmentCard as EquipmentCardType } from "@/types/timeline";
import { SITE_TZ } from "@/utils/datetime";
import { plainText } from "@/utils/plainText";
import { riskColor } from "@/utils/statusColors";

interface Props {
  card: EquipmentCardType;
  index?: number;
}

/** 오늘(KST) YYYY-MM-DD. 마지막 사건이 미래(조치 기한, 작업일)면 "최근"이 아니라 "예정"이다 */
function todayKst(): string {
  return new Date().toLocaleDateString("sv-SE", { timeZone: SITE_TZ });
}

export default function EquipmentCard({ card, index = 0 }: Props) {
  const level = card.currentRiskLevel;
  const chip = cardChip(card);
  const assessed = assessedLabel(card);

  return (
    <article className="group relative flex flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card transition-colors hover:border-slate-300 animate-rise-in"
      style={{ animationDelay: `${index * 60}ms` }}>
      <span className={cn("absolute inset-y-0 left-0 w-1.5", level ? riskColor(level).solid : "bg-slate-200")} aria-hidden />
      <div className="flex items-start justify-between gap-3 pl-6 pr-5 pt-4">
        <h3 className="min-w-0 break-keep text-base font-semibold text-slate-900" title={card.name}>
          <Link to={`/equipment/${card.id}`}
            className="after:absolute after:inset-0 focus-visible:outline-none focus-visible:after:ring-2 focus-visible:after:ring-inset focus-visible:after:ring-progress-border group-hover:underline">
            {card.name}
          </Link>
        </h3>
        {level ? <RiskGradeMark level={level} /> : (
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-lg border border-dashed border-slate-300 text-xs text-slate-400">미평가</span>
        )}
      </div>
      <div className="flex items-baseline justify-between gap-3 pl-6 pr-5">
        <p className="min-w-0 truncate text-xs text-slate-500">{plainText(card.locationTag) || "위치 미등록"}</p>
        {assessed && <p className="shrink-0 whitespace-nowrap text-xs tabular-nums text-slate-400">{assessed}</p>}
      </div>

      <div className="mt-3 flex h-11 items-center justify-between gap-3 border-t border-slate-100 pl-6 pr-5">
        {chip ? <StatusBadge tone={chip.tone}>{chip.text}</StatusBadge> : <span />}
        {card.lastEventOn && (
          <p className="shrink-0 text-xs tabular-nums text-slate-400">
            {card.lastEventOn > todayKst() ? "예정" : "최근"} {card.lastEventOn.slice(5)}
          </p>
        )}
      </div>
    </article>
  );
}
