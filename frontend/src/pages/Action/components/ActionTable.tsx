// ActionTable.tsx: 개선대책 목록 표: 기한, 개선대책, 설비, 담당, 상태, 이행 완료
import { Link } from "react-router-dom";

import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import DataTable, { type Column } from "@/components/ui/DataTable";
import cn from "@/lib/cn";
import { ACTION_STATUS_LABEL, dueInfo, hazardLine, type DueTone } from "@/pages/Action/utils/actionRow";
import type { ActionListItem } from "@/types/action";
import { actionStatusTone, toneColor } from "@/utils/statusColors";

interface Props {
  actions: ActionListItem[];
  /** 완료 탭이면 기한 칸이 완료일 칸이 된다 */
  doneOnly?: boolean;
  today: string;
  emptyMessage: string;
  onComplete: (row: ActionListItem) => void;
}

const HINT_CLASS: Record<DueTone, string> = {
  overdue: cn("font-semibold", toneColor("high").text),
  soon: cn("font-semibold", toneColor("medium").text),
  done: "text-slate-400",
  normal: "text-slate-400",
  none: "text-slate-400",
};

export default function ActionTable({ actions, doneOnly = false, today, emptyMessage, onComplete }: Props) {
  const columns: Column<ActionListItem>[] = [
    {
      key: "due",
      header: doneOnly ? "완료일" : "기한",
      render: (a) => {
        const d = dueInfo(a, today);
        const hint = doneOnly ? null : d.hint;
        return (
          <div className="tabular-nums leading-tight">
            <div className={cn(d.tone === "done" && !doneOnly ? "text-slate-500" : "text-slate-900")}>{d.date}</div>
            {hint && <div className={cn("mt-0.5 text-xs", HINT_CLASS[d.tone])}>{hint}</div>}
          </div>
        );
      },
    },
    {
      key: "content",
      header: "개선대책",
      width: "w-full",
      wrap: true,
      render: (a) => {
        const sub = hazardLine(a);
        return (
          <div className="min-w-64 break-keep leading-snug">
            <div className={cn("font-semibold", a.status === "DONE" ? "text-slate-500" : "text-slate-900")}>{a.content}</div>
            {sub && <div className="mt-0.5 text-xs text-slate-500">{sub}</div>}
          </div>
        );
      },
    },
    {
      key: "equipment",
      header: "설비",
      render: (a) =>
        a.equipmentId !== null ? (
          <Link
            to={`/equipment/${a.equipmentId}`}
            className="text-slate-700 underline-offset-2 transition-colors hover:text-brand hover:underline"
          >
            {a.equipmentName ?? "설비"}
          </Link>
        ) : (
          <span className="text-slate-400">-</span>
        ),
    },
    {
      key: "owner",
      header: "담당",
      render: (a) => (a.owner ? <span className="text-slate-700">{a.owner}</span> : <span className="text-slate-400">-</span>),
    },
    {
      key: "status",
      header: "상태",
      render: (a) => <StatusBadge tone={actionStatusTone(a.status)}>{ACTION_STATUS_LABEL[a.status]}</StatusBadge>,
    },
    {
      key: "act",
      header: <span className="sr-only">처리</span>,
      align: "right",
      render: (a) =>
        a.status === "DONE" ? null : (
          <Button variant="secondary" size="sm" onClick={() => onComplete(a)} aria-label={`${a.content} 이행 완료`}>
            이행 완료
          </Button>
        ),
    },
  ];

  return (
    <DataTable
      columns={columns}
      data={actions}
      rowKey={(a) => a.id}
      adaptiveWidth
      emptyMessage={emptyMessage}
      className="rounded-xl"
    />
  );
}
