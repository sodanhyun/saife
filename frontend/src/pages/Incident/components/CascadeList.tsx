// CascadeList.tsx — 사고 연쇄 4단계를 가로 사슬로 보인다. "한 사건이 네 곳을 차례로 바꾼다".
// 카드가 약 350ms 간격으로 차례로 떠오르고, 카드 사이 연결선이 앞 카드에서 뒤 카드로 채워진다.
// 각 카드는 버튼이다: 누르면 아래 상세 카드로 이동한다(키보드 Enter/Space는 네이티브 버튼이 처리).
import cn from "@/lib/cn";
import { CASCADE_ANCHOR_BY_KIND } from "@/pages/Incident/utils/cascadeAnchor";
import type { CascadeCard } from "@/pages/Incident/utils/premonition";
import type { CascadeKind, CascadeStep } from "@/types/incident";
import { emphasisTone, toneColor } from "@/utils/statusColors";

/** 카드 등장 간격. 첫 카드는 히어로가 자리 잡은 뒤 뜬다 */
export const STEP_INTERVAL_MS = 350;
const FIRST_DELAY_MS = 500;

function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

interface Props {
  cards: CascadeCard[];
  /** 백엔드 cascade — 단계별 강조(emphasis)만 가져다 쓴다 */
  steps?: CascadeStep[];
}

export default function CascadeList({ cards, steps = [] }: Props) {
  if (cards.length === 0) return null;
  const reduced = prefersReducedMotion();
  const emphasisOf = (kind: CascadeKind) => steps.find((s) => s.kind === kind)?.emphasis ?? "NORMAL";

  const jumpTo = (kind: CascadeKind) => {
    const el = document.getElementById(CASCADE_ANCHOR_BY_KIND[kind]);
    el?.scrollIntoView({ block: "start", behavior: reduced ? "auto" : "smooth" });
  };

  return (
    <section aria-labelledby="cascade-title">
      <h2 id="cascade-title" className="mb-3 text-xs font-bold tracking-wide text-slate-500">
        등록 한 번으로 이어진 일
      </h2>
      <ol className="grid gap-4 md:grid-cols-4 md:gap-8">
        {cards.map((card, i) => {
          const emphasis = emphasisOf(card.kind);
          const tone = toneColor(emphasisTone(emphasis));
          const delay = reduced ? 0 : FIRST_DELAY_MS + i * STEP_INTERVAL_MS;
          const last = i === cards.length - 1;
          return (
            <li key={card.kind} className="relative">
              <button
                type="button"
                onClick={() => jumpTo(card.kind)}
                aria-label={`${card.order}단계 ${card.label}: ${card.value}. ${card.detail}`}
                className={cn(
                  "flex h-full w-full flex-col rounded-xl border border-slate-200 bg-white px-5 py-4 text-left shadow-card",
                  "transition-colors hover:border-slate-300 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-line",
                  !reduced && "animate-rise-in",
                )}
                style={reduced ? undefined : { animationDelay: `${delay}ms` }}
              >
                <span className="flex items-center gap-2">
                  <span
                    aria-hidden
                    className={cn("grid h-6 w-6 place-items-center rounded-full text-xs font-bold text-white", tone.solid)}
                  >
                    {card.order}
                  </span>
                  <span className="text-xs font-bold tracking-wide text-slate-500">{card.label}</span>
                </span>
                <span className={`text-display ${cn("mt-3 tabular-nums", emphasis === "NORMAL" ? "text-slate-900" : tone.text)}`}>
                  {card.value}
                </span>
                <span className="mt-1 line-clamp-2 text-sm text-slate-600">{card.detail}</span>
              </button>
              {!last && (
                <span
                  aria-hidden
                  className="absolute left-full top-1/2 hidden h-0.5 w-8 -translate-y-1/2 bg-slate-200 md:block"
                >
                  <span
                    className={cn("absolute inset-0 origin-left bg-slate-400", !reduced && "animate-grow-x")}
                    style={reduced ? undefined : { animationDelay: `${delay + 220}ms`, animationDuration: "320ms" }}
                  />
                  <span className="absolute -right-0.5 top-1/2 h-2 w-2 -translate-y-1/2 rotate-45 border-r-2 border-t-2 border-slate-400" />
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
