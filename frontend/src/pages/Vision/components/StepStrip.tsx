// StepStrip.tsx — 사진 점검 5단계 진행 띠. 사진에서 이행까지 지금 어디에 있는지를 한 줄로 보인다.
import { Check } from "lucide-react";

import cn from "@/lib/cn";
import type { StepState, StepView } from "@/pages/Vision/utils/steps";

const DOT: Record<StepState, string> = {
  idle: "border-slate-300 bg-white text-slate-400",
  active: "border-brand bg-white text-brand",
  running: "border-brand bg-brand text-white",
  done: "border-brand bg-brand text-white",
};

export default function StepStrip({ steps }: { steps: StepView[] }) {
  return (
    <ol aria-label="점검 진행" className="mb-5 flex items-center rounded-xl border border-slate-200 bg-white px-6 py-4 shadow-card">
      {steps.map((s, i) => {
        const last = i === steps.length - 1;
        const live = s.state === "active" || s.state === "running";
        return (
          <li key={s.key} aria-current={live ? "step" : undefined} className={cn("flex min-w-0 items-center", !last && "flex-1")}>
            <span className="relative flex h-8 w-8 shrink-0 items-center justify-center">
              {live && <span aria-hidden className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />}
              <span className={cn("relative grid h-8 w-8 place-items-center rounded-full border-2 text-sm font-bold transition-colors", DOT[s.state])}>
                {s.state === "done" ? <Check aria-hidden className="h-4 w-4" strokeWidth={3} /> : i + 1}
              </span>
            </span>
            <div className="ml-3 min-w-0 shrink-0">
              <p className={cn("text-stage font-semibold", s.state === "idle" ? "text-slate-400" : "text-slate-900")}>
                {s.title}
                <span className="sr-only">{s.state === "done" ? " 완료" : live ? " 진행 중" : " 대기"}</span>
              </p>
              {s.detail && (
                <p className={cn("max-w-[11rem] truncate text-xs", live ? "font-semibold text-brand" : "text-slate-500")}>{s.detail}</p>
              )}
            </div>
            {/* 다음 단계로 가는 선. 이 단계가 끝나면 brand로 채운다 */}
            {!last && (
              <span aria-hidden className="mx-4 h-0.5 min-w-6 flex-1 overflow-hidden rounded bg-slate-200">
                {s.state === "done" && <span className="block h-full origin-left bg-brand animate-grow-x" />}
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}
