// AgentFlowPanel.tsx — 진행. 여섯 단계가 차례로 끝나는 것을 보이고, 단계마다 결과 한 줄만 남긴다.
// 확인 값이 비어 멈춘 단계는 "확인 필요: 발판 높이"처럼 무엇이 비었는지만 보인다.
import cn from "@/lib/cn";
import { SLOT_LABEL, toStageViews, type StageState, type StageView } from "@/pages/WorkPlan/utils/flowStages";
import type { ToolTraceRow } from "@/types/sse";

interface EvidenceCount { total: number; photos: number; guides: number; laws: number; msds: number }

interface Props {
  rows: ToolTraceRow[];
  evidence: EvidenceCount;
}

const DOT: Record<StageState, string> = {
  idle: "border-slate-300 bg-white text-slate-400",
  running: "border-brand bg-brand text-white",
  ok: "border-brand bg-brand text-white",
  incomplete: "border-pending bg-pending text-white",
  failed: "border-risk-high bg-risk-high text-white",
};

const STATE_LABEL: Record<StageState, string> = {
  idle: "대기",
  running: "진행 중",
  ok: "완료",
  incomplete: "확인 필요",
  failed: "실패",
};

function StageRow({ view, index, isLast }: { view: StageView; index: number; isLast: boolean }) {
  const { stage, state, latest, missing } = view;
  const done = state === "ok" || state === "incomplete";
  return (
    <li className="relative flex gap-3.5 pb-5 last:pb-0">
      {!isLast && (
        <span aria-hidden className={cn("absolute left-[9px] top-6 bottom-0 w-0.5", done ? "bg-brand-line" : "bg-slate-200")} />
      )}
      <span className="relative mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center">
        {state === "running" && <span aria-hidden className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />}
        <span aria-hidden className={cn("relative grid h-5 w-5 place-items-center rounded-full border-2 text-xs font-bold", DOT[state])}>
          {index + 1}
        </span>
      </span>
      <div className="min-w-0 flex-1">
        <p className={cn("text-stage font-semibold", state === "idle" ? "text-slate-400" : "text-slate-900")}>
          {stage.title}
          <span className="sr-only"> {STATE_LABEL[state]}</span>
        </p>
        {state === "incomplete" && missing.length > 0 && (
          <p className="mt-1 text-sm font-medium text-pending-text">확인 필요: {missing.map((k) => SLOT_LABEL[k] ?? k).join(", ")}</p>
        )}
        {state === "ok" && latest?.summary && (
          <p className="mt-0.5 text-sm text-slate-600 animate-fade-in">{latest.summary}</p>
        )}
        {state === "failed" && <p className="mt-0.5 text-sm text-risk-high-text">다시 시도해 주세요</p>}
      </div>
    </li>
  );
}

export default function AgentFlowPanel({ rows, evidence }: Props) {
  const views = toStageViews(rows);
  const cases = evidence.total - evidence.guides - evidence.laws - evidence.msds;

  return (
    <aside aria-label="진행" className="flex flex-col rounded-xl border border-slate-200 bg-white shadow-card animate-fade-in">
      <h2 className="border-b border-slate-100 px-5 py-3.5 text-base font-semibold text-slate-900">진행</h2>
      <ol className="px-5 py-4">
        {views.map((v, i) => <StageRow key={v.stage.tool} view={v} index={i} isLast={i === views.length - 1} />)}
      </ol>
      {evidence.total > 0 && (
        <dl className="grid grid-cols-4 border-t border-slate-100 text-center">
          {[
            ["재해사례", cases],
            ["지침", evidence.guides],
            ["조문", evidence.laws],
            ["MSDS", evidence.msds],
          ].map(([label, value]) => (
            <div key={label} className="flex flex-col-reverse px-2 py-3">
              <dt className="text-xs text-slate-500">{label}</dt>
              <dd className="text-lg font-bold tabular-nums text-slate-900">{value}</dd>
            </div>
          ))}
        </dl>
      )}
    </aside>
  );
}
