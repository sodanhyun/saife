import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { WorkPlanListItem } from "@/types/workPlan";
import { formatDate } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

interface Props {
  plans: WorkPlanListItem[];
  onOpen: (id: number) => void;
  emptyMessage?: string;
}

const DOC_LABEL: Record<WorkPlanListItem["documentType"], string> = {
  TBM_CHECKLIST: "점검표",
  WORK_PLAN: "작업계획서",
};

export default function WorkPlanTable({ plans, onOpen, emptyMessage = "점검 기록 없음" }: Props) {
  const columns: Column<WorkPlanListItem>[] = [
    { key: "workName", header: "작업명", render: (p) => p.workName },
    { key: "doc", header: "문서", width: "w-28", render: (p) => <span className="text-slate-600">{DOC_LABEL[p.documentType] ?? "점검표"}</span> },
    { key: "workDate", header: "작업일", width: "w-28", render: (p) => <span className="whitespace-nowrap tabular-nums">{formatDate(p.workDate)}</span> },
    { key: "equipment", header: "설비", render: (p) => p.equipmentName ?? "-" },
    { key: "status", header: "상태", width: "w-28", render: (p) => <StatusBadge tone={workPlanStatusTone(p.status)}>{WORK_PLAN_STATUS_LABEL[p.status]}</StatusBadge> },
    { key: "briefing", header: "TBM", width: "w-24", render: (p) => p.briefingAcknowledged ? <span className="text-risk-low-text">실시</span> : <span className="text-slate-400">미실시</span> },
    { key: "open", header: "", width: "w-20", align: "right", render: (p) => <Button variant="subtle" size="sm" onClick={() => onOpen(p.id)}>열기</Button> },
  ];
  return <DataTable columns={columns} data={plans} rowKey={(p) => p.id} onRowClick={(p) => onOpen(p.id)} emptyMessage={emptyMessage} />;
}
