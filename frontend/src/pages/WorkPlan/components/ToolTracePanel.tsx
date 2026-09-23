import cn from "@/lib/cn";
import SseConnectionStatus from "@/components/ui/SseConnectionStatus";
import type { ConnectionState, ToolTraceRow } from "@/types/sse";

/**
 * 도구 호출 트레이스 — 심사위원이 "입력→판단→도구 실행→결과"를 눈으로 보게 만드는 패널.
 * 프로젝터에서 읽혀야 하므로 큰 글씨 6줄을 유지한다. 도구를 추가할 거면 이 레이아웃을 같이 본다.
 */
export default function ToolTracePanel({ rows, connectionState }: { rows: ToolTraceRow[]; connectionState: ConnectionState }) {
  return (
    <aside className="flex h-full flex-col rounded-lg border border-slate-200 bg-white p-5 shadow-card">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-600">에이전트 트레이스</h2>
        <SseConnectionStatus state={connectionState} />
      </div>
      <ol className="mt-4 space-y-3">
        {rows.length === 0 && <li className="text-sm text-slate-400">도구 호출 대기 중</li>}
        {rows.map((row) => (
          <li key={row.callOrder} className="flex items-center gap-3 text-stage">
            <span className={cn("h-2.5 w-2.5 shrink-0 rounded-full",
              row.status === "running" && "bg-progress animate-pulse",
              row.status === "ok" && "bg-slate-400",
              row.status === "failed" && "bg-risk-high")} />
            <span className="font-mono">{row.toolName}</span>
            {row.durationMs !== undefined && <span className="ml-auto text-sm tabular-nums text-slate-500">{row.durationMs}ms</span>}
          </li>
        ))}
      </ol>
    </aside>
  );
}
