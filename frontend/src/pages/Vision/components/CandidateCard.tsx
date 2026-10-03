// CandidateCard.tsx — 위험요인 한 건. 반영/제외, 허용 가능 여부, 개선대책, 이행 결과가 한 카드 안에서 이어진다.
// 개선대책 초안은 기준표(ActionSuggestionTable) 1순위 문안이다. 기존 조치가 기한을 넘겼어도 같은 구체 대책을 다시 세운다.
import { ChevronDown } from "lucide-react";
import { useState } from "react";

import EvidenceCard from "@/components/evidence/EvidenceCard";
import PhotoLightbox from "@/components/evidence/PhotoLightbox";
import { Badge, StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import SegmentedControl from "@/components/ui/SegmentedControl";
import cn from "@/lib/cn";
import { ActionForm, ActionStatusRow } from "@/pages/Vision/components/ActionPanel";
import type { ActionInput } from "@/pages/Vision/hooks/useVision";
import { daysUntil, dueLabel, shortDate } from "@/pages/Vision/utils/dates";
import { isOverdue } from "@/pages/Vision/utils/inspection";
import type { Evidence } from "@/types/evidence";
import type { VisionCandidate } from "@/types/vision";

interface Props {
  c: VisionCandidate;
  busy: boolean;
  onDecide: (hazardId: number, reflect: boolean) => void;
  onAcceptable: (hazardId: number, acceptable: boolean) => void;
  onCreateAction: (c: VisionCandidate, input: ActionInput) => void;
  onCompleteAction: (hazardId: number, actionId: number) => void;
  /** 등장 순서 지연(ms) */
  delay?: number;
}

function decisionChip(adopted: boolean | null) {
  if (adopted === null) return <StatusBadge tone="pending">검토 필요</StatusBadge>;
  return adopted ? <StatusBadge tone="progress">반영</StatusBadge> : <StatusBadge tone="neutral">제외</StatusBadge>;
}

/** 근거 카드(지침, 조문, 사례). 접힌 채로 시작한다 */
function EvidenceList({ items, scope }: { items: Evidence[]; scope: string }) {
  const [open, setOpen] = useState(false);
  const [photo, setPhoto] = useState<Evidence | null>(null);
  if (items.length === 0) return null;
  return (
    <section className="mt-3" aria-label="근거">
      <button type="button" aria-expanded={open} onClick={() => setOpen((v) => !v)} className="inline-flex items-center gap-1 text-xs font-semibold text-slate-500 hover:text-slate-800">
        근거 {items.length}건
        <ChevronDown aria-hidden className={cn("h-3.5 w-3.5 transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <div className="mt-2 grid grid-cols-1 gap-2">
          {items.map((e) => <EvidenceCard key={`${e.kind}-${e.refKey}-${e.no}`} e={e} scope={scope} onOpenPhoto={setPhoto} />)}
        </div>
      )}
      {photo && <PhotoLightbox e={photo} onClose={() => setPhoto(null)} />}
    </section>
  );
}

export default function CandidateCard({ c, busy, onDecide, onAcceptable, onCreateAction, onCompleteAction, delay = 0 }: Props) {
  const prior = c.priorOpenAction;
  const priorDays = daysUntil(prior?.dueDate ?? null);
  const priorOverdue = isOverdue(prior);
  // 기한이 남은 기존 조치가 있으면 폼을 접어 둔다. 기한이 지났으면 대책을 다시 세워야 하므로 펼친다
  const [formOpen, setFormOpen] = useState(prior === null || priorOverdue);

  return (
    <article aria-label={`위험요인 ${c.missingControl}`} className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-card animate-rise-in" style={{ animationDelay: `${delay}ms` }}>
      <div className="flex gap-4 p-5">
        <RiskGradeMark level={c.riskLevel} size="lg" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge>{c.accidentLabel}</Badge>
            {c.alreadyKnown && <Badge variant="pending">기존 위험요인</Badge>}
            {c.gateStatus === "CHECKLIST" && <Badge variant="pending">현장 확인 필요</Badge>}
            <span className="ml-auto">{decisionChip(c.adopted)}</span>
          </div>
          <h3 className="mt-1.5 text-headline text-slate-900">{c.missingControl}</h3>
          {c.evidence && (
            <p className="mt-1 text-sm text-slate-600"><span className="mr-1.5 text-xs font-bold text-slate-500">사진 내용</span>{c.evidence}</p>
          )}

          <div className="mt-3 rounded-md bg-panel px-3 py-2">
            <p className="text-xs font-bold tracking-wide text-slate-500">등급 근거</p>
            <p className="mt-0.5 text-sm text-slate-700">{c.ruleTrace}</p>
          </div>

          {prior && (
            <div className={cn("mt-3 rounded-md border px-3 py-2", priorOverdue ? "border-risk-high-border bg-risk-high-bg" : "border-slate-200 bg-slate-50")}>
              <p className="flex flex-wrap items-baseline gap-x-2 text-sm">
                <span className={cn("font-semibold", priorOverdue ? "text-risk-high-text" : "text-slate-700")}>미이행 조치</span>
                <span className="text-slate-800">{prior.content}</span>
              </p>
              <p className="mt-0.5 text-xs text-slate-500">
                {[prior.owner, prior.dueDate ? `기한 ${shortDate(prior.dueDate)}` : null].filter(Boolean).join(", ")}
                {priorOverdue && <span className="ml-2 font-semibold text-risk-high-text">{dueLabel(priorDays)}</span>}
              </p>
            </div>
          )}

          <EvidenceList items={c.evidenceItems ?? []} scope={`candidate-${c.hazardId}`} />
        </div>
      </div>

      <footer className="border-t border-slate-100 bg-slate-50">
        {c.adopted === null && (
          <div className="flex items-center gap-2 px-5 py-3.5">
            <Button loading={busy} onClick={() => onDecide(c.hazardId, true)}>반영</Button>
            <Button variant="secondary" disabled={busy} onClick={() => onDecide(c.hazardId, false)}>제외</Button>
          </div>
        )}

        {c.adopted === false && (
          <div className="flex items-center gap-3 px-5 py-3">
            <span className="text-sm font-semibold text-slate-500">제외</span>
            <Button className="ml-auto" size="sm" variant="subtle" disabled={busy} onClick={() => onDecide(c.hazardId, true)}>되돌리기</Button>
          </div>
        )}

        {c.adopted === true && (
          <>
            <div className="flex items-center gap-3 px-5 py-3">
              <span className="text-xs font-bold tracking-wide text-slate-500">허용 가능 여부</span>
              <SegmentedControl<boolean>
                ariaLabel="허용 가능 여부"
                size="sm"
                className="w-44"
                value={c.acceptable}
                onChange={(v) => { if (v !== c.acceptable) onAcceptable(c.hazardId, v); }}
                options={[
                  { value: false, label: "불가" },
                  { value: true, label: "가능" },
                ]}
              />
              {c.acceptable && <span className="text-sm text-slate-500">현 상태 유지</span>}
            </div>

            {!c.acceptable && c.action !== null && (
              <div className={cn("border-t border-slate-100", c.action.status === "DONE" ? "bg-risk-low-bg" : "bg-white")}>
                <ActionStatusRow action={c.action} busy={busy} onComplete={() => c.action && onCompleteAction(c.hazardId, c.action.id)} />
              </div>
            )}

            {!c.acceptable && c.action === null && (
              formOpen ? (
                <div className="border-t border-slate-100 bg-white">
                  <ActionForm suggested={c.suggestedAction} busy={busy} onSubmit={(input) => onCreateAction(c, input)} onCancel={prior && !priorOverdue ? () => setFormOpen(false) : undefined} />
                </div>
              ) : (
                <div className="flex items-center gap-3 border-t border-slate-100 px-5 py-3">
                  <span className="text-sm text-slate-600">기존 조치 이행 대기</span>
                  <Button className="ml-auto" size="sm" variant="secondary" onClick={() => setFormOpen(true)}>개선대책 추가</Button>
                </div>
              )
            )}
          </>
        )}
      </footer>
    </article>
  );
}
