// EquipmentCard.tsx — 설비 한 대의 현재 상태 한 장. 왼쪽 띠가 등급, 숫자 셋, 상태 칩 하나.
// 카드 전체가 설비 상세로 가는 링크이고(제목 링크를 카드 크기로 늘림), 바로가기 셋은 hover 때만 아래에서 올라온다.
import { Link, useNavigate } from "react-router-dom";

import { StatusBadge } from "@/components/ui/Badge";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { cardChip } from "@/pages/EquipmentHome/utils/todayModel";
import { ACCIDENT_LABEL } from "@/types/domain";
import type { EquipmentCard as EquipmentCardType } from "@/types/timeline";
import { SITE_TZ } from "@/utils/datetime";
import { plainText } from "@/utils/plainText";
import { riskColor, toneColor, type Tone } from "@/utils/statusColors";

interface Props {
  card: EquipmentCardType;
  index?: number;
}

const CARD_VERBS: [string, string][] = [["작업 전 점검", "/work-plan"], ["순회점검", "/vision"], ["사고 보고", "/incident"]];

function Count({ label, value, tone }: { label: string; value: number; tone: Tone }) {
  const zero = value === 0;
  return (
    <div className="min-w-0">
      <p className={cn("text-xs font-semibold", zero ? "text-slate-400" : "text-slate-500")}>{label}</p>
      <p className={cn("mt-0.5 text-xl font-bold tabular-nums", zero ? "text-slate-300" : tone === "neutral" ? "text-slate-900" : toneColor(tone).text)}>{value}</p>
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
  // 발생형태와 평가일은 두 줄로 쌓는다. 한 줄로 두면 긴 설비명("기계식 프레스 1호")이 두 줄로 꺾인다
  const axisLabel = card.currentRiskAxis ? ACCIDENT_LABEL[card.currentRiskAxis] : null;
  const assessedOn = card.lastAssessedOn ? card.lastAssessedOn.slice(5) : null;
  const assessed = axisLabel || assessedOn;

  return (
    <article className="group relative flex flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card transition-colors hover:border-slate-300 animate-rise-in"
      style={{ animationDelay: `${index * 60}ms` }}>
      <span className={cn("absolute inset-y-0 left-0 w-1.5", level ? riskColor(level).solid : "bg-slate-200")} aria-hidden />
      <div className="flex items-start justify-between gap-3 pl-6 pr-5 pt-4">
        <div className="min-w-0">
          <h3 className="truncate text-base font-semibold text-slate-900" title={card.name}>
            <Link to={`/equipment/${card.id}`}
              className="after:absolute after:inset-0 focus-visible:outline-none focus-visible:after:ring-2 focus-visible:after:ring-inset focus-visible:after:ring-progress-border group-hover:underline">
              {card.name}
            </Link>
          </h3>
          <p className="mt-0.5 truncate text-xs text-slate-500">{plainText(card.locationTag) || "위치 미등록"}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2.5">
          {assessed && (
            <p className="text-right text-xs leading-tight text-slate-400">
              {axisLabel && <span className="block whitespace-nowrap">{axisLabel}</span>}
              {assessedOn && <span className="block tabular-nums">{assessedOn}</span>}
            </p>
          )}
          {level ? <RiskGradeMark level={level} /> : (
            <span className="grid h-10 w-10 place-items-center rounded-lg border border-dashed border-slate-300 text-xs text-slate-400">미평가</span>
          )}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-3 gap-3 pl-6 pr-5">
        <Count label="미이행 조치" value={card.unfinishedActionCount} tone="pending" />
        <Count label="예정 작업" value={card.upcomingWorkPlanCount} tone="neutral" />
        <Count label="사고" value={card.incidentCount} tone="high" />
      </div>

      <div className="mt-3 flex h-11 items-center justify-between gap-3 border-t border-slate-100 pl-6 pr-5">
        {chip ? <StatusBadge tone={chip.tone}>{chip.text}</StatusBadge> : <span />}
        {card.lastEventOn && (
          <p className="shrink-0 text-xs tabular-nums text-slate-400">
            {card.lastEventOn > todayKst() ? "예정" : "최근"} {card.lastEventOn.slice(5)}
          </p>
        )}
      </div>

      {/* 바로가기. 평소에는 칩 줄 아래에 숨어 있다가 hover나 키보드 초점에서 올라온다 */}
      <div className="absolute inset-x-0 bottom-0 z-10 grid h-11 translate-y-full grid-cols-3 divide-x divide-slate-100 border-t border-slate-200 bg-white transition-transform duration-150 group-hover:translate-y-0 group-focus-within:translate-y-0">
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
