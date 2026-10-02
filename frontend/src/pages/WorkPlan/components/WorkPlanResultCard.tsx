// WorkPlanResultCard.tsx — 계획서 제출 결과. 대화의 끝에서 "무엇이 만들어졌나"를 한 장으로 보인다.
// 등급은 룰 엔진 판정(briefingView.decisions)이고, 모델 문장은 이 카드 아래에 접혀 들어간다.
import { useState } from "react";

import { formUrl } from "@/api/formUrl";
import EvidenceGrid from "@/components/evidence/EvidenceGrid";
import PhotoLightbox from "@/components/evidence/PhotoLightbox";
import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import LinkButton from "@/components/ui/LinkButton";
import RiskGradeMark from "@/components/ui/RiskGradeMark";
import cn from "@/lib/cn";
import { SLOT_LABEL } from "@/pages/WorkPlan/utils/flowStages";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { Evidence } from "@/types/evidence";
import type { WorkPlanDetail } from "@/types/workPlan";
import { formatDate, formatDateTime } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

interface Props {
  detail: WorkPlanDetail;
  /** 이 대화에서 모인 근거. 사진 있는 사고사례를 결과 카드에 다시 보인다 */
  evidence: Evidence[];
  onOpenDetail?: (id: number) => void;
  /** approval: 승인 모달 안에서 쓴다. 테두리·그림자 없이, 머리 문구와 바닥 버튼을 바꾼다 */
  variant?: "result" | "approval";
}

function Section({ title, note, children, className }: { title: string; note?: string; children: React.ReactNode; className?: string }) {
  return (
    <section className={cn("px-6 py-5", className)}>
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h4 className="text-xs font-bold tracking-wide text-slate-500">{title}</h4>
        {note && <p className="text-xs text-slate-400">{note}</p>}
      </div>
      {children}
    </section>
  );
}

export default function WorkPlanResultCard({ detail, evidence, onOpenDetail, variant = "result" }: Props) {
  const approval = variant === "approval";
  const [photo, setPhoto] = useState<Evidence | null>(null);
  const view = detail.briefingView;
  const cases = evidence.filter((e) => e.kind.startsWith("CASE_")).slice(0, 3);
  const workers = detail.workers.map((w) => (w.position ? `${w.name} ${w.position}` : w.name)).join(", ");
  const meta = [formatDate(detail.workDate), detail.workPlace, detail.equipmentName, workers].filter(Boolean) as string[];

  return (
    <article aria-label={`작업계획서 ${detail.id} ${approval ? "검토" : "제출 결과"}`}
      className={cn("overflow-hidden bg-white", approval ? "" : "rounded-xl border border-slate-200 shadow-lift animate-rise-in")}>
      {/* 머리: 무엇이 만들어졌고 지금 어디에 있는가 */}
      <header className="border-b border-slate-100 bg-brand-soft px-6 py-5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-bold tracking-wide text-brand">작업계획서 #{detail.id}{approval ? "" : " 제출됨"}</span>
          <StatusBadge tone={workPlanStatusTone(detail.status)}>{WORK_PLAN_STATUS_LABEL[detail.status]}</StatusBadge>
        </div>
        <h3 className="mt-1.5 text-headline text-slate-900">{detail.workName}</h3>
        <p className="mt-1 text-sm text-slate-600">{meta.join("  /  ")}</p>
        {detail.warningNote && (
          <p className="mt-3 rounded-md border border-risk-high-border bg-risk-high-bg px-3 py-2 text-sm font-medium text-risk-high-text">{detail.warningNote}</p>
        )}
        {approval && detail.briefingAckAt && (
          <p className="mt-2 text-sm font-medium text-risk-low-text">브리핑 확인 {formatDateTime(detail.briefingAckAt)}, 상시평가 TBM 증빙으로 보존</p>
        )}
      </header>

      {view && view.decisions.length > 0 && (
        <Section title="위험성 판정" note="등급은 룰 엔진이 정합니다. AI는 후보와 문안만 만듭니다">
          <ul className="space-y-3">
            {view.decisions.map((d, i) => (
              <li key={d.accidentType} className="flex items-start gap-4 animate-rise-in" style={{ animationDelay: `${120 + i * 110}ms` }}>
                <RiskGradeMark level={d.riskLevel} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline gap-2">
                    <p className="text-stage font-semibold text-slate-900">{d.label}</p>
                    <p className="text-xs tabular-nums text-slate-400">빈도 {d.frequency} x 강도 {d.severity}</p>
                  </div>
                  <p className="mt-1 rounded-md bg-panel px-2.5 py-1.5 text-sm text-slate-700">{d.ruleTrace}</p>
                </div>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {view && view.pendingActions.length > 0 && (
        <div className="mx-6 mb-1 rounded-lg border border-risk-high-border bg-risk-high-bg px-4 py-3">
          <p className="text-sm font-semibold text-risk-high-text">이 설비에 아직 끝나지 않은 조치가 있습니다</p>
          {view.pendingActions.map((a) => (
            <p key={a.content} className="mt-1 text-sm text-slate-700">
              {a.content}
              {a.overdueDays !== null && a.overdueDays > 0 && <span className="ml-2 font-semibold text-risk-high-text">기한 {a.overdueDays}일 경과</span>}
            </p>
          ))}
        </div>
      )}

      {(view?.msds || cases.length > 0) && (
        <div className="grid divide-slate-100 md:grid-cols-2 md:divide-x">
          {view?.msds && (
            <Section title={`MSDS  ${view.msds.chemName}`} note={view.msds.productName !== view.msds.chemName ? view.msds.productName : undefined}>
              <dl className="space-y-1.5 text-sm">
                {view.msds.lines.map((l) => (
                  <div key={l.item} className="grid grid-cols-[88px_minmax(0,1fr)] gap-2">
                    <dt className="text-xs font-semibold text-slate-500">{l.item}</dt>
                    <dd className="line-clamp-2 text-slate-700">{l.text}</dd>
                  </div>
                ))}
              </dl>
            </Section>
          )}
          {cases.length > 0 && (
            <Section title="같은 발생형태의 실제 사고" note="한국산업안전보건공단">
              <ul className="space-y-2">
                {cases.map((e) => (
                  <li key={e.no} className="flex items-center gap-3">
                    {e.thumbnailUrl ? (
                      <button type="button" aria-label="사진 크게 보기" onClick={() => setPhoto(e)} className="shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-line">
                        <img src={e.thumbnailUrl} alt={e.title} className="h-11 w-16 rounded object-cover" />
                      </button>
                    ) : (
                      <span className="grid h-11 w-16 shrink-0 place-items-center rounded bg-panel text-xs text-slate-400">#{e.no}</span>
                    )}
                    <p className="line-clamp-2 text-sm text-slate-700">{e.title}</p>
                  </li>
                ))}
              </ul>
            </Section>
          )}
        </div>
      )}

      {detail.slots.length > 0 && (
        <Section title="현장 확인 값" note="대장 기록과 오늘 답변이 다르면 그대로 남깁니다" className="border-t border-slate-100">
          <div className="flex flex-wrap gap-2">
            {detail.slots.map((s) => (
              <span key={s.slotKey} className={cn("rounded-md border px-2.5 py-1 text-sm", s.conflicted ? "border-risk-high-border bg-risk-high-bg text-risk-high-text" : "border-slate-200 bg-slate-50 text-slate-700")}>
                <span className="text-xs text-slate-500">{SLOT_LABEL[s.slotKey] ?? s.question}</span>
                <span className="ml-2 font-semibold">{s.answeredValue ?? "-"}</span>
              </span>
            ))}
          </div>
        </Section>
      )}

      {approval && evidence.length > 0 && (
        <div className="border-t border-slate-100 px-6 pb-5">
          <EvidenceGrid items={evidence} title="참고 자료" collapsedByDefault scope={`workplan-${detail.id}`} />
        </div>
      )}

      {!approval && (
        <footer className="flex flex-wrap items-center gap-2 border-t border-slate-100 bg-slate-50 px-6 py-3.5">
          {onOpenDetail && <Button size="sm" onClick={() => onOpenDetail(detail.id)}>승인 화면 열기</Button>}
          <LinkButton href={formUrl.workPlan(detail.id)} external>법정 서식 보기</LinkButton>
          <p className="ml-auto text-xs text-slate-500">작업 전 브리핑 확인은 TBM 기록으로 남습니다</p>
        </footer>
      )}
      {photo && <PhotoLightbox e={photo} onClose={() => setPhoto(null)} />}
    </article>
  );
}
