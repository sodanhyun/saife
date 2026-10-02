// ActionPanel.tsx — 개선대책: 등록 폼(우선순위, 내용, 담당, 기한), 이행 대기, 이행 완료.
import { useState } from "react";

import { Badge, StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import DateInput from "@/components/ui/DateInput";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import LinkButton from "@/components/ui/LinkButton";
import SegmentedControl from "@/components/ui/SegmentedControl";
import Textarea from "@/components/ui/Textarea";
import type { ActionInput } from "@/pages/Vision/hooks/useVision";
import { basisLine } from "@/pages/Vision/utils/action";
import { daysUntil, defaultDueDate, dueLabel, shortDate, todayLabel } from "@/pages/Vision/utils/dates";
import { CONTROL_PRIORITIES, CONTROL_PRIORITY_LABEL, type ActionView, type ControlPriority, type SuggestedAction } from "@/types/action";

interface FormProps {
  suggested: SuggestedAction | null;
  busy: boolean;
  onSubmit: (input: ActionInput) => void;
  onCancel?: () => void;
}

export function ActionForm({ suggested, busy, onSubmit, onCancel }: FormProps) {
  const [priority, setPriority] = useState<ControlPriority | null>(suggested?.priority ?? null);
  const [content, setContent] = useState(suggested?.content ?? "");
  const [owner, setOwner] = useState("");
  const [dueDate, setDueDate] = useState(defaultDueDate);
  const basis = basisLine(suggested);

  return (
    <form
      aria-label="개선대책 등록"
      className="animate-rise-in space-y-3 px-5 py-4"
      onSubmit={(e) => { e.preventDefault(); if (content.trim()) onSubmit({ content, owner, dueDate, priority, guideRef: suggested?.guideRef ?? null }); }}
    >
      <div className="flex items-center gap-3">
        <h4 className="text-xs font-bold tracking-wide text-brand">개선대책</h4>
        <SegmentedControl<ControlPriority>
          ariaLabel="감소대책 우선순위"
          size="sm"
          className="ml-auto w-[22rem]"
          value={priority}
          onChange={setPriority}
          options={CONTROL_PRIORITIES.map((p) => ({ value: p, label: CONTROL_PRIORITY_LABEL[p] }))}
        />
      </div>
      <div>
        <Textarea aria-label="개선대책 내용" rows={2} value={content} placeholder="개선대책 입력" onChange={(e) => setContent(e.target.value)} className="text-stage" />
        {basis && <p className="mt-1 text-xs text-slate-500">{basis}</p>}
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-end gap-3">
        <FormField label="담당">
          <Input aria-label="담당" value={owner} placeholder="이름 또는 부서" onChange={(e) => setOwner(e.target.value)} />
        </FormField>
        <FormField label="기한">
          <DateInput type="date" aria-label="기한" value={dueDate} onChange={setDueDate} />
        </FormField>
        <div className="flex gap-2">
          {onCancel && <Button type="button" variant="ghost" disabled={busy} onClick={onCancel}>닫기</Button>}
          <Button type="submit" loading={busy} disabled={!content.trim()}>등록</Button>
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

/**
 * 등록된 개선대책. 완료면 완료일과 설비 이력 링크.
 *
 * <p>이행 완료는 등록과 다른 날, 다른 사람이 하는 별도 행동이다. 등록 직후 같은 자리에 주 버튼으로 두면
 * 무심코 눌러 "등록하자마자 이행"이 기록된다. 그래서 작은 보조 버튼 "이행 완료 기록"을 누르면
 * 완료일을 보여주는 확인 줄이 열리고, 거기서 한 번 더 눌러야 기록된다.
 */
export function ActionStatusRow({ action, busy, onComplete }: StatusProps) {
  const [confirming, setConfirming] = useState(false);
  const done = action.status === "DONE";
  const days = daysUntil(action.dueDate);
  const overdue = !done && days !== null && days < 0;

  return (
    <div className="animate-fade-in">
      <div className="flex items-center gap-4 px-5 py-4">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            {done ? (
              <StatusBadge tone="low">이행 완료 {shortDate(action.completedAt)}</StatusBadge>
            ) : (
              <StatusBadge tone={overdue ? "high" : "pending"}>{overdue ? dueLabel(days) : "이행 대기"}</StatusBadge>
            )}
            {action.priority && <Badge>{CONTROL_PRIORITY_LABEL[action.priority]}</Badge>}
            <span className="text-xs text-slate-500">
              {[action.owner, action.dueDate ? `기한 ${shortDate(action.dueDate)}${!done && days !== null && days >= 0 ? ` (${dueLabel(days)})` : ""}` : null].filter(Boolean).join(", ")}
            </span>
          </div>
          <p className="mt-1.5 text-stage font-semibold text-slate-900">{action.content}</p>
        </div>
        {done ? (
          action.equipmentId !== null && <LinkButton href={`/equipment/${action.equipmentId}`} className="animate-fade-in">설비 이력</LinkButton>
        ) : (
          !confirming && <Button variant="subtle" size="sm" onClick={() => setConfirming(true)}>이행 완료 기록</Button>
        )}
      </div>
      {!done && confirming && (
        <div role="group" aria-label="이행 완료 확인" className="flex items-center gap-3 border-t border-slate-100 bg-slate-50 px-5 py-3 animate-fade-in">
          <span className="text-sm text-slate-700">완료일 <span className="font-semibold tabular-nums text-slate-900">{todayLabel()}</span></span>
          <div className="ml-auto flex gap-2">
            <Button variant="ghost" size="sm" disabled={busy} onClick={() => setConfirming(false)}>취소</Button>
            <Button size="sm" loading={busy} onClick={onComplete}>이행 완료</Button>
          </div>
        </div>
      )}
    </div>
  );
}
