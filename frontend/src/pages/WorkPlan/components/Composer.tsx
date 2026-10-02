import { useState } from "react";

import Button from "@/components/ui/Button";
import cn from "@/lib/cn";
import { EXAMPLES } from "@/pages/WorkPlan/utils/examples";
import { SLOT_LABEL } from "@/pages/WorkPlan/utils/flowStages";
import type { SlotRequestPayload } from "@/types/sse";

interface Props {
  disabled: boolean;
  onSend: (message: string, slotKey?: string) => void;
  /** 확인이 필요한 항목. 있으면 입력창 위에 표시하고 답을 그 항목으로 보낸다 */
  slot?: SlotRequestPayload | null;
  /** 대화 시작 전: 예시 칩을 보인다 */
  showExamples?: boolean;
}

/** 입력창. Enter 전송, Shift+Enter 줄바꿈. */
export default function Composer({ disabled, onSend, slot = null, showExamples = false }: Props) {
  const [input, setInput] = useState("");
  const send = () => {
    const m = input.trim();
    if (!m || disabled) return;
    onSend(m, slot?.slotKey);
    setInput("");
  };
  const slotLabel = slot ? SLOT_LABEL[slot.slotKey] ?? slot.question : null;
  return (
    <div className="flex flex-col gap-2.5">
      <div className={cn("rounded-xl border bg-white shadow-card transition-colors", disabled ? "border-slate-200" : "border-slate-300 focus-within:border-brand-line focus-within:ring-2 focus-within:ring-brand-soft")}>
        {slotLabel && (
          <p className="border-b border-slate-100 px-4 pt-2.5 pb-2 text-sm font-semibold text-pending-text">확인 필요: {slotLabel}</p>
        )}
        <div className="flex items-end gap-3 p-3">
          <textarea rows={2} aria-label="작업 내용" value={input} onChange={(e) => setInput(e.target.value)}
            placeholder={slot ? "답변 입력" : "예: 내일 차양부 천장 도장, 사다리 사용, 김철수 반장 외 1명"}
            className="min-h-[52px] flex-1 resize-none border-0 bg-transparent px-1 py-1 text-stage text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-0"
            onKeyDown={(e) => {
              // 한글 IME 조합 중 Enter는 조합 확정 키다. 여기서 전송하면 마지막 음절이 잘리거나 두 번 전송된다.
              if (e.nativeEvent.isComposing) return;
              if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
            }} />
          <Button loading={disabled} disabled={!input.trim()} onClick={send}>보내기</Button>
        </div>
      </div>
      {showExamples && (
        <div className="flex flex-wrap gap-2">
          {EXAMPLES.map((ex) => (
            <button key={ex} type="button" onClick={() => setInput(ex)}
              className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600 transition-colors hover:border-slate-300 hover:text-slate-900">
              {ex}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
