import { StatusBadge } from "@/components/ui/Badge";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { ACCIDENT_LABEL } from "@/types/domain";
import type { IncidentListItem } from "@/types/incident";
import { dDayLabel, formatDate, formatDateTime } from "@/utils/datetime";
import { reportDutyTone } from "@/utils/statusColors";

/** 사고 목록 — 발생 일시·설비·발생형태·휴업·조사표 상태·기한·수시평가 */
export default function IncidentTable({ incidents }: { incidents: IncidentListItem[] }) {
  const columns: Column<IncidentListItem>[] = [
    {
      key: "occurredAt",
      header: "발생 일시",
      width: "w-40",
      render: (i) => <span className="tabular-nums">{formatDateTime(i.occurredAt)}</span>,
    },
    { key: "equipment", header: "설비", render: (i) => i.equipmentName ?? "-" },
    {
      key: "type",
      header: "발생형태",
      width: "w-24",
      render: (i) => (i.accidentType ? ACCIDENT_LABEL[i.accidentType] : "-"),
    },
    {
      key: "leave",
      header: "휴업",
      width: "w-20",
      render: (i) => <span className="tabular-nums">{i.leaveDays === null ? "미입력" : `${i.leaveDays}일`}</span>,
    },
    {
      key: "report",
      header: "조사표",
      width: "w-28",
      render: (i) => <StatusBadge tone={reportDutyTone(i.reportStatus)}>{i.reportStatusLabel}</StatusBadge>,
    },
    {
      key: "due",
      header: "기한",
      width: "w-44",
      render: (i) => (
        <span className="tabular-nums">
          {formatDate(i.reportDueDate)}
          {i.daysRemaining !== null && (
            <span className={i.daysRemaining < 0 ? "ml-2 text-risk-high-text" : "ml-2 text-slate-500"}>
              {dDayLabel(i.daysRemaining)}
            </span>
          )}
        </span>
      ),
    },
    {
      key: "followUp",
      header: "수시평가",
      width: "w-24",
      render: (i) => (i.followUpAssessmentId ? `#${i.followUpAssessmentId}` : "-"),
    },
  ];
  return <DataTable columns={columns} data={incidents} rowKey={(i) => i.id} emptyMessage="등록된 사고가 없습니다" />;
}
