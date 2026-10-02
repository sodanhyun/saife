// AdoptionRate.tsx — 성과 지표: 후보 채택률. 정확도가 아니라 사람이 채택한 비율이다.
import type { AdoptionRate as Rate } from "@/types/vision";

export default function AdoptionRate({ rate }: { rate: Rate }) {
  const pct = rate.rate === null ? null : Math.round(rate.rate * 100);
  return (
    <div title={rate.note} aria-label="후보 채택률" className="flex items-center gap-4 rounded-xl border border-slate-200 bg-white px-4 py-2.5 shadow-card">
      <div>
        <p className="text-xs font-bold tracking-wide text-slate-500">후보 채택률</p>
        <p className="text-xs text-slate-400">{rate.axes.length > 0 ? `${rate.axes.join(", ")} 축, 사람이 채택한 비율` : "사람이 채택한 비율"}</p>
      </div>
      <p className="text-2xl font-bold tabular-nums text-slate-900">{pct === null ? "-" : `${pct}%`}</p>
      <p className="text-sm tabular-nums text-slate-500">{rate.adopted}/{rate.suggested}</p>
    </div>
  );
}
