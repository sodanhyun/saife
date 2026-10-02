// EvidenceCard.tsx — 근거 카드. 사례·지침은 공통 레이아웃, 조문·MSDS는 전용 카드로 위임한다.
// 내부 번호(#n)는 카드에 쓰지 않는다. 본문 인용 칩이 카드를 찾아갈 때는 id로 찾는다.
import { useState } from "react";

import LawArticleCard from "@/components/evidence/LawArticleCard";
import MsdsCard from "@/components/evidence/MsdsCard";
import { cleanEvidenceSnippet, cleanEvidenceTitle } from "@/components/evidence/evidenceMeta";
import cn from "@/lib/cn";
import { EVIDENCE_KIND_LABEL, type Evidence } from "@/types/evidence";

interface Props {
  e: Evidence;
  /** id·reveal 이벤트 스코프(F18) — 상위 EvidenceGrid가 넘긴다 */
  scope: string;
  onOpenPhoto?: (e: Evidence) => void;
  className?: string;
}

export default function EvidenceCard({ e, scope, onOpenPhoto, className }: Props) {
  if (e.kind === "LAW") return <LawArticleCard e={e} scope={scope} />;
  if (e.kind === "MSDS") return <MsdsCard e={e} scope={scope} />;
  return <GenericCard e={e} scope={scope} onOpenPhoto={onOpenPhoto} className={className} />;
}

function GenericCard({ e, scope, onOpenPhoto, className }: Props) {
  const [imgFailed, setImgFailed] = useState(false);
  const showImg = !!e.thumbnailUrl && !imgFailed;
  const title = cleanEvidenceTitle(e.title);
  const snippet = cleanEvidenceSnippet(e.snippet);
  return (
    <article
      id={`evidence-${scope}-${e.no}`}
      className={cn("flex gap-3 rounded-lg border border-slate-200 bg-white p-3 shadow-card", className)}
      aria-label={`근거 #${e.no}`}
    >
      {showImg && (
        <button
          type="button"
          aria-label="사진 크게 보기"
          className="block w-24 shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border"
          onClick={() => onOpenPhoto?.(e)}
        >
          <img src={e.thumbnailUrl ?? undefined} alt={title} className="h-16 w-24 rounded object-cover" onError={() => setImgFailed(true)} />
        </button>
      )}
      <div className="min-w-0 flex-1">
        <span className="text-xs text-slate-500">{EVIDENCE_KIND_LABEL[e.kind]}</span>
        <p className="mt-1 text-stage font-semibold leading-snug">{title}</p>
        {snippet && <p className="mt-1 line-clamp-2 text-sm text-slate-600">{snippet}</p>}
        {(e.sourceUrl ?? e.mediaUrl) && (
          <div className="mt-2 text-xs text-slate-500">
            <a href={e.sourceUrl ?? e.mediaUrl ?? "#"} target="_blank" rel="noreferrer" className="underline">
              원문 보기
            </a>
          </div>
        )}
      </div>
    </article>
  );
}
