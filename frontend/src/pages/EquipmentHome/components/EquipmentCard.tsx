// EquipmentCard.tsx — 설비 한 대의 현재 상태 한 장. 왼쪽 띠가 등급, 숫자 셋, 마지막 기록, 동사 셋.
// 카드 전체가 설비 상세로 가는 링크이고(제목 링크를 카드 크기로 늘림), 동사 버튼은 그 위에 따로 선다.
import { Link, useNavigate } from "react-router-dom";

import { plainText } from "@/utils/plainText";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { ACCIDENT_LABEL } from "@/types/domain";
import type { EquipmentCard as EquipmentCardType } from "@/types/timeline";
import { SITE_TZ } from "@/utils/datetime";
import { riskColor, toneColor, type Tone } from "@/utils/statusColors";

interface Props {
  card: EquipmentCardType;
  index?: number;
}

function Count({ label, value, tone, note }: { label: string; value: number; tone: Tone; note?: string | null }) {
  const zero = value === 0;
  return (
    <div className="min-w-0">
      <p className={cn("text-xs font-semibold", zero ? "text-slate-400" : "text-slate-500")}>{label}</p>
      <p className={cn("mt-0.5 text-xl font-bold tabular-nums", zero ? "text-slate-300" : tone === "neutral" ? "text-slate-900" : toneColor(tone).text)}>{value}</p>
      {note && <p className="text-xs font-semibold text-risk-high-text">{note}</p>}
    </div>
  );
}

/** 오늘(KST) YYYY-MM-DD. 마지막 사건이 미래(조치 기한, 작업일)면 "최근"이 아니라 "예정"이다 */
function todayKst(): string {
  return new Date().toLocaleDateString("sv-SE", { timeZone: SITE_TZ });
}

/** 사실이 없는 설비는 같은 문장을 반복하지 않고 흐리게 "이상 없음"만 말한다 */
function lastLine(card: EquipmentCardType): { text: string; muted: boolean } {
  if (!card.currentRiskLevel && !card.lastEventOn) return { text: "평가 기록 없음, 최초 평가가 필요합니다", muted: false };
  if (card.emphasis === "NORMAL") return { text: "미이행 조치와 사고 이력 없음", muted: true };
  return { text: plainText(card.headline), muted: false };
}

export default function EquipmentCard({ card, index = 0 }: Props) {
  const navigate = useNavigate();
  const level = card.currentRiskLevel;
  const line = lastLine(card);
  const verb = (path: string) => () => navigate(`${path}?equipmentId=${card.id}`);
  const assessed = [card.currentRiskAxis ? ACCIDENT_LABEL[card.currentRiskAxis] : null, card.lastAssessedOn ? `${card.lastAssessedOn.slice(5)} 평가` : null]
    .filter(Boolean).join(", ");

  return (
    <article className="group relative flex flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card transition-colors hover:border-slate-300 animate-rise-in"
      style={{ animationDelay: `${index * 60}ms` }}>
      <span className={cn("absolute inset-y-0 left-0 w-1.5", level ? riskColor(level).solid : "bg-slate-200")} aria-hidden />
      <div className="flex items-start justify-between gap-3 pl-6 pr-5 pt-4">
        <div className="min-w-0">
          <h3 className="text-base font-semibold text-slate-900">
            <Link to={`/equipment/${card.id}`}
              className="after:absolute after:inset-0 focus-visible:outline-none focus-visible:after:ring-2 focus-visible:after:ring-inset focus-visible:after:ring-progress-border group-hover:underline">
              {card.name}
            </Link>
          </h3>
          <p className="mt-0.5 truncate text-xs text-slate-500">{plainText(card.locationTag) || "위치 미등록"}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2.5">
          <div className="text-right">
            <p className="text-xs font-semibold text-slate-500">현재 등급</p>
            <p className="text-xs text-slate-400">{assessed || "미평가"}</p>
          </div>
          {level ? <RiskGradeMark level={level} /> : (
            <span className="grid h-10 w-10 place-items-center rounded-lg border border-dashed border-slate-300 text-xs text-slate-400">없음</span>
          )}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-3 gap-3 pl-6 pr-5">
        <Count label="미이행 조치" value={card.unfinishedActionCount} tone="pending"
          note={card.overdueActionCount > 0 ? `기한 경과 ${card.overdueActionCount}` : null} />
        <Count label="예정 작업" value={card.upcomingWorkPlanCount} tone="neutral" />
        <Count label="사고" value={card.incidentCount} tone="high" />
      </div>

      <div className="mt-3 flex flex-1 items-end justify-between gap-3 pb-3 pl-6 pr-5">
        <p className={cn("line-clamp-2 text-sm", line.muted ? "text-slate-400" : "text-slate-700")}>{line.text}</p>
        {card.lastEventOn && (
          <p className="shrink-0 text-xs tabular-nums text-slate-400">
            {card.lastEventOn > todayKst() ? "예정" : "최근"} {card.lastEventOn.slice(5)}
          </p>
        )}
      </div>

      <div className="relative z-10 grid grid-cols-3 divide-x divide-slate-100 border-t border-slate-100">
        {[["작업 신고", "/work-plan"], ["사진 점검", "/vision"], ["사고 신고", "/incident"]].map(([label, path]) => (
          <button key={path} type="button" onClick={verb(path)}
            className="bg-white py-2.5 text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-progress-border">
            {label}
          </button>
        ))}
      </div>
    </article>
  );
}
