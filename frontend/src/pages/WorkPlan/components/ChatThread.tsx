import { useEffect, useRef } from "react";

import AgentMessage from "@/components/common/AgentMessage";
import type { Turn } from "@/pages/WorkPlan/hooks/useAgentStream";

interface Props { turns: Turn[]; streaming: boolean; restoring: boolean }

/** 대화 스레드. 새 답변이 오면 아래로 따라간다(발표자가 이전 턴을 보고 있게 되는 것을 막는다). */
export default function ChatThread({ turns, streaming, restoring }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { const el = ref.current; if (el) el.scrollTop = el.scrollHeight; }, [turns]);
  const waiting = streaming && turns[turns.length - 1]?.role === "user";
  return (
    <div ref={ref} className="max-h-[60vh] min-h-[320px] flex-1 space-y-3 overflow-auto rounded-lg border border-slate-200 bg-white p-4 shadow-card">
      {turns.length === 0 && (
        <p className="text-sm text-slate-400">
          {restoring ? "이전 대화를 불러오는 중…" : "예: 내일 공장동 후면 차양부에서 사다리 놓고 천장 페인트 칠할 건데요"}
        </p>
      )}
      {turns.map((turn, i) => turn.role === "user" ? (
        <div key={i} className="flex justify-end">
          <p className="max-w-[80%] rounded-lg bg-slate-900 px-3 py-2 text-sm text-white">{turn.text}</p>
        </div>
      ) : (
        <div key={i} className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm"><AgentMessage text={turn.text} /></div>
      ))}
      {waiting && <p className="text-sm text-slate-400 animate-cursor-blink">확인하고 있습니다…</p>}
    </div>
  );
}
