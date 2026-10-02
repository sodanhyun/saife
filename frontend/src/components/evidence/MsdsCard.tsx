// MsdsCard.tsx — GHS 픽토그램이 글자보다 먼저 읽힌다. 아이콘은 리포 동봉 SVG(/ghs/GHSxx.svg).
import { plainText } from "@/utils/plainText";
import { useState } from "react";

import type { Evidence } from "@/types/evidence";

const SECTION_NAME: Record<string, string> = {
  "02": "유해성, 위험성",
  "05": "폭발, 화재 시 대처",
  "07": "취급과 저장",
  "08": "노출방지와 보호구",
};

export default function MsdsCard({ e, scope }: { e: Evidence; scope: string }) {
  const [failedCodes, setFailedCodes] = useState<Set<string>>(new Set());
  const codes = typeof e.meta.pictograms === "string" ? e.meta.pictograms.split(",").filter(Boolean) : [];
  const visibleCodes = codes.filter((c) => !failedCodes.has(c));
  // 원장 데이터가 손상되어도(문자열·null 등) 카드가 죽지 않도록 항목별로 배열만 신뢰한다.
  const sections = (e.meta.sections ?? {}) as Record<string, unknown>;
  return (
    <article
      id={`evidence-${scope}-${e.no}`}
      className="rounded-lg border border-slate-200 bg-white p-3 shadow-card"
      aria-label={`근거 #${e.no}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs text-slate-500">#{e.no}</span>
        <span className="text-xs text-slate-500">MSDS</span>
      </div>
      <p className="mt-1 text-stage font-semibold">{plainText(e.title)}</p>
      {visibleCodes.length > 0 && (
        <div className="mt-2 flex gap-2">
          {visibleCodes.map((c) => (
            <img
              key={c}
              src={`/ghs/${c}.svg`}
              alt={c}
              title={c}
              className="h-10 w-10"
              onError={() => setFailedCodes((prev) => new Set(prev).add(c))}
            />
          ))}
        </div>
      )}
      <dl className="mt-2 grid gap-1 text-sm">
        {Object.entries(sections).map(([code, value]) => {
          const lines = Array.isArray(value) ? value : [];
          return (
            <div key={code}>
              <dt className="text-xs font-semibold text-slate-500">{SECTION_NAME[code] ?? `항목 ${code}`}</dt>
              <dd className="text-slate-700">{lines.slice(0, 4).join(", ")}</dd>
            </div>
          );
        })}
      </dl>
      {e.sourceUrl && (
        <a href={e.sourceUrl} target="_blank" rel="noreferrer" className="mt-2 inline-block text-xs text-slate-500 underline">
          공단 MSDS 원문
        </a>
      )}
    </article>
  );
}
