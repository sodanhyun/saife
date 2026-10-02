// WorkPlanResultCard.tsx — 작업 전 안전점검표. 대화의 끝에서 무엇이 만들어졌는지 한 장으로 보인다.
// 등급은 판정 기준(briefingView.decisions)에서 오고, 근거와 개선대책을 평문으로 옆에 둔다.
import { useState } from "react";

import { formUrl } from "@/api/formUrl";
import PhotoLightbox from "@/components/evidence/PhotoLightbox";
import { Badge, StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import LinkButton from "@/components/ui/LinkButton";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { caseTitle, formatShortDateTime } from "@/pages/WorkPlan/utils/format";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { Evidence } from "@/types/evidence";
import type { WorkPlanDetail } from "@/types/workPlan";
import { formatDate } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

interface Props {
  detail: WorkPlanDetail;
  /** 이 대화에서 모인 근거. 유사 재해사례를 결과 카드에 다시 보인다 */
  evidence: Evidence[];
  onOpenDetail?: (id: number) => void;
  /** approval: 승인 모달 안에서 쓴다. 테두리와 그림자, 바닥 버튼이 없다 */
  variant?: "result" | "approval";
}

function Section({ title, children, className }: { title: string; children: React.ReactNode; className?: string }) {
  return (
    <section className={cn("px-6 py-5", className)}>
      <h4 className="mb-3 text-xs font-bold tracking-wide text-slate-500">{title}</h4>
      {children}
    </section>
  );
}

function Points({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <div>
      <p className="mb-1.5 text-sm font-semibold text-slate-900">{title}</p>
      <ol className="space-y-1">
        {items.map((t, i) => (
          <li key={t} className="flex gap-2 text-sm text-slate-700">
            <span className="w-4 shrink-0 tabular-nums text-slate-400">{i + 1}.</span>
            <span>{t}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

export default function WorkPlanResultCard({ detail, evidence, onOpenDetail, variant = "result" }: Props) {
  const approval = variant === "approval";
  const [photo, setPhoto] = useState<Evidence | null>(null);
  const view = detail.briefingView;
  const cases = evidence.filter((e) => e.kind.startsWith("CASE_")).slice(0, 3);
  const photoCases = cases.filter((e) => e.thumbnailUrl);
  const textCases = cases.filter((e) => !e.thumbnailUrl);
  const workers = detail.workers.map((w) => (w.position ? `${w.name} ${w.position}` : w.name)).join(", ");
  const meta = [formatDate(detail.workDate), detail.workPlace, detail.equipmentName, workers].filter(Boolean) as string[];
  const approvedLine = detail.approvedAt && detail.approvedBy
    ? `${detail.status === "CONDITIONAL" ? "조건부 승인" : "승인"} ${detail.approvedBy}, ${formatShortDateTime(detail.approvedAt)}`
    : null;

  return (
    <article aria-label={`${detail.documentTitle} ${detail.workName}`}
      className={cn("overflow-hidden bg-white", approval ? "" : "rounded-xl border border-slate-200 shadow-lift animate-rise-in")}>
      <header className="border-b border-slate-100 bg-brand-soft px-6 py-5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-bold tracking-wide text-brand">{detail.documentTitle}</span>
          <StatusBadge tone={workPlanStatusTone(detail.status)}>{WORK_PLAN_STATUS_LABEL[detail.status]}</StatusBadge>
          {approvedLine && <span className="ml-auto text-sm font-medium tabular-nums text-slate-600">{approvedLine}</span>}
        </div>
        <h3 className="mt-1.5 text-headline text-slate-900">{detail.workName}</h3>
        <p className="mt-1 text-sm text-slate-600">{meta.join("  /  ")}</p>
        {detail.warningNote && (
          <p className="mt-3 rounded-md border border-risk-high-border bg-risk-high-bg px-3 py-2 text-sm font-medium text-risk-high-text">{detail.warningNote}</p>
        )}
        {detail.approvalNote && (
          <p className="mt-3 text-sm text-slate-700"><span className="mr-2 text-xs font-bold text-slate-500">잠정조치</span>{detail.approvalNote}</p>
        )}
      </header>

      {view && view.decisions.length > 0 && (
        <Section title="위험성">
          <ul className="space-y-3.5">
            {view.decisions.map((d, i) => (
              <li key={d.accidentType} className="flex items-start gap-4 animate-rise-in" style={{ animationDelay: `${120 + i * 110}ms` }}>
                <RiskGradeMark level={d.riskLevel} />
                <div className="min-w-0 flex-1">
                  <p className="text-stage font-semibold text-slate-900">{d.label}</p>
                  <p className="mt-1 rounded-md bg-panel px-2.5 py-1.5 text-sm text-slate-700">{d.ruleTrace}</p>
                  {d.recommendation && (
                    <p className="mt-1.5 text-sm text-slate-900"><span className="mr-2 text-xs font-bold text-brand">개선대책</span>{d.recommendation}</p>
                  )}
                </div>
              </li>
            ))}
          </ul>
          {view.pendingActions.length > 0 && (
            <div className="mt-4 space-y-1 rounded-lg border border-risk-high-border bg-risk-high-bg px-4 py-2.5">
              {view.pendingActions.map((a) => (
                <p key={a.content} className="text-sm text-slate-800">
                  <span className="mr-2 text-xs font-bold text-risk-high-text">미이행 조치</span>
                  {a.content}
                  {a.overdueDays !== null && a.overdueDays > 0 && <span className="ml-2 font-semibold text-risk-high-text">{a.overdueDays}일 경과</span>}
                </p>
              ))}
            </div>
          )}
        </Section>
      )}

      {view && (view.riskPoints.length > 0 || view.keepPoints.length > 0) && (
        <Section title="TBM" className="border-t border-slate-100">
          <div className="grid gap-5 md:grid-cols-2">
            <Points title="위험 포인트" items={view.riskPoints} />
            <Points title="지킬 것" items={view.keepPoints} />
          </div>
          <p className="mt-3 text-sm font-medium text-slate-900">위험하면 작업을 멈추고 관리감독자에게 알립니다.</p>
        </Section>
      )}

      {(view?.msds || cases.length > 0) && (
        <div className="grid divide-slate-100 border-t border-slate-100 md:grid-cols-2 md:divide-x">
          {view?.msds && (
            <Section title="MSDS">
              <p className="text-sm font-semibold text-slate-900">{view.msds.productName}</p>
              {view.msds.inferred && (
                <p className="mt-1 flex flex-wrap items-center gap-1.5 text-sm text-slate-600">
                  <Badge>추정 주성분 {view.msds.chemName}</Badge>
                  <span>제품 MSDS 확인 필요</span>
                </p>
              )}
              <dl className="mt-2.5 space-y-1.5 text-sm">
                {view.msds.lines.map((l) => (
                  <div key={l.item} className="grid grid-cols-[64px_minmax(0,1fr)] gap-2">
                    <dt className="text-xs font-semibold text-slate-500">{l.item}</dt>
                    <dd className="line-clamp-2 text-slate-700">{l.text}</dd>
                  </div>
                ))}
              </dl>
            </Section>
          )}
          {cases.length > 0 && (
            <Section title="유사 재해사례">
              {photoCases.length > 0 && (
                <div className="mb-2.5 flex gap-2">
                  {photoCases.map((e) => (
                    <button key={e.no} type="button" aria-label="사진 크게 보기" onClick={() => setPhoto(e)}
                      className="shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-line">
                      <img src={e.thumbnailUrl ?? undefined} alt={e.title} className="h-16 w-24 rounded object-cover" />
                    </button>
                  ))}
                </div>
              )}
              <ul className="space-y-1.5">
                {[...photoCases, ...textCases].map((e) => (
                  <li key={e.no} className="line-clamp-2 text-sm text-slate-700">{caseTitle(e.title)}</li>
                ))}
              </ul>
            </Section>
          )}
        </div>
      )}

      {detail.slots.length > 0 && (
        <Section title="현장 확인" className="border-t border-slate-100">
          <div className="flex flex-wrap gap-2">
            {detail.slots.map((s) => (
              <span key={s.slotKey} className={cn("rounded-md border px-2.5 py-1 text-sm", s.conflicted ? "border-risk-high-border bg-risk-high-bg text-risk-high-text" : "border-slate-200 bg-slate-50 text-slate-700")}>
                <span className="text-xs text-slate-500">{s.label}</span>
                <span className="ml-2 font-semibold">{s.displayValue ?? "-"}</span>
              </span>
            ))}
          </div>
        </Section>
      )}

      {!approval && (
        <footer className="flex flex-wrap items-center gap-2 border-t border-slate-100 bg-slate-50 px-6 py-3.5">
          {onOpenDetail && <Button size="sm" onClick={() => onOpenDetail(detail.id)}>검토 및 승인</Button>}
          <LinkButton href={formUrl.workPlan(detail.id)} external>서식 출력</LinkButton>
        </footer>
      )}
      {photo && <PhotoLightbox e={photo} onClose={() => setPhoto(null)} />}
    </article>
  );
}
