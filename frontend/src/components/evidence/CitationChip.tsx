// CitationChip.tsx — 모델 문장 속 [#n]. 원장에 있는 번호만 칩이 되고, 클릭하면
// 해당 근거 카드(id="evidence-{scope}-{n}")로 스크롤·강조한다. 모르는 번호는 평문으로 남긴다.
//
// F3: 근거 그리드는 전부 접힌 채로 시작한다(collapsedByDefault) — 카드가 DOM에 없으면
// getElementById가 조용히 실패한다. 그래서 먼저 evidence:reveal 이벤트로 그리드를 펼치라고
// 알리고, 다음 프레임(그리드가 다시 그려진 뒤)에 같은 id를 한 번 더 찾아 스크롤·강조한다.
import { Fragment, useEffect, useRef } from "react";

interface Props {
  no: number;
  known: boolean;
  /** 같은 페이지 안에서도 번호 공간이 겹칠 수 있다(F18) — 그리드와 칩이 같은 scope를 공유해야 한다 */
  scope: string;
}

export default function CitationChip({ no, known, scope }: Props) {
  // 연타로 여러 타이머가 겹치면 먼저 걸린 타이머가 뒤 타이머의 강조를 조기에 지운다 —
  // 진행 중인 타이머를 추적해 새로 걸기 전에, 그리고 언마운트 시 정리한다.
  const highlightTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (highlightTimeoutRef.current !== null) clearTimeout(highlightTimeoutRef.current);
  }, []);

  if (!known) return <Fragment>{`[#${no}]`}</Fragment>;

  const highlight = (el: HTMLElement) => {
    const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    el.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "center" });
    el.classList.add("ring-2", "ring-progress-border");
    if (highlightTimeoutRef.current !== null) clearTimeout(highlightTimeoutRef.current);
    highlightTimeoutRef.current = setTimeout(() => el.classList.remove("ring-2", "ring-progress-border"), 1500);
  };

  const jump = () => {
    const id = `evidence-${scope}-${no}`;
    const el = document.getElementById(id);
    if (el) {
      highlight(el);
      return;
    }
    // 접혀 있을 수 있다 — 이 scope·no를 쥔 그리드에게 펼치라고 알린다.
    window.dispatchEvent(new CustomEvent("evidence:reveal", { detail: { no, scope } }));
    // 그리드의 setOpen은 React 이벤트 밖(window 리스너)에서 일어나므로 커밋 시점이 다음 프레임보다
    // 늦을 수 있다(2026-09-30 실브라우저 실측: 첫 클릭에 그리드는 펼쳐졌지만 rAF 한 번으로는 카드를
    // 못 찾아 스크롤·강조가 빠졌다). 카드가 나타날 때까지 최대 30프레임(약 0.5초) 동안 프레임마다 다시 찾는다.
    let frames = 0;
    const tick = () => {
      const revealed = document.getElementById(id);
      if (revealed) {
        highlight(revealed);
        return;
      }
      if (++frames < 30) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  };

  return (
    <button
      type="button"
      onClick={jump}
      aria-label={`근거 #${no}`}
      className="mx-0.5 inline-flex items-center rounded border border-progress-border bg-progress-bg px-1 align-baseline font-mono text-xs text-progress-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border"
    >
      #{no}
    </button>
  );
}
