// LawArticleCard.tsx — 조문은 사진 대신 인용한 항(호) 원문을 크게 보여준다. 시행일과 법제처 링크가 출처다.
// 백엔드가 인용한 항호를 발췌(snippet)로 고르고 meta.focus("제2항제3호")를 붙이면 그 표시를 함께 단다.
import { Scale } from "lucide-react";
import { useState } from "react";

import type { Evidence } from "@/types/evidence";
import { plainText } from "@/utils/plainText";

export default function LawArticleCard({ e, scope }: { e: Evidence; scope: string }) {
  const [open, setOpen] = useState(false);
  const full = typeof e.meta.fullText === "string" ? e.meta.fullText : e.snippet;
  const eff = typeof e.meta.effectiveOn === "string" ? e.meta.effectiveOn : null;
  const focus = typeof e.meta.focus === "string" ? e.meta.focus : null;
  const title = plainText(e.title);
  return (
    <article id={`evidence-${scope}-${e.no}`} className="rounded-lg border border-slate-200 bg-white p-3 shadow-card" aria-label={`근거 #${e.no}`}>
      <div className="flex flex-wrap items-center gap-2">
        <Scale size={14} className="text-slate-400" aria-hidden />
        <span className="text-xs text-slate-500">법 조문{eff ? `, 시행 ${eff}` : ""}</span>
      </div>
      <p className="mt-1 text-stage font-semibold">{title}</p>
      {focus && !open && <p className="mt-1 text-xs font-semibold text-slate-500">{focus}</p>}
      <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{plainText(open ? full : e.snippet)}</p>
      <div className="mt-2 flex items-center gap-3 text-xs">
        {full !== e.snippet && (
          <button
            type="button"
            className="text-slate-500 underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border"
            onClick={() => setOpen((v) => !v)}
          >
            {open ? "접기" : "전문 보기"}
          </button>
        )}
        {e.sourceUrl && (
          <a href={e.sourceUrl} target="_blank" rel="noreferrer" className="text-slate-500 underline">
            법제처 원문
          </a>
        )}
      </div>
    </article>
  );
}
