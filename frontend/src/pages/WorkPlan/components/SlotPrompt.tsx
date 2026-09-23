import { useState } from "react";

import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import Input from "@/components/ui/Input";
import SegmentedControl from "@/components/ui/SegmentedControl";
import type { SlotRequestPayload } from "@/types/sse";

interface Props { slot: SlotRequestPayload; disabled: boolean; onAnswer: (slotKey: string, value: string) => void }

/** 되묻기 카드 — options가 있으면 세그먼트, 없으면 입력. ledgerValue가 있으면 "확인" 성격이라 미리 채운다. */
export default function SlotPrompt({ slot, disabled, onAnswer }: Props) {
  const [value, setValue] = useState(slot.ledgerValue ?? "");
  const submit = () => { if (value.trim()) onAnswer(slot.slotKey, value.trim()); };
  return (
    <Callout tone="pending" title={slot.question}>
      {slot.ledgerValue && <p className="text-xs text-slate-500">설비 대장 기록: {slot.ledgerValue}</p>}
      <div className="mt-2 flex items-center gap-2">
        {slot.options && slot.options.length > 0 ? (
          <SegmentedControl ariaLabel={slot.question} size="sm" value={value || null}
            options={slot.options.map((o) => ({ value: o, label: o }))} onChange={setValue} className="flex-1" />
        ) : (
          <Input value={value} onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && !disabled) submit(); }} placeholder="답변을 입력하세요" />
        )}
        <Button size="sm" disabled={disabled || !value.trim()} onClick={submit}>답변</Button>
      </div>
    </Callout>
  );
}
