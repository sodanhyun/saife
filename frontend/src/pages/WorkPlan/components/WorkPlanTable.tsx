import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { WorkPlanListItem } from "@/types/workPlan";
import { formatDate } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

interface Props { plans: WorkPlanListItem[]; onOpen: (id: number) => void }

export default function WorkPlanTable({ plans, onOpen }: Props) {
  const columns: Column<WorkPlanListItem>[] = [
    { key: "workName", header: "작업명", render: (p) => p.workName },
    { key: "workDate", header: "일자", width: "w-28", render: (p) => <span className="tabular-nums">{formatDate(p.workDate)}</span> },
    { key: "equipment", header: "설비", render: (p) => p.equipmentName ?? "-" },
    { key: "status", header: "상태", width: "w-28", render: (p) => <StatusBadge tone={workPlanStatusTone(p.status)}>{WORK_PLAN_STATUS_LABEL[p.status]}</StatusBadge> },
    { key: "briefing", header: "TBM", width: "w-24", render: (p) => p.briefingAcknowledged ? <span className="text-risk-low-text">실시</span> : <span className="text-slate-400">미실시</span> },
    { key: "open", header: "", width: "w-20", align: "right", render: (p) => <Button variant="subtle" size="sm" onClick={() => onOpen(p.id)}>열기</Button> },
  ];
  return <DataTable columns={columns} data={plans} rowKey={(p) => p.id} onRowClick={(p) => onOpen(p.id)} emptyMessage="점검 기록이 없습니다" />;
}
