import { useCallback, useEffect, useRef, useState } from "react";
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
 *
 * 대화 ID는 sessionStorage에 남긴다. 새로고침 한 번에 맥락이 사라지면
 * 모델이 이미 아는 것을 다시 묻고, 이어지는 extractWorkPlan이 새 대화 ID로
 * 초안을 하나 더 만들어 계획서가 쪼개진다 (QA 실측, 2026-09-21).
 */
const CONVERSATION_KEY = "saife.conversationId";

function readStoredConversationId(): string | null {
  try {
    return sessionStorage.getItem(CONVERSATION_KEY);
  } catch {
    // 사생활 보호 모드 등에서 접근이 막힐 수 있다. 막히면 그냥 새 대화로 간다
    return null;
  }
}

function storeConversationId(id: string | null): void {
  try {
    if (id === null) sessionStorage.removeItem(CONVERSATION_KEY);
    else sessionStorage.setItem(CONVERSATION_KEY, id);
  } catch {
    // 무시 — 저장 실패가 대화를 막으면 안 된다
  }
}
/** 화면에 그리는 대화 한 줄 */
export interface Turn {
  role: "user" | "assistant";
  text: string;
}

export function useAgentStream() {
  const [trace, setTrace] = useState<ToolTraceRow[]>([]);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [pendingSlot, setPendingSlot] = useState<SlotRequestPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);

  const conversationIdRef = useRef<string | null>(readStoredConversationId());
  const [restoring, setRestoring] = useState(false);
  const lastSeqRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const handleEvent = useCallback((env: SseEnvelope) => {
    // 재개 시 seq가 이어지므로 역행분만 중복으로 버린다
    if (env.seq <= lastSeqRef.current) return;
    lastSeqRef.current = env.seq;
    if (conversationIdRef.current !== env.correlationId) {
      conversationIdRef.current = env.correlationId;
      storeConversationId(env.correlationId);
    }

    switch (env.type) {
      case "ai.token": {
        const chunk = String(env.payload ?? "");
        // 이 턴의 assistant 줄에 이어 붙인다. 없으면 새로 만든다
        setTurns((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === "assistant") {
            return [...prev.slice(0, -1), { role: "assistant", text: last.text + chunk }];
          }
          return [...prev, { role: "assistant", text: chunk }];
        });
        break;
      }

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

      // 사용자가 무엇을 말했는지 화면에 남긴다. 심사위원이 입력과 응답을
      // 나란히 봐야 "AI가 무엇을 받아 무엇을 했는지"가 보인다
      setTurns((prev) => [...prev, { role: "user", text: message }]);
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

  /**
   * 저장된 대화 기록을 화면에 되살린다.
   *
   * 트레이스는 복원하지 않는다. 도구 호출은 그 턴의 사건이고,
   * 지난 턴의 트레이스를 다시 그리면 방금 일어난 일처럼 보인다.
   */
  useEffect(() => {
    const id = conversationIdRef.current;
    if (id === null) return;

    setRestoring(true);
    void fetch(`/api/agent/${id}/transcript`)
      .then((res) => (res.ok ? res.json() : []))
      .then((lines: { role: string; text: string }[]) => {
        setTurns(
          lines
            .filter((l) => l.role === "user" || l.role === "assistant")
            .map((l) => ({ role: l.role as Turn["role"], text: l.text })),
        );
      })
      .catch(() => {
        // 복원 실패는 대화를 막지 않는다. 새 대화처럼 이어간다
      })
      .finally(() => setRestoring(false));
  }, []);

  /** 새 대화 시작 */
  const reset = useCallback(() => {
    conversationIdRef.current = null;
    storeConversationId(null);
    setTrace([]);
    setTurns([]);
    setPendingSlot(null);
    setError(null);
  }, []);

  return { trace, turns, pendingSlot, error, streaming, restoring, send, answerSlot, reset };
}
