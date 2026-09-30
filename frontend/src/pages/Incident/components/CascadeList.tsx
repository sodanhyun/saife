// CascadeList.tsx — 사고 연쇄 4단계. "등록 한 번에 세 가지가 동시에 일어난다"가 아니라
// "한 사건이 세 곳을 차례로 바꾼다"로 보이게 하는 세로 스텝.
//
// 카드 위에 얹히는 안내일 뿐이다. 기존 카드(회상·수시평가·조사표 KPI·작업계획서 표)의
// 구조·문구는 건드리지 않고, 이 목록의 등장 애니메이션도 카드 높이에는 관여하지 않는다.
import { useEffect, useMemo, useState } from "react";

import cn from "@/lib/cn";
import { CASCADE_ANCHOR_BY_KIND } from "@/pages/Incident/utils/cascadeAnchor";
import type { CascadeKind, CascadeStep } from "@/types/incident";
import { emphasisTone, toneColor } from "@/utils/statusColors";

const STEP_ENTER_INTERVAL_MS = 150;

function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

interface CascadeListProps {
  steps: CascadeStep[];
  /**
   * WORK_PLAN 스텝의 앵커를 스텝 자신에게 둘지 여부 — `AffectedWorkPlans` 표가 없을 때(0건)
   * IncidentResult가 true를 넘긴다. 표가 있을 때는 표 쪽 래퍼가 같은 id(`cascade-workplans`)를
   * 이미 갖고 있으므로 여기서는 달지 않는다(DOM에 같은 id 중복 방지).
   */
  workPlanAnchorOnSelf?: boolean;
}

/** 사고 연쇄 스텝 목록. cascade가 없거나 비어 있으면 아무것도 렌더하지 않는다 */
export default function CascadeList({ steps, workPlanAnchorOnSelf = false }: CascadeListProps) {
  const ordered = useMemo(() => [...steps].sort((a, b) => a.order - b.order), [steps]);
  const reduced = useMemo(() => prefersReducedMotion(), []);

  // 등장 애니메이션 진행도 — reduced가 아닐 때만 타이머가 이 값을 밀어 올린다.
  // reduced일 때는 아예 건드리지 않고, 렌더 시점에 ordered.length로 대체해서 쓴다
  // (effect 안에서 곧바로 setState하는 패턴을 피한다 — react-hooks/set-state-in-effect).
  const [tick, setTick] = useState(0);
  const visibleCount = reduced ? ordered.length : tick;

  useEffect(() => {
    if (reduced) return undefined;
    const timers = ordered.map((_, i) =>
      setTimeout(() => setTick((v) => Math.max(v, i + 1)), i * STEP_ENTER_INTERVAL_MS),
    );
    // 언마운트·재마운트 시 타이머를 반드시 정리한다 — 안 하면 이전 결과 화면에 걸어둔
    // 타이머가 새 결과 화면의 상태를 건드린다.
    return () => timers.forEach(clearTimeout);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [steps, reduced]);

  if (ordered.length === 0) return null;

  const jumpTo = (kind: CascadeKind) => {
    const el = document.getElementById(CASCADE_ANCHOR_BY_KIND[kind]);
    if (!el) return;
    el.scrollIntoView({ block: "start", behavior: reduced ? "auto" : "smooth" });
  };

  return (
    <ol className="space-y-2" aria-label="사고 연쇄">
      {ordered.map((step, i) => {
        const tone = emphasisTone(step.emphasis);
        const c = toneColor(tone);
        const visible = i < visibleCount;
        const selfAnchorId =
          step.kind === "WORK_PLAN" && workPlanAnchorOnSelf ? CASCADE_ANCHOR_BY_KIND.WORK_PLAN : undefined;
        return (
          <li key={step.kind} id={selfAnchorId}>
            <button
              type="button"
              onClick={() => jumpTo(step.kind)}
              className={cn(
                "flex w-full items-start gap-3 rounded-md border p-3 text-left transition-all duration-300 ease-out",
                c.bg,
                c.border,
                "hover:brightness-95 focus:outline-none focus:ring-2 focus:ring-offset-1",
                c.ring,
                visible ? "translate-y-0 opacity-100" : "translate-y-1 opacity-0",
              )}
            >
              <span
                className={cn(
                  "mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white",
                  c.solid,
                )}
              >
                {step.order}
              </span>
              <span className="min-w-0">
                <span className={cn("block text-stage font-semibold", c.text)}>{step.title}</span>
                <span className="block text-sm text-slate-700">{step.detail}</span>
              </span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}
