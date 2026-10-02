// DraftCard.tsx — 산업재해조사표 원인·재발방지 초안. 연쇄의 주인공은 아니라 접힌 채로 둔다.
import { useState } from "react";

import AgentMessage from "@/components/common/AgentMessage";
import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import { Badge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import { plain } from "@/pages/Incident/utils/premonition";
import type { IncidentRegisterResponse } from "@/types/incident";

export default function DraftCard({ r }: { r: IncidentRegisterResponse }) {
  const [open, setOpen] = useState(false);
  const similarCases = r.similarCases ?? [];

  // 조사표 초안 본문의 [#n] 인용이 가리키는 근거 번호(R42). 유사 사례 그리드가 먼저 보인 번호는 빼고
  // 나머지를 전부 카드로 그린다. knownNos는 실제로 카드가 그려진 번호만 담는다.
  const similarNos = new Set(similarCases.map((e) => e.no));
  const seen = new Set<number>();
  const remaining = (r.evidence ?? []).filter((e) => {
    if (similarNos.has(e.no) || seen.has(e.no)) return false;
    seen.add(e.no);
    return true;
  });
  const knownNos = new Set<number>([...similarNos, ...remaining.map((e) => e.no)]);
  const total = similarCases.length + remaining.length;

  return (
    <section aria-label="산업재해조사표 초안" className="rounded-xl border border-slate-200 bg-white shadow-card">
      <header className="flex flex-wrap items-center gap-3 px-5 py-3.5">
        <h3 className="text-sm font-semibold text-slate-900">산업재해조사표 초안 문안</h3>
        <Badge>{r.draft.aiGenerated ? "AI 작성 보조" : "AI 생성 아님"}</Badge>
        <span className="text-xs text-slate-500">근거 {total}건 인용, 확정과 제출은 담당자가 합니다</span>
        <Button variant="secondary" size="sm" className="ml-auto" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          {open ? "접기" : "문안 보기"}
        </Button>
      </header>
      {open && (
        <div className="grid gap-6 border-t border-slate-100 px-5 py-4 lg:grid-cols-2 animate-fade-in">
          <div>
            <h4 className="text-xs font-bold tracking-wide text-slate-500">재해 발생 원인</h4>
            <div className="mt-1 text-sm">
              <AgentMessage text={plain(r.draft.cause)} knownNos={knownNos} scope="incident" />
            </div>
            <h4 className="mt-4 text-xs font-bold tracking-wide text-slate-500">재발 방지 계획</h4>
            <div className="mt-1 text-sm">
              <AgentMessage text={plain(r.draft.prevention)} knownNos={knownNos} scope="incident" />
            </div>
            <p className="mt-3 text-xs text-slate-400">{plain(r.draft.disclaimer)}</p>
          </div>
          <div className="space-y-3">
            {similarCases.length > 0 && <EvidenceGrid items={similarCases} title="동종 유사 사고 (공단 사례)" scope="incident" />}
            <EvidenceGrid items={remaining} title="법령 근거" scope="incident" />
          </div>
        </div>
      )}
    </section>
  );
}
