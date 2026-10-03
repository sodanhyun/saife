import { useState } from "react";

import Button from "@/components/ui/Button";
import cn from "@/lib/cn";
import { SLOT_LABEL } from "@/pages/WorkPlan/utils/flowStages";
import type { SlotRequestPayload } from "@/types/sse";

interface Props {
  disabled: boolean;
  onSend: (message: string, slotKey?: string) => void;
  /** 확인이 필요한 항목. 있으면 입력창 위에 표시하고 답을 그 항목으로 보낸다 */
  slot?: SlotRequestPayload | null;
  /** 대화 시작 전: 새 점검표 머리와 예시 자리표시를 보인다 */
  showExamples?: boolean;
}

const EXAMPLE_PLACEHOLDER = "작업 내용, 장소, 사용 설비, 작업자 (예: 오늘 오전 차양부 천장 도장, 이동식 사다리, 유성 페인트, 김철수 반장 외 1명)";

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
        {showExamples && !slotLabel && (
          <p className="border-b border-slate-100 px-4 pt-3 pb-2.5 text-sm font-semibold text-slate-900">새 점검표</p>
        )}
        {slotLabel && (
          <p className="border-b border-slate-100 px-4 pt-2.5 pb-2 text-sm font-semibold text-pending-text">확인 필요: {slotLabel}</p>
        )}
        <div className="flex items-end gap-3 p-3">
          <textarea rows={2} aria-label="작업 내용" value={input} onChange={(e) => setInput(e.target.value)}
            placeholder={slot ? "답변 입력" : showExamples ? EXAMPLE_PLACEHOLDER : "작업 내용 입력"}
            className="min-h-[52px] flex-1 resize-none border-0 bg-transparent px-1 py-1 text-stage text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-0"
            onKeyDown={(e) => {
              // 한글 IME 조합 중 Enter는 조합 확정 키다. 여기서 전송하면 마지막 음절이 잘리거나 두 번 전송된다.
              if (e.nativeEvent.isComposing) return;
              if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
            }} />
          <Button loading={disabled} disabled={!input.trim()} onClick={send}>{showExamples ? "작성" : "입력"}</Button>
        </div>
      </div>
    </div>
  );
}
