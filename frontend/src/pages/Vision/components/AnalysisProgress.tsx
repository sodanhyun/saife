// AnalysisProgress.tsx — 판독 파이프라인 4단계(업로드, 모델 판독, 룰 엔진 등급, 근거 수집)와 단계별 소요 시간.
// 스피너 대신 "지금 무엇을 하는가"를 보인다. 등급은 모델이 아니라 룰 엔진 단계에서 난다는 것이 여기서 보인다.
import { useEffect, useState } from "react";

import cn from "@/lib/cn";
import type { AnalysisStage, StageMark } from "@/pages/Vision/hooks/useVisionStream";

const ROWS: { stage: AnalysisStage; title: string; note: string }[] = [
  { stage: "UPLOADING", title: "사진 업로드", note: "원본 보관" },
  { stage: "ANALYZING", title: "모델 판독", note: "Gemini, 6축 탐지" },
  { stage: "GRADING", title: "등급 판정", note: "룰 엔진, 빈도 x 강도" },
  { stage: "EVIDENCE", title: "근거 수집", note: "지침, 조문, 사례" },
];

const ORDER: AnalysisStage[] = ["UPLOADING", "ANALYZING", "GRADING", "EVIDENCE", "DONE"];

function seconds(ms: number): string {
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}

interface Props {
  stage: AnalysisStage | null;
  stageLog: StageMark[];
  analyzing: boolean;
  failed: boolean;
}

export default function AnalysisProgress({ stage, stageLog, analyzing, failed }: Props) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!analyzing) return;
    const id = window.setInterval(() => setNow(Date.now()), 100);
    return () => window.clearInterval(id);
  }, [analyzing]);

  if (stage === null && stageLog.length === 0) return null;
  const currentIdx = stage === null ? -1 : ORDER.indexOf(stage);
  const at = (s: AnalysisStage) => stageLog.find((m) => m.stage === s)?.at;
  const total = stageLog.length > 0 ? (at("DONE") ?? now) - stageLog[0].at : 0;

  return (
    <section aria-label="판독 진행" className="border-t border-slate-100 px-5 py-4">
      <div className="mb-3 flex items-baseline justify-between">
        <h3 className="text-xs font-bold tracking-wide text-slate-500">판독 파이프라인</h3>
        <p className="text-xs tabular-nums text-slate-400">{failed ? "실패" : stage === "DONE" ? `완료 ${seconds(total)}` : seconds(total)}</p>
      </div>
      <ol className="grid grid-cols-4 gap-3">
        {ROWS.map((row, i) => {
          const idx = ORDER.indexOf(row.stage);
          const done = currentIdx > idx;
          const running = currentIdx === idx && analyzing;
          const start = at(row.stage);
          const end = at(ORDER[idx + 1]);
          let elapsed: number | undefined;
          if (start !== undefined && end !== undefined) elapsed = end - start;
          else if (start !== undefined && running) elapsed = now - start;
          return (
            <li key={row.stage} className={cn("min-w-0", !done && !running && "opacity-50")}>
              <div className="flex items-center gap-2">
                <span className="relative flex h-2.5 w-2.5 shrink-0">
                  {running && <span aria-hidden className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />}
                  <span className={cn("relative h-2.5 w-2.5 rounded-full", done || running ? "bg-brand" : "bg-slate-300", failed && running && "bg-risk-high")} />
                </span>
                <p className="truncate text-sm font-semibold text-slate-900">{row.title}</p>
                {elapsed !== undefined && i > 0 && <span className="ml-auto shrink-0 text-xs tabular-nums text-slate-400">{seconds(elapsed)}</span>}
              </div>
              {/* 진행선: 끝난 단계는 꽉 차고, 진행 중 단계는 자란다 */}
              <div className="mt-2 h-1 overflow-hidden rounded bg-slate-100">
                {(done || running) && <div className={cn("h-full origin-left bg-brand", running ? "w-2/3 animate-grow-x" : "w-full")} />}
              </div>
              <p className="mt-1.5 truncate text-xs text-slate-500">{row.note}</p>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
