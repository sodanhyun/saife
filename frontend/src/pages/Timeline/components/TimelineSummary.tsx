import { RiskBadge } from "@/components/ui/Badge";
import Card from "@/components/ui/Card";
import KpiCell from "@/components/ui/KpiCell";
import type { EquipmentTimeline } from "@/types/timeline";

/** 헤드라인 한 줄만 읽혀도 논지가 전달돼야 한다(프로젝터). */
export default function TimelineSummary({ timeline }: { timeline: EquipmentTimeline }) {
  const { equipment, summary } = timeline;
  return (
    <Card title={<span className="flex flex-wrap items-center gap-2">{equipment.name}{summary.currentRiskLevel && <RiskBadge level={summary.currentRiskLevel} />}</span>}
      description={[equipment.locationTag, equipment.processName].filter(Boolean).join(" · ")}>
      <p className="text-stage">{summary.headline}</p>
      <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-5">
        <KpiCell label="위험성평가" value={summary.assessmentCount} />
        <KpiCell label="작업계획서" value={summary.workPlanCount} />
        <KpiCell label="사고" value={summary.incidentCount} tone={summary.incidentCount > 0 ? "high" : undefined} />
        <KpiCell label="미이행 조치" value={summary.unfinishedActionCount} tone={summary.unfinishedActionCount > 0 ? "pending" : undefined} />
        <KpiCell label="기한 경과" value={summary.overdueActionCount} tone={summary.overdueActionCount > 0 ? "high" : undefined} subText={summary.overdueActionCount > 0 ? "즉시 조치" : undefined} />
      </div>
    </Card>
  );
}
