// EquipmentCard.tsx — 설비 홈의 카드 한 장. 클릭하면 설비 상세로, 동사 버튼 3개가 각 화면으로 진입 컨텍스트를 들고 간다.
import { useNavigate } from "react-router-dom";

import { RiskBadge, StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import KpiCell from "@/components/ui/KpiCell";
import cn from "@/lib/cn";
import type { EquipmentCard as EquipmentCardType } from "@/types/timeline";
import { formatDate } from "@/utils/datetime";
import { emphasisTone, toneColor } from "@/utils/statusColors";

interface Props {
  card: EquipmentCardType;
}

const EMPHASIS_LABEL: Record<EquipmentCardType["emphasis"], string> = {
  CRITICAL: "긴급",
  WARNING: "주의",
  NORMAL: "정상",
};

/** 카드 테두리·배지 톤은 emphasis로만 정한다 — 화면마다 다르게 판단하면 시연에서 색이 흔들린다. */
export default function EquipmentCard({ card }: Props) {
  const navigate = useNavigate();
  const tone = emphasisTone(card.emphasis);
  const toneClasses = toneColor(tone);

  const goDetail = () => navigate(`/equipment/${card.id}`);
  const goVerb = (path: string) => (e: React.MouseEvent) => {
    e.stopPropagation();
    navigate(`${path}?equipmentId=${card.id}`);
  };

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={goDetail}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          goDetail();
        }
      }}
      className={cn(
        "cursor-pointer rounded-lg border bg-white p-4 shadow-card transition-colors hover:bg-slate-50",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border",
        toneClasses.border,
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-base font-semibold text-slate-900">{card.name}</h3>
          <p className="mt-0.5 text-xs text-slate-500">{card.locationTag ?? "-"}</p>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {card.currentRiskLevel && <RiskBadge level={card.currentRiskLevel} />}
          {card.emphasis !== "NORMAL" && <StatusBadge tone={tone}>{EMPHASIS_LABEL[card.emphasis]}</StatusBadge>}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-3 gap-2">
        <KpiCell
          label="미이행 조치"
          value={card.unfinishedActionCount}
          subText={card.overdueActionCount > 0 ? `기한 초과 ${card.overdueActionCount}건` : undefined}
        />
        <KpiCell label="예정 작업" value={card.upcomingWorkPlanCount} />
        <KpiCell label="사고" value={card.incidentCount} />
      </div>

      <p className="mt-3 text-stage">{card.headline}</p>
      <p className="mt-1 text-xs text-slate-400">마지막 사건 {formatDate(card.lastEventOn)}</p>

      <div className="mt-3 flex flex-wrap gap-2">
        <Button size="sm" onClick={goVerb("/work-plan")}>작업 신고</Button>
        <Button size="sm" variant="secondary" onClick={goVerb("/vision")}>사진 점검</Button>
        <Button size="sm" variant="secondary" onClick={goVerb("/incident")}>사고 신고</Button>
      </div>
    </div>
  );
}
