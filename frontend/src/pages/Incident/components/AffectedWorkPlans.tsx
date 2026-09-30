// AffectedWorkPlans.tsx — 사고 연쇄 4단계(WORK_PLAN)가 가리키는 표.
// 같은 설비의 진행 중 작업계획서에 경고가 붙었음을 보여준다. 0건이면 렌더하지 않는다
// (스텝 4의 detail "해당 없음"이 이미 그 사실을 말하므로 여기 빈 표를 또 두지 않는다).
import { StatusBadge } from "@/components/ui/Badge";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { AffectedWorkPlan } from "@/types/incident";
import { formatDate } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

export default function AffectedWorkPlans({ workPlans }: { workPlans: AffectedWorkPlan[] }) {
  if (workPlans.length === 0) return null;

  const columns: Column<AffectedWorkPlan>[] = [
    { key: "workName", header: "작업명", render: (p) => p.workName },
    {
      key: "workDate",
      header: "작업일",
      width: "w-28",
      render: (p) => <span className="tabular-nums">{formatDate(p.workDate)}</span>,
    },
    {
      key: "status",
      header: "상태",
      width: "w-24",
      render: (p) => <StatusBadge tone={workPlanStatusTone(p.status)}>{WORK_PLAN_STATUS_LABEL[p.status]}</StatusBadge>,
    },
    { key: "warning", header: "경고", render: (p) => <span className="text-risk-high-text">{p.warning}</span> },
  ];

  return (
    <DataTable
      columns={columns}
      data={workPlans}
      rowKey={(p) => p.workPlanId}
      emptyMessage="영향받는 작업계획서가 없습니다"
    />
  );
}
