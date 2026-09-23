import { useState } from "react";

import Button from "@/components/ui/Button";
import Textarea from "@/components/ui/Textarea";

interface Props { disabled: boolean; onSend: (message: string) => void }

/** 입력창. Enter 전송, Shift+Enter 줄바꿈. */
export default function Composer({ disabled, onSend }: Props) {
  const [input, setInput] = useState("");
  const send = () => { const m = input.trim(); if (!m || disabled) return; onSend(m); setInput(""); };
  return (
    <div className="flex items-end gap-2">
      <Textarea rows={2} placeholder="작업 내용을 입력하세요" value={input} onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }} />
      <Button loading={disabled} disabled={!input.trim()} onClick={send}>보내기</Button>
    </div>
  );
}
