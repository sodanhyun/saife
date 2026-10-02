import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { WorkPlanListItem } from "@/types/workPlan";
import { formatDate, nowLocalInput } from "@/utils/datetime";
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

/** TBM은 승인 후 작업 당일. 실시, 승인 전이거나 작업일 전이면 -, 승인 후 작업일이 됐는데 기록이 없으면 미실시 */
function tbmCell(p: WorkPlanListItem, today: string) {
  if (p.briefingAcknowledged) return <span className="text-risk-low-text">실시</span>;
  const approved = p.status === "APPROVED" || p.status === "CONDITIONAL" || p.status === "CLOSED";
  if (!approved || p.workDate > today) return <span className="text-slate-400">-</span>;
  return <span className="text-risk-medium-text">미실시</span>;
}

export default function WorkPlanTable({ plans, onOpen, emptyMessage = "점검 기록 없음" }: Props) {
  const today = nowLocalInput().slice(0, 10);
  const columns: Column<WorkPlanListItem>[] = [
    { key: "workName", header: "작업명", render: (p) => p.workName },
    { key: "doc", header: "문서", width: "w-28", render: (p) => <span className="text-slate-600">{DOC_LABEL[p.documentType] ?? "점검표"}</span> },
    { key: "workDate", header: "작업일", width: "w-28", render: (p) => <span className="whitespace-nowrap tabular-nums">{formatDate(p.workDate)}</span> },
    { key: "equipment", header: "설비", render: (p) => p.equipmentName ?? "-" },
    { key: "status", header: "상태", width: "w-28", render: (p) => <StatusBadge tone={workPlanStatusTone(p.status)}>{WORK_PLAN_STATUS_LABEL[p.status]}</StatusBadge> },
    { key: "briefing", header: "TBM", width: "w-24", render: (p) => tbmCell(p, today) },
    { key: "open", header: "", width: "w-20", align: "right", render: (p) => <Button variant="subtle" size="sm" onClick={() => onOpen(p.id)}>열기</Button> },
  ];
  return <DataTable columns={columns} data={plans} rowKey={(p) => p.id} onRowClick={(p) => onOpen(p.id)} emptyMessage={emptyMessage} />;
}
