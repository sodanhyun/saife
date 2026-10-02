// ActionPanel.tsx — 채택한 후보의 감소대책: 등록 폼(룰 표 초안으로 미리 채움) → 이행 대기 → 이행 완료.
import { useState } from "react";

import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import DateInput from "@/components/ui/DateInput";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import LinkButton from "@/components/ui/LinkButton";
import Textarea from "@/components/ui/Textarea";
import type { ActionInput } from "@/pages/Vision/hooks/useVision";
import { basisLine, DEFAULT_OWNER } from "@/pages/Vision/utils/action";
import { defaultDueDate, daysUntil } from "@/pages/Vision/utils/dates";
import type { ActionView, SuggestedAction } from "@/types/action";
import { dDayLabel, formatDateTime } from "@/utils/datetime";

interface FormProps {
  suggested: SuggestedAction | null;
  busy: boolean;
  onSubmit: (input: ActionInput) => void;
  onCancel?: () => void;
}

export function ActionForm({ suggested, busy, onSubmit, onCancel }: FormProps) {
  const [content, setContent] = useState(suggested?.content ?? "");
  const [owner, setOwner] = useState(DEFAULT_OWNER);
  const [dueDate, setDueDate] = useState(defaultDueDate);
  const basis = basisLine(suggested);

  return (
    <form
      aria-label="감소대책 등록"
      className="animate-rise-in px-5 py-4"
      onSubmit={(e) => { e.preventDefault(); if (content.trim()) onSubmit({ content, owner, dueDate }); }}
    >
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <h4 className="text-xs font-bold tracking-wide text-brand">감소대책</h4>
        <p className="text-xs text-slate-400">룰 표에서 고른 초안입니다. 고쳐서 등록하십시오</p>
      </div>
      <Textarea aria-label="감소대책 내용" rows={2} value={content} onChange={(e) => setContent(e.target.value)} className="text-stage" />
      {basis && <p className="mt-1.5 text-xs text-slate-500">근거 {basis}</p>}
      <div className="mt-3 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-end gap-3">
        <FormField label="담당">
          <Input value={owner} onChange={(e) => setOwner(e.target.value)} />
        </FormField>
        <FormField label="기한">
          <DateInput type="date" aria-label="기한" value={dueDate} onChange={setDueDate} />
        </FormField>
        <div className="flex gap-2">
          {onCancel && <Button type="button" variant="ghost" disabled={busy} onClick={onCancel}>닫기</Button>}
          <Button type="submit" loading={busy} disabled={!content.trim()}>감소대책 등록</Button>
        </div>
      </div>
    </form>
  );
}

interface StatusProps {
  action: ActionView;
  busy: boolean;
  onComplete: () => void;
}

/** 등록된 조치. PENDING이면 이행 완료 버튼, DONE이면 완료 시각과 설비 타임라인 링크 */
export function ActionStatusRow({ action, busy, onComplete }: StatusProps) {
  const done = action.status === "DONE";
  const days = daysUntil(action.dueDate);
  const meta = [action.owner, action.dueDate ? `기한 ${action.dueDate}${days !== null && !done ? ` (${dDayLabel(days)})` : ""}` : null]
    .filter(Boolean)
    .join(", ");

  return (
    <div className="flex items-center gap-4 px-5 py-4 animate-fade-in">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          {done ? (
            <StatusBadge tone="low">이행 완료 {formatDateTime(action.completedAt)}</StatusBadge>
          ) : (
            <StatusBadge tone="pending">조치 등록, 이행 대기</StatusBadge>
          )}
          <span className="text-xs text-slate-500">{meta}</span>
        </div>
        <p className="mt-1.5 text-stage font-semibold text-slate-900">{action.content}</p>
        {action.guideRef && <p className="mt-0.5 text-xs text-slate-500">근거 KOSHA GUIDE {action.guideRef}</p>}
      </div>
      {done ? (
        action.equipmentId !== null && (
          <LinkButton href={`/equipment/${action.equipmentId}`} className="animate-fade-in">이 설비 타임라인에 기록됨, 보기</LinkButton>
        )
      ) : (
        <Button loading={busy} onClick={onComplete}>이행 완료</Button>
      )}
    </div>
  );
}
