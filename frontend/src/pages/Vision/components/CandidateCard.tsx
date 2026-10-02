// CandidateCard.tsx — AI 후보 한 건. 등급 옆에 룰 트레이스를 항상 같이 띄우고, 채택 버튼이 사람의 자리다.
// 채택하면 같은 카드 안에서 감소대책 등록 → 이행 대기 → 이행 완료로 이어진다.
import { useState } from "react";

import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import { Badge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import { ActionForm, ActionStatusRow } from "@/pages/Vision/components/ActionPanel";
import type { ActionInput } from "@/pages/Vision/hooks/useVision";
import { daysUntil } from "@/pages/Vision/utils/dates";
import type { VisionCandidate } from "@/types/vision";
import { dDayLabel } from "@/utils/datetime";

interface Props {
  c: VisionCandidate;
  busy: boolean;
  onDecide: (hazardId: number, adopt: boolean) => void;
  onCreateAction: (c: VisionCandidate, input: ActionInput) => void;
  onCompleteAction: (hazardId: number, actionId: number) => void;
  /** 등장 순서 지연(ms) */
  delay?: number;
}

export default function CandidateCard({ c, busy, onDecide, onCreateAction, onCompleteAction, delay = 0 }: Props) {
  // 이미 미이행 조치가 걸린 재확인 후보는 폼을 접어 둔다. 새 대책은 사람이 열어서 추가한다
  const [formOpen, setFormOpen] = useState(c.priorOpenAction === null);
  const prior = c.priorOpenAction;
  const priorDays = daysUntil(prior?.dueDate ?? null);

  return (
    <article aria-label={`후보 ${c.missingControl}`} className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card animate-rise-in" style={{ animationDelay: `${delay}ms` }}>
      <div className="flex gap-4 p-5">
        <RiskGradeMark level={c.riskLevel} size="lg" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge variant="progress">AI 후보</Badge>
            <Badge>{c.accidentLabel}</Badge>
            {c.alreadyKnown && <Badge variant="pending">기존 위험요인 재확인</Badge>}
            {c.gateStatus === "CHECKLIST" && <Badge variant="pending">참고, 현장 확인 필요</Badge>}
          </div>
          <h3 className="mt-1.5 text-headline text-slate-900">{c.missingControl}</h3>
          {c.evidence && (
            <p className="mt-1 text-sm text-slate-600"><span className="mr-1.5 text-xs font-bold text-slate-500">사진 근거</span>{c.evidence}</p>
          )}
          {c.gateNote && <p className="mt-1 text-xs text-slate-500">{c.gateNote}</p>}

          <div className="mt-3 rounded-md bg-panel px-3 py-2">
            <p className="text-xs font-bold tracking-wide text-slate-500">룰 엔진 판정</p>
            <p className="mt-0.5 text-sm text-slate-700">{c.ruleTrace}</p>
          </div>

          {prior && (
            <div className="mt-3 rounded-md border border-risk-high-border bg-risk-high-bg px-3 py-2">
              <p className="text-sm font-semibold text-risk-high-text">이 위험요인에 끝나지 않은 조치가 있습니다</p>
              <p className="mt-0.5 text-sm text-slate-700">
                {prior.content}
                {prior.dueDate && <span className="ml-2 text-xs text-slate-500">기한 {prior.dueDate}</span>}
                {priorDays !== null && priorDays < 0 && <span className="ml-2 font-semibold text-risk-high-text">{dDayLabel(priorDays)}, 기한 경과</span>}
              </p>
            </div>
          )}

          <EvidenceGrid items={c.evidenceItems ?? []} title="근거" collapsedByDefault scope={`candidate-${c.hazardId}`} className="mt-3" />
        </div>
      </div>

      <footer className="border-t border-slate-100 bg-slate-50">
        {c.adopted === null && (
          <div className="flex items-center gap-2 px-5 py-3.5">
            <Button loading={busy} onClick={() => onDecide(c.hazardId, true)}>채택</Button>
            <Button variant="secondary" disabled={busy} onClick={() => onDecide(c.hazardId, false)}>반려</Button>
            <p className="ml-auto text-xs text-slate-500">AI는 후보만 제안합니다. 확정은 사람이 합니다</p>
          </div>
        )}

        {c.adopted === false && (
          <div className="flex items-center gap-3 px-5 py-3.5">
            <span className="text-sm font-semibold text-slate-500">반려됨</span>
            <span className="text-xs text-slate-400">채택률 분모에만 남고 평가표에서는 빠집니다</span>
            <Button className="ml-auto" size="sm" variant="subtle" disabled={busy} onClick={() => onDecide(c.hazardId, true)}>되돌리기</Button>
          </div>
        )}

        {c.adopted === true && c.action === null && (
          formOpen ? (
            <div className="bg-white">
              <ActionForm suggested={c.suggestedAction} busy={busy} onSubmit={(input) => onCreateAction(c, input)} onCancel={prior ? () => setFormOpen(false) : undefined} />
            </div>
          ) : (
            <div className="flex items-center gap-3 px-5 py-3.5">
              <span className="text-sm font-semibold text-risk-low-text">채택됨</span>
              <span className="text-xs text-slate-500">기존 조치의 이행이 먼저입니다</span>
              <Button className="ml-auto" size="sm" variant="secondary" onClick={() => setFormOpen(true)}>감소대책 추가</Button>
            </div>
          )
        )}

        {c.adopted === true && c.action !== null && (
          <div className={c.action.status === "DONE" ? "bg-risk-low-bg" : "bg-white"}>
            <ActionStatusRow action={c.action} busy={busy} onComplete={() => c.action && onCompleteAction(c.hazardId, c.action.id)} />
          </div>
        )}
      </footer>
    </article>
  );
}
