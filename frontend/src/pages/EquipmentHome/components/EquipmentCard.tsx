// EquipmentCard.tsx — 설비 한 대의 현재 상태 한 장. 왼쪽 띠가 등급, 숫자 셋, 상태 칩 하나.
// 카드 전체가 설비 상세로 가는 링크이고(제목 링크를 카드 크기로 늘림), 바로가기 셋은 카드 맨 아래 자기 줄에
// 있다가 hover나 키보드 초점에서 드러난다. 칩과 날짜 줄을 가리지 않는다.
import { Link, useNavigate } from "react-router-dom";

import { StatusBadge } from "@/components/ui/Badge";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { assessedLabel, cardChip } from "@/pages/EquipmentHome/utils/todayModel";
import type { EquipmentCard as EquipmentCardType } from "@/types/timeline";
import { SITE_TZ } from "@/utils/datetime";
import { plainText } from "@/utils/plainText";
import { riskColor, toneColor, type Tone } from "@/utils/statusColors";

interface Props {
  card: EquipmentCardType;
  index?: number;
}

const CARD_VERBS: [string, string][] = [["작업 전 점검", "/work-plan"], ["순회점검", "/vision"], ["사고 보고", "/incident"]];

function Count({ label, value, tone, extra }: { label: string; value: number; tone: Tone; extra?: string | null }) {
  const zero = value === 0;
  return (
    <div className="min-w-0">
      <p className={cn("text-xs font-semibold", zero ? "text-slate-400" : "text-slate-500")}>{label}</p>
      <p className="mt-0.5 flex items-baseline gap-1.5">
        <span className={cn("text-xl font-bold tabular-nums", zero ? "text-slate-300" : tone === "neutral" ? "text-slate-900" : toneColor(tone).text)}>{value}</span>
        {extra && <span className="whitespace-nowrap text-xs font-semibold text-pending-text">{extra}</span>}
      </p>
    </div>
  );
}

/** 오늘(KST) YYYY-MM-DD. 마지막 사건이 미래(조치 기한, 작업일)면 "최근"이 아니라 "예정"이다 */
function todayKst(): string {
  return new Date().toLocaleDateString("sv-SE", { timeZone: SITE_TZ });
}

export default function EquipmentCard({ card, index = 0 }: Props) {
  const navigate = useNavigate();
  const level = card.currentRiskLevel;
  const chip = cardChip(card);
  const verb = (path: string) => () => navigate(`${path}?equipmentId=${card.id}`);
  const assessed = assessedLabel(card);
  const nearMiss = card.nearMissCount ?? 0;

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

      <div className="mt-3 grid grid-cols-3 gap-3 pl-6 pr-5">
        <Count label="미이행 조치" value={card.unfinishedActionCount} tone="pending" />
        <Count label="예정 작업" value={card.upcomingWorkPlanCount} tone="neutral" />
        <Count label="사고" value={card.incidentCount} tone="high" extra={nearMiss > 0 ? `아차사고 ${nearMiss}` : null} />
      </div>

      <div className="mt-3 flex h-11 items-center justify-between gap-3 border-t border-slate-100 pl-6 pr-5">
        {chip ? <StatusBadge tone={chip.tone}>{chip.text}</StatusBadge> : <span />}
        {card.lastEventOn && (
          <p className="shrink-0 text-xs tabular-nums text-slate-400">
            {card.lastEventOn > todayKst() ? "예정" : "최근"} {card.lastEventOn.slice(5)}
          </p>
        )}
      </div>

      {/* 바로가기. 자기 줄을 차지하고(칩과 날짜를 덮지 않는다) 평소에는 흐리게 숨어 있다가 hover나 키보드 초점에서 드러난다 */}
      <div className="relative z-10 grid h-10 grid-cols-3 divide-x divide-slate-100 border-t border-slate-100 opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100">
        {CARD_VERBS.map(([label, path]) => (
          <button key={path} type="button" onClick={verb(path)}
            className="text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-progress-border">
            {label}
          </button>
        ))}
      </div>
    </article>
  );
}
