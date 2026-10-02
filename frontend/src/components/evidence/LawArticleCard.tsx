// LawArticleCard.tsx — 조문은 사진 대신 항 원문을 크게 보여준다. 시행일과 법제처 링크가 출처다.
import { plainText } from "@/utils/plainText";
import { Scale } from "lucide-react";
import { useState } from "react";

import { originLabel, originTone } from "@/components/evidence/evidenceMeta";
import { StatusBadge } from "@/components/ui/Badge";
import type { Evidence } from "@/types/evidence";

export default function LawArticleCard({ e, scope }: { e: Evidence; scope: string }) {
  const [open, setOpen] = useState(false);
  const full = typeof e.meta.fullText === "string" ? e.meta.fullText : e.snippet;
  const eff = typeof e.meta.effectiveOn === "string" ? e.meta.effectiveOn : null;
  return (
    <article
      id={`evidence-${scope}-${e.no}`}
      className="rounded-lg border border-slate-200 bg-white p-3 shadow-card"
      aria-label={`근거 #${e.no}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs text-slate-500">#{e.no}</span>
        <Scale size={14} className="text-slate-400" aria-hidden />
        <span className="text-xs text-slate-500">법 조문{eff ? `, 시행 ${eff}` : ""}</span>
        <StatusBadge tone={originTone(e)}>{originLabel(e)}</StatusBadge>
      </div>
      <p className="mt-1 text-stage font-semibold">{plainText(e.title)}</p>
      <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{open ? full : e.snippet}</p>
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
