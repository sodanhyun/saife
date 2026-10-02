import { useState } from "react";

import Button from "@/components/ui/Button";
import cn from "@/lib/cn";

interface Props { disabled: boolean; onSend: (message: string) => void }

/** 입력창. Enter 전송, Shift+Enter 줄바꿈. */
export default function Composer({ disabled, onSend }: Props) {
  const [input, setInput] = useState("");
  const send = () => { const m = input.trim(); if (!m || disabled) return; onSend(m); setInput(""); };
  return (
    <div className={cn("flex items-end gap-3 rounded-xl border bg-white p-3 shadow-card transition-colors", disabled ? "border-slate-200" : "border-slate-300 focus-within:border-brand-line focus-within:ring-2 focus-within:ring-brand-soft")}>
      <textarea rows={2} aria-label="작업 내용" placeholder="작업 내용을 입력하세요" value={input} onChange={(e) => setInput(e.target.value)}
        className="min-h-[52px] flex-1 resize-none border-0 bg-transparent px-1 py-1 text-stage text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-0"
        onKeyDown={(e) => {
          // 한글 IME 조합 중 Enter는 조합 확정 키다. 여기서 전송하면 마지막 음절이 잘리거나 두 번 전송된다.
          if (e.nativeEvent.isComposing) return;
          if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
        }} />
      <Button loading={disabled} disabled={!input.trim()} onClick={send}>보내기</Button>
    </div>
  );
}
