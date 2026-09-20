import { useCallback, useRef, useState } from "react";
import type {
  AiErrorPayload,
  SlotRequestPayload,
  SseEnvelope,
  ToolDonePayload,
  ToolStartPayload,
  ToolTraceRow,
} from "@/types/sse";

/**
 * 에이전트 스트림 구독 훅.
 *
 * EventSource를 쓰지 않는 이유: POST로 대화를 시작해야 하고 헤더(JWT)가 필요하다.
 * fetch + ReadableStream으로 SSE를 직접 파싱한다.
 *
 * seq는 재개 후에도 이어서 증가한다(백엔드 conversation_state.last_seq).
 * 순서 보장을 위해 seq 기준으로 정렬하지 말고 도착 순서대로 처리하되,
 * 역행하는 seq는 중복으로 간주해 버린다.
 */
export function useAgentStream() {
  const [trace, setTrace] = useState<ToolTraceRow[]>([]);
  const [answer, setAnswer] = useState("");
  const [pendingSlot, setPendingSlot] = useState<SlotRequestPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);

  const conversationIdRef = useRef<string | null>(null);
  const lastSeqRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const handleEvent = useCallback((env: SseEnvelope) => {
    // 재개 시 seq가 이어지므로 역행분만 중복으로 버린다
    if (env.seq <= lastSeqRef.current) return;
    lastSeqRef.current = env.seq;
    conversationIdRef.current = env.correlationId;

    switch (env.type) {
      case "ai.token":
        setAnswer((prev) => prev + String(env.payload ?? ""));
        break;

      case "ai.tool.start": {
        const p = env.payload as ToolStartPayload;
        setTrace((prev) => [
          ...prev,
          { callOrder: p.callOrder, toolName: p.toolName, params: p.params, status: "running" },
        ]);
        break;
      }

      case "ai.tool.done": {
        const p = env.payload as ToolDonePayload;
        setTrace((prev) =>
          prev.map((row) =>
            row.callOrder === p.callOrder
              ? {
                  ...row,
                  status: p.success ? "ok" : "failed",
                  durationMs: p.durationMs,
                  errorMessage: p.errorMessage,
                }
              : row,
          ),
        );
        break;
      }

      case "ai.slot.request":
        // 스트림이 여기서 멈춘다. 답변은 별도 POST로 보낸다.
        setPendingSlot(env.payload as SlotRequestPayload);
        setStreaming(false);
        break;

      case "ai.error":
        setError((env.payload as AiErrorPayload)?.message ?? "알 수 없는 오류");
        setStreaming(false);
        break;

      case "ai.done":
        setStreaming(false);
        break;

      case "system.heartbeat":
        break;

      default:
        break;
    }
  }, []);

  /** SSE 텍스트 스트림을 읽어 이벤트 단위로 넘긴다 */
  const consume = useCallback(
    async (res: Response) => {
      if (!res.body) throw new Error("응답 본문이 없습니다");
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // SSE는 빈 줄로 이벤트를 구분한다
        const chunks = buffer.split("\n\n");
        buffer = chunks.pop() ?? "";

        for (const chunk of chunks) {
          const dataLine = chunk.split("\n").find((l) => l.startsWith("data:"));
          if (!dataLine) continue;
          try {
            handleEvent(JSON.parse(dataLine.slice(5).trim()) as SseEnvelope);
          } catch {
            // 파싱 실패한 청크 하나가 스트림 전체를 죽이지 않게 한다
          }
        }
      }
    },
    [handleEvent],
  );

  const start = useCallback(
    async (message: string) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setTrace([]);
      setAnswer("");
      setError(null);
      setPendingSlot(null);
      lastSeqRef.current = 0;
      setStreaming(true);

      try {
        const res = await fetch("/api/agent/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ message }),
          signal: controller.signal,
        });
        await consume(res);
      } catch (e) {
        if ((e as Error).name !== "AbortError") {
          setError((e as Error).message);
        }
        setStreaming(false);
      }
    },
    [consume],
  );

  /** 되묻기 턴 답변 — 중단된 턴을 재개한다 */
  const answerSlot = useCallback(
    async (slotKey: string, value: string) => {
      const cid = conversationIdRef.current;
      if (!cid) return;

      setPendingSlot(null);
      setStreaming(true);

      try {
        const res = await fetch(`/api/agent/${cid}/slot`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ slotKey, value }),
        });
        await consume(res);
      } catch (e) {
        setError((e as Error).message);
        setStreaming(false);
      }
    },
    [consume],
  );

  return { trace, answer, pendingSlot, error, streaming, start, answerSlot };
}
