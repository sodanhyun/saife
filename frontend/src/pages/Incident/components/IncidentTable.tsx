import { StatusBadge } from "@/components/ui/Badge";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { ACCIDENT_LABEL, SEVERITY_LABEL } from "@/types/domain";
import type { IncidentListItem } from "@/types/incident";
import { formatDate, formatDateTime } from "@/utils/datetime";
import { reportDutyTone } from "@/utils/statusColors";

interface Props {
  incidents: IncidentListItem[];
  /** 지금 결과 화면에 열린 사고 */
  selectedId?: number | null;
  /** 행을 누르면 그 사고의 결과 화면을 연다 */
  onOpen?: (id: number) => void;
}

/** 사고 이력: 발생 일시, 설비, 발생형태, 휴업, 조사표 상태와 기한. 제출이 끝난 건은 기한을 숨긴다 */
export default function IncidentTable({ incidents, selectedId = null, onOpen }: Props) {
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
      width: "w-28",
      render: (i) => (i.accidentType ? ACCIDENT_LABEL[i.accidentType] : "-"),
    },
    {
      key: "leave",
      header: "휴업",
      width: "w-24",
      render: (i) => (
        <span className="tabular-nums">
          {i.severity === "FATALITY" ? SEVERITY_LABEL.FATALITY : i.leaveDays === null ? "미입력" : `${i.leaveDays}일`}
        </span>
      ),
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
      render: (i) => {
        if (i.reportStatus === "SUBMITTED" || !i.reportDueDate) return <span className="text-slate-400">-</span>;
        const late = i.daysRemaining !== null && i.daysRemaining < 0;
        return (
          <span className="tabular-nums">
            {formatDate(i.reportDueDate)}
            {late && <span className="ml-2 font-semibold text-risk-high-text">{Math.abs(i.daysRemaining ?? 0)}일 경과</span>}
          </span>
        );
      },
    },
  ];
  return (
    <DataTable
      columns={columns}
      data={incidents}
      rowKey={(i) => i.id}
      onRowClick={onOpen ? (i) => onOpen(i.id) : undefined}
      rowClassName={(i) => (i.id === selectedId ? "bg-slate-50" : undefined)}
      emptyMessage="보고된 사고가 없습니다"
    />
  );
}
