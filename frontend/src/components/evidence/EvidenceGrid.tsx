// EvidenceGrid.tsx — 카드 목록. 3장 이하 세로, 4장 이상 2열. 사진 클릭은 라이트박스.
//
// F3: 접힌 채로 시작해도(collapsedByDefault) 인용 칩이 가리킬 카드가 필요하면 스스로 펼친다 —
// CitationChip이 쏘는 "evidence:reveal" 커스텀 이벤트를 듣다가, 자기 scope이고 그 번호를
// 들고 있으면 연다. 서로 다른 그리드가 번호를 공유할 수 있어(F18) scope로 걸러야 한다.
import { useEffect, useState } from "react";

import EvidenceCard from "@/components/evidence/EvidenceCard";
import PhotoLightbox from "@/components/evidence/PhotoLightbox";
import cn from "@/lib/cn";
import type { Evidence } from "@/types/evidence";

interface Props {
  items: Evidence[];
  title?: string;
  collapsedByDefault?: boolean;
  /** id·reveal 이벤트를 가르는 번호 공간. 같은 페이지에 그리드가 여럿이면 반드시 서로 달라야 한다 */
  scope: string;
  className?: string;
}

export default function EvidenceGrid({ items, title = "근거", collapsedByDefault = false, scope, className }: Props) {
  const [open, setOpen] = useState(!collapsedByDefault);
  const [photo, setPhoto] = useState<Evidence | null>(null);

  useEffect(() => {
    const onReveal = (e: Event) => {
      const detail = (e as CustomEvent<{ no: number; scope: string } | undefined>).detail;
      if (!detail || detail.scope !== scope) return;
      if (items.some((item) => item.no === detail.no)) setOpen(true);
    };
    window.addEventListener("evidence:reveal", onReveal);
    return () => window.removeEventListener("evidence:reveal", onReveal);
  }, [items, scope]);

  if (items.length === 0) return null;
  return (
    <section className={cn("mt-2", className)} aria-label={title}>
      <button
        type="button"
        className="text-xs font-semibold text-slate-500 underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-progress-border"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        {title} {items.length}건 {open ? "접기" : "펼치기"}
      </button>
      {open && (
        <div className={cn("mt-2 grid gap-2", items.length >= 4 ? "md:grid-cols-2" : "grid-cols-1")}>
          {items.map((e) => (
            <EvidenceCard key={`${e.kind}-${e.refKey}-${e.no}`} e={e} scope={scope} onOpenPhoto={setPhoto} />
          ))}
        </div>
      )}
      {photo && <PhotoLightbox e={photo} onClose={() => setPhoto(null)} />}
    </section>
  );
}
