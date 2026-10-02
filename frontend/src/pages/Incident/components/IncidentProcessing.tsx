// IncidentProcessing.tsx — 보고 요청이 도는 동안의 자리. 가짜 진행률을 만들지 않고 경과 초만 센다.
import { useEffect, useState } from "react";

const STAGES = ["사고 전 기록", "수시평가", "조사표 기한", "작업 보류", "조사표 초안"];

export default function IncidentProcessing() {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);

  return (
    <section
      role="status"
      aria-live="polite"
      className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border border-slate-200 bg-white px-6 py-4 shadow-card animate-fade-in"
    >
      <span className="relative flex h-2.5 w-2.5">
        <span className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />
        <span className="relative h-2.5 w-2.5 rounded-full bg-brand" />
      </span>
      <p className="text-stage font-semibold text-slate-900">기록 중</p>
      <p className="flex flex-wrap gap-x-2 text-sm text-slate-500">
        {STAGES.map((s, i) => (
          <span key={s}>
            {s}
            {i < STAGES.length - 1 && <span className="ml-2 text-slate-300">/</span>}
          </span>
        ))}
      </p>
      <span className="ml-auto text-sm tabular-nums text-slate-500">{seconds}초</span>
    </section>
  );
}
