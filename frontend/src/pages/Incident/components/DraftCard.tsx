// DraftCard.tsx — 조사표 초안(재해발생 원인, 재발방지 계획). 접힌 채로 둔다.
import { useState } from "react";

import AgentMessage from "@/components/common/AgentMessage";
import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import Button from "@/components/ui/Button";
import { plain } from "@/pages/Incident/utils/priorRecord";
import type { IncidentRegisterResponse } from "@/types/incident";

export default function DraftCard({ r }: { r: IncidentRegisterResponse }) {
  const [open, setOpen] = useState(false);
  const similarCases = r.similarCases ?? [];

  // 초안 본문의 [#n] 인용이 가리키는 근거. 유사 사례 그리드가 먼저 보인 번호는 빼고 나머지를 카드로 그린다
  const similarNos = new Set(similarCases.map((e) => e.no));
  const seen = new Set<number>();
  const remaining = (r.evidence ?? []).filter((e) => {
    if (similarNos.has(e.no) || seen.has(e.no)) return false;
    seen.add(e.no);
    return true;
  });
  const knownNos = new Set<number>([...similarNos, ...remaining.map((e) => e.no)]);
  const hasDraft = Boolean(r.draft.cause || r.draft.prevention);

  return (
    <section aria-label="조사표 초안" className="rounded-xl border border-slate-200 bg-white shadow-card">
      <header className="flex items-center gap-3 px-5 py-3.5">
        <h3 className="text-sm font-semibold text-slate-900">조사표 초안</h3>
        {!hasDraft && <span className="text-xs text-slate-500">작성 필요</span>}
        <Button variant="secondary" size="sm" className="ml-auto" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          {open ? "접기" : "펼치기"}
        </Button>
      </header>
      {open && (
        <div className="grid gap-6 border-t border-slate-100 px-5 py-4 lg:grid-cols-2 animate-fade-in">
          <div>
            <h4 className="text-xs font-bold tracking-wide text-slate-500">재해발생 원인</h4>
            <div className="mt-1 text-sm">
              <AgentMessage text={plain(r.draft.cause) || "-"} knownNos={knownNos} scope="incident" />
            </div>
            <h4 className="mt-4 text-xs font-bold tracking-wide text-slate-500">재발방지 계획</h4>
            <div className="mt-1 text-sm">
              <AgentMessage text={plain(r.draft.prevention) || "-"} knownNos={knownNos} scope="incident" />
            </div>
          </div>
          <div className="space-y-3">
            {similarCases.length > 0 && <EvidenceGrid items={similarCases} title="유사 재해사례" scope="incident" />}
            {remaining.length > 0 && <EvidenceGrid items={remaining} title="법령" scope="incident" />}
          </div>
        </div>
      )}
    </section>
  );
}
