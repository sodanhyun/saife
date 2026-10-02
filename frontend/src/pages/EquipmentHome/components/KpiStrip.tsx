// KpiStrip.tsx — 홈 최상단 숫자 다섯 개. 누르면 아래 "오늘 할 일"이 그 종류로 좁혀진다.
// 0은 흐리게 둔다. 눈은 0이 아닌 숫자에만 가야 한다.
import cn from "@/lib/cn";
import type { KpiKey, KpiModel } from "@/pages/EquipmentHome/utils/todayModel";
import { toneColor } from "@/utils/statusColors";

interface Props {
  kpis: KpiModel[];
  active: KpiKey | null;
  onSelect: (key: KpiKey) => void;
}

export default function KpiStrip({ kpis, active, onSelect }: Props) {
  return (
    <section aria-label="오늘 요약" className="mb-5 grid grid-cols-2 overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card sm:grid-cols-3 xl:grid-cols-5">
      {kpis.map((k, i) => {
        const zero = k.value === 0;
        const selected = active === k.key;
        return (
          <button key={k.key} type="button" aria-pressed={selected} onClick={() => onSelect(k.key)}
            className={cn("relative min-w-0 px-5 py-4 text-left transition-colors animate-rise-in",
              i > 0 && "xl:border-l xl:border-slate-100",
              selected ? "bg-panel" : "hover:bg-slate-50",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-progress-border")}
            style={{ animationDelay: `${i * 60}ms` }}>
            {selected && <span className="absolute inset-x-0 bottom-0 h-0.5 bg-brand" aria-hidden />}
            <span className="block text-xs font-bold tracking-wide text-slate-500">{k.label}</span>
            {/* 크기와 색을 한 cn()에 넣으면 tailwind-merge가 text-display를 색으로 보고 지운다. 요소를 나눈다 */}
            <span className="mt-1 block text-display tabular-nums">
              <span className={cn(zero ? "text-slate-300" : k.tone === "neutral" ? "text-slate-900" : toneColor(k.tone).text)}>{k.value}</span>
            </span>
            <span className={cn("mt-0.5 block truncate text-xs", zero ? "text-slate-300" : "text-slate-500")}>{k.note}</span>
          </button>
        );
      })}
    </section>
  );
}
