// CandidateCard.tsx — 등급 옆에 룰 트레이스를 항상 같이 띄운다. 채택 버튼이 사람의 자리다.
import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import { Badge, RiskBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import type { VisionCandidate } from "@/types/vision";

interface Props { c: VisionCandidate; busy: boolean; onDecide: (hazardId: number, adopt: boolean) => void }

export default function CandidateCard({ c, busy, onDecide }: Props) {
  return (
    <Card>
      <div className="flex flex-wrap items-center gap-2">
        <Badge>{c.accidentLabel}</Badge>
        <span className="text-stage font-semibold">{c.missingControl}</span>
        <RiskBadge level={c.riskLevel} />
        {c.alreadyKnown && <Badge>기존 위험요인 재확인</Badge>}
        {c.gateStatus === "CHECKLIST" && <Badge variant="pending">참고 — 현장 확인 필요</Badge>}
      </div>
      {c.gateNote && <p className="mt-1 text-xs text-slate-500">{c.gateNote}</p>}
      {c.evidence && <p className="mt-2 text-sm text-slate-700"><span className="text-slate-500">근거: </span>{c.evidence}</p>}
      <p className="mt-2 rounded-md bg-panel px-2 py-1 font-mono text-xs text-slate-600">{c.ruleTrace}</p>
      <EvidenceGrid items={c.evidenceItems ?? []} title="근거" collapsedByDefault scope={`candidate-${c.hazardId}`} className="mt-2" />
      <div className="mt-3 flex items-center gap-2">
        {c.adopted === null ? (
          <>
            <Button size="sm" loading={busy} onClick={() => onDecide(c.hazardId, true)}>채택</Button>
            <Button size="sm" variant="secondary" disabled={busy} onClick={() => onDecide(c.hazardId, false)}>반려</Button>
            <span className="text-xs text-slate-400">확정은 사람이 합니다</span>
          </>
        ) : (
          <>
            <span className={c.adopted ? "text-sm font-medium text-risk-low-text" : "text-sm font-medium text-slate-500"}>{c.adopted ? "채택됨" : "반려됨"}</span>
            <Button size="sm" variant="subtle" disabled={busy} onClick={() => onDecide(c.hazardId, !c.adopted)}>되돌리기</Button>
          </>
        )}
      </div>
    </Card>
  );
}
