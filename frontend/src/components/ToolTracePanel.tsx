import type { ToolTraceRow } from "@/types/sse";

/**
 * 도구 호출 트레이스 패널.
 *
 * 심사위원이 "입력 → 판단 → 도구 실행 → 결과"를 추론하는 게 아니라 눈으로 보게 만드는 화면.
 * 프로젝터 해상도에서 읽혀야 하므로 로그 덤프가 아니라 큰 글씨 6줄로 유지한다.
 * 도구를 추가할 거면 이 레이아웃을 같이 본다.
 */
export function ToolTracePanel({ rows }: { rows: ToolTraceRow[] }) {
  return (
    <aside className="flex flex-col border-l bg-white p-5">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
        Agent Trace
      </h2>

      <ol className="mt-4 space-y-3">
        {rows.length === 0 && (
          <li className="text-sm text-slate-400">도구 호출 대기 중</li>
        )}

        {rows.map((row) => (
          <li key={row.callOrder} className="flex items-center gap-3 text-base">
            <span
              className={
                row.status === "running"
                  ? "h-2.5 w-2.5 animate-pulse rounded-full bg-amber-400"
                  : row.status === "ok"
                    ? "h-2.5 w-2.5 rounded-full bg-emerald-500"
                    : "h-2.5 w-2.5 rounded-full bg-red-500"
              }
            />
            <span className="font-mono">{row.toolName}</span>
            {row.durationMs !== undefined && (
              <span className="ml-auto tabular-nums text-sm text-slate-500">
                {row.durationMs}ms
              </span>
            )}
          </li>
        ))}
      </ol>
    </aside>
  );
}
