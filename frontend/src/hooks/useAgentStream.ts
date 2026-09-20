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
 * 되묻기는 특별한 흐름이 아니라 평범한 멀티턴이다. 모델이 질문하고 턴이 끝나면
 * 사용자가 답을 보내고, 같은 conversationId로 다음 턴이 이어진다.
 * `ai.slot.request`는 전용 입력 위젯을 그리기 위한 UI 힌트일 뿐이고,
 * 놓쳐도 자유 입력으로 진행된다.
 *
 * seq는 턴마다 새로 시작하므로 턴 경계에서 초기화한다.
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

  const send = useCallback(
    async (message: string, slotKey?: string) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setError(null);
      setPendingSlot(null);
      lastSeqRef.current = 0;
      setStreaming(true);

      // 새 대화면 화면을 비우고, 이어가는 턴이면 트레이스만 초기화한다
      if (!conversationIdRef.current) {
        setAnswer("");
      }
      setTrace([]);

      try {
        const res = await fetch("/api/agent/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            message,
            conversationId: conversationIdRef.current,
            slotKey: slotKey ?? null,
          }),
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

  /** 되묻기 답변 — 같은 엔드포인트로 보낸다. 별도 재개 API가 없다 */
  const answerSlot = useCallback(
    (slotKey: string, value: string) => send(value, slotKey),
    [send],
  );

  /** 새 대화 시작 */
  const reset = useCallback(() => {
    conversationIdRef.current = null;
    setTrace([]);
    setAnswer("");
    setPendingSlot(null);
    setError(null);
  }, []);

  return { trace, answer, pendingSlot, error, streaming, send, answerSlot, reset };
}
