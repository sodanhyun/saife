import { useState } from "react";

import Button from "@/components/ui/Button";
import Input from "@/components/ui/Input";
import SegmentedControl from "@/components/ui/SegmentedControl";
import { SLOT_LABEL, SLOT_REASON } from "@/pages/WorkPlan/utils/flowStages";
import type { SlotRequestPayload } from "@/types/sse";

interface Props { slot: SlotRequestPayload; disabled: boolean; onAnswer: (slotKey: string, value: string) => void }

/** 되묻기 카드. 무엇을, 왜 묻는지 같이 보인다. options가 있으면 세그먼트, 없으면 입력. ledgerValue가 있으면 "확인" 성격이라 미리 채운다. */
export default function SlotPrompt({ slot, disabled, onAnswer }: Props) {
  const [value, setValue] = useState(slot.ledgerValue ?? "");
  const submit = () => { if (value.trim()) onAnswer(slot.slotKey, value.trim()); };
  const reason = SLOT_REASON[slot.slotKey];
  return (
    <div role="note" className="rounded-xl border border-pending-border bg-pending-bg px-5 py-4 animate-rise-in">
      <div className="flex flex-wrap items-baseline gap-2">
        <span className="text-xs font-bold tracking-wide text-pending-text">등급을 바꾸는 값을 묻습니다</span>
        <span className="text-stage font-semibold text-slate-900">{SLOT_LABEL[slot.slotKey] ?? slot.question}</span>
      </div>
      {reason && <p className="mt-1 text-sm text-slate-600">{reason}</p>}
      {slot.ledgerValue && <p className="mt-1 text-xs text-slate-500">설비 대장 기록: {slot.ledgerValue}</p>}
      <div className="mt-3 flex items-center gap-2">
        {slot.options && slot.options.length > 0 ? (
          <SegmentedControl ariaLabel={slot.question} size="sm" value={value || null}
            options={slot.options.map((o) => ({ value: o, label: o }))} onChange={setValue} className="flex-1" />
        ) : (
          <Input value={value} onChange={(e) => setValue(e.target.value)} className="bg-white"
            onKeyDown={(e) => {
              // 한글 IME 조합 중 Enter는 조합 확정 키다. 여기서 제출하면 마지막 음절이 잘리거나 두 번 제출된다.
              if (e.nativeEvent.isComposing) return;
              if (e.key === "Enter" && !disabled) submit();
            }} placeholder="답변을 입력하세요" />
        )}
        <Button size="sm" disabled={disabled || !value.trim()} onClick={submit}>답변</Button>
      </div>
    </div>
  );
}
