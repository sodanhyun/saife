import { useState } from "react";
import { useAgentStream } from "@/hooks/useAgentStream";
import { ToolTracePanel } from "@/components/ToolTracePanel";

/**
 * 스캐폴드 화면. UC3(작업계획서 대화형 등록) 뼈대만 올려두었다.
 * 좌측 대화 / 우측 트레이스 패널 구성이 시연 레이아웃이다.
 */
export default function App() {
  const [input, setInput] = useState("");
  const { trace, answer, pendingSlot, error, streaming, start, answerSlot } = useAgentStream();
  const [slotValue, setSlotValue] = useState("");

  return (
    <div className="grid h-screen grid-cols-[1fr_420px] bg-slate-50">
      <section className="flex flex-col p-6">
        <h1 className="text-xl font-semibold">SAIFE — 작업계획서 등록</h1>

        <div className="mt-4 flex-1 overflow-auto whitespace-pre-wrap rounded border bg-white p-4">
          {answer || <span className="text-slate-400">작업 내용을 말씀해 주세요.</span>}
        </div>

        {pendingSlot && (
          <div className="mt-3 rounded border border-amber-300 bg-amber-50 p-4">
            <p className="font-medium">{pendingSlot.question}</p>
            {pendingSlot.ledgerValue && (
              <p className="mt-1 text-sm text-slate-600">
                기록상 값: {pendingSlot.ledgerValue}
              </p>
            )}
            <div className="mt-2 flex gap-2">
              <input
                className="flex-1 rounded border px-3 py-2"
                value={slotValue}
                onChange={(e) => setSlotValue(e.target.value)}
              />
              <button
                className="rounded bg-slate-900 px-4 py-2 text-white"
                onClick={() => {
                  answerSlot(pendingSlot.slotKey, slotValue);
                  setSlotValue("");
                }}
              >
                답변
              </button>
            </div>
          </div>
        )}

        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

        <div className="mt-3 flex gap-2">
          <input
            className="flex-1 rounded border px-3 py-2"
            placeholder="내일 사다리 놓고 천장 페인트 칠할 건데요"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && !streaming && start(input)}
          />
          <button
            className="rounded bg-slate-900 px-5 py-2 text-white disabled:opacity-40"
            disabled={streaming}
            onClick={() => start(input)}
          >
            보내기
          </button>
        </div>
      </section>

      <ToolTracePanel rows={trace} />
    </div>
  );
}
