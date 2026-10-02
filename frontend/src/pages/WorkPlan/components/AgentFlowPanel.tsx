// AgentFlowPanel.tsx — 에이전트 작업 흐름. 심사위원이 "입력 → 판단 → 도구 실행 → 결과"를 눈으로 따라가게 만드는 패널.
// 단계는 도구 6종으로 고정이고, 각 단계에 입력값과 도구가 남긴 결과 한 줄, 되묻기 분기를 그대로 보인다.
import { useEffect, useState } from "react";

import SseConnectionStatus from "@/components/ui/SseConnectionStatus";
import cn from "@/lib/cn";
import { paramValues, SLOT_LABEL, toStageViews, type StageState, type StageView } from "@/pages/WorkPlan/utils/flowStages";
import type { ConnectionState, ToolTraceRow } from "@/types/sse";

interface EvidenceCount { total: number; photos: number; guides: number; laws: number; msds: number }

interface Props {
  rows: ToolTraceRow[];
  connectionState: ConnectionState;
  streaming: boolean;
  evidence: EvidenceCount;
}

/** 실행 중 단계가 있을 때만 0.1초 단위로 다시 그린다(경과 시간 표시) */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const id = window.setInterval(() => setNow(Date.now()), 100);
    return () => window.clearInterval(id);
  }, [active]);
  return now;
}

const DOT: Record<StageState, string> = {
  idle: "border-slate-300 bg-white",
  running: "border-brand bg-brand",
  ok: "border-brand bg-brand",
  incomplete: "border-pending bg-pending",
  failed: "border-risk-high bg-risk-high",
};

function seconds(ms: number): string {
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}

function StageRow({ view, index, isLast, now }: { view: StageView; index: number; isLast: boolean; now: number }) {
  const { stage, state, calls, latest, askedFor } = view;
  const done = state === "ok" || state === "incomplete";
  const inputs = latest ? paramValues(latest.params).slice(0, 3) : [];
  const elapsed = latest?.status === "running" ? now - latest.startedAt : latest?.durationMs;

  return (
    <li className="relative flex gap-3.5 pb-4 last:pb-0">
      {/* 단계 사이 세로선: 끝난 단계까지 brand로 채운다 */}
      {!isLast && (
        <span aria-hidden className={cn("absolute left-[9px] top-6 bottom-0 w-0.5", done ? "bg-brand-line" : "bg-slate-200")} />
      )}
      <span className="relative mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center">
        {state === "running" && <span aria-hidden className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />}
        <span aria-hidden className={cn("relative grid h-5 w-5 place-items-center rounded-full border-2 text-xs font-bold", DOT[state], state === "idle" ? "text-slate-400" : "text-white")}>
          {index + 1}
        </span>
      </span>
      <div className={cn("min-w-0 flex-1", state === "idle" && "opacity-60")}>
        <div className="flex items-baseline gap-2">
          <p className={cn("text-stage font-semibold", state === "idle" ? "text-slate-500" : "text-slate-900")}>{stage.title}</p>
          {calls.length > 1 && <span className="text-xs font-medium text-slate-400">{calls.length}회 호출</span>}
          <span className="sr-only">{state === "running" ? "실행 중" : state === "ok" ? "완료" : state === "incomplete" ? "되묻기" : state === "failed" ? "실패" : "대기"}</span>
          {elapsed !== undefined && <span className="ml-auto shrink-0 text-xs tabular-nums text-slate-400">{seconds(elapsed)}</span>}
        </div>
        <p className="mt-0.5 font-mono text-xs text-slate-400">{stage.tool}</p>
        {state === "idle" && <p className="mt-1 text-xs text-slate-500">{stage.purpose}</p>}
        {inputs.length > 0 && (
          <div className="mt-1.5 flex flex-wrap gap-1">
            {inputs.map((v) => (
              <span key={v} className="max-w-full truncate rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-xs text-slate-600">{v}</span>
            ))}
          </div>
        )}
        {askedFor.length > 0 && (
          <p className="mt-1.5 rounded-md border border-pending-border bg-pending-bg px-2 py-1 text-xs font-medium text-pending-text">
            판단: 필수 값 비어 있음, {askedFor.map((k) => SLOT_LABEL[k] ?? k).join(", ")} 되묻기
          </p>
        )}
        {latest?.summary && state !== "running" && (
          <p className="mt-1.5 text-sm text-slate-700 animate-fade-in">{latest.summary}</p>
        )}
        {latest?.status === "failed" && latest.errorMessage && (
          <p className="mt-1.5 text-xs text-risk-high-text">실패: {latest.errorMessage}</p>
        )}
      </div>
    </li>
  );
}

export default function AgentFlowPanel({ rows, connectionState, streaming, evidence }: Props) {
  const views = toStageViews(rows);
  const now = useNow(rows.some((r) => r.status === "running"));
  const completed = views.filter((v) => v.state === "ok").length;
  const callCount = rows.length;

  return (
    <aside aria-label="에이전트 작업 흐름" className="flex flex-col rounded-xl border border-slate-200 bg-white shadow-card">
      <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3.5">
        <div>
          <h2 className="text-base font-semibold text-slate-900">에이전트 작업 흐름</h2>
          <p className="mt-0.5 text-xs text-slate-500">
            {callCount === 0 ? "도구 6종 대기" : `도구 호출 ${callCount}회, 단계 ${completed}/6 완료`}
          </p>
        </div>
        <SseConnectionStatus state={streaming ? connectionState : "idle"} />
      </div>
      <ol className="px-5 py-4">
        {views.map((v, i) => <StageRow key={v.stage.tool} view={v} index={i} isLast={i === views.length - 1} now={now} />)}
      </ol>
      <div className="grid grid-cols-4 border-t border-slate-100 text-center">
        {[
          ["사고사례", String(evidence.total - evidence.guides - evidence.laws - evidence.msds)],
          ["기술지침", String(evidence.guides)],
          ["법 조문", String(evidence.laws)],
          ["MSDS", String(evidence.msds)],
        ].map(([label, value]) => (
          <div key={label} className="px-2 py-3">
            <p className="text-lg font-bold tabular-nums text-slate-900">{value}</p>
            <p className="text-xs text-slate-500">{label}</p>
          </div>
        ))}
      </div>
    </aside>
  );
}
