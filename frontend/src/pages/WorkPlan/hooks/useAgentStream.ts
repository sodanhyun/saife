// 에이전트 대화 도메인 훅 — useSSEStream 위에 이벤트 핸들러만 얹는다.
// 되묻기는 평범한 멀티턴이다. 모델이 질문하며 턴을 끝내는 것 자체가 일시정지다.
// 대화 ID는 sessionStorage에 남긴다(새로고침 한 번에 맥락이 사라지면 계획서가 쪼개진다 — QA 2026-09-21).
import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "@/api/client";
import { agentTranscript, AGENT_CHAT } from "@/api/endpoints";
import { useSSEStream } from "@/hooks/useSSEStream";
import type { AiErrorPayload, SlotRequestPayload, SseEnvelope, ToolDonePayload, ToolStartPayload, ToolTraceRow } from "@/types/sse";

const CONVERSATION_KEY = "saife.conversationId";

function readStoredConversationId(): string | null {
  try { return sessionStorage.getItem(CONVERSATION_KEY); } catch { return null; }
}
function storeConversationId(id: string | null): void {
  try {
    if (id === null) sessionStorage.removeItem(CONVERSATION_KEY);
    else sessionStorage.setItem(CONVERSATION_KEY, id);
  } catch { /* 저장 실패가 대화를 막으면 안 된다 */ }
}

export interface Turn { role: "user" | "assistant"; text: string }

interface AgentEventMap {
  "ai.token": string;
  "ai.tool.start": ToolStartPayload;
  "ai.tool.done": ToolDonePayload;
  "ai.slot.request": SlotRequestPayload;
  "ai.error": AiErrorPayload;
  "ai.done": null;
}

export function useAgentStream() {
  const [trace, setTrace] = useState<ToolTraceRow[]>([]);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [pendingSlot, setPendingSlot] = useState<SlotRequestPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [restoring, setRestoring] = useState(() => readStoredConversationId() !== null);

  const conversationIdRef = useRef<string | null>(readStoredConversationId());
  const lastSeqRef = useRef(0);
  // send가 한 번이라도 불리면 복원 결과로 turns를 덮지 않는다
  const sentRef = useRef(false);
  // 턴 세대 번호 — send가 겹치면(앞 턴이 밀려나 aborted로 끝나면) 뒤늦게 끝난 앞 턴이
  // 새 턴의 streaming·error를 덮지 않게, 가장 최근 턴만 마무리 상태를 반영한다.
  const turnRef = useRef(0);

  /** 봉투 공통 처리 — 역행 seq 폐기 + 대화 ID 동기화. true면 계속 처리. */
  const accept = useCallback((env: SseEnvelope<unknown>) => {
    if (env.seq <= lastSeqRef.current) return false;
    lastSeqRef.current = env.seq;
    if (conversationIdRef.current !== env.correlationId) {
      conversationIdRef.current = env.correlationId;
      storeConversationId(env.correlationId);
    }
    return true;
  }, []);

  const { connectionState, start, abort } = useSSEStream<AgentEventMap>({
    url: AGENT_CHAT,
    method: "POST",
    handlers: {
      "ai.token": (chunk, env) => {
        if (!accept(env)) return;
        const text = String(chunk ?? "");
        setTurns((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === "assistant") return [...prev.slice(0, -1), { role: "assistant", text: last.text + text }];
          return [...prev, { role: "assistant", text }];
        });
      },
      "ai.tool.start": (p, env) => {
        if (!accept(env)) return;
        setTrace((prev) => [...prev, { callOrder: p.callOrder, toolName: p.toolName, params: p.params, status: "running" }]);
      },
      "ai.tool.done": (p, env) => {
        if (!accept(env)) return;
        setTrace((prev) => prev.map((row) => (row.callOrder === p.callOrder
          ? { ...row, status: p.success ? "ok" : "failed", durationMs: p.durationMs, errorMessage: p.errorMessage }
          : row)));
      },
      "ai.slot.request": (p, env) => { if (accept(env)) setPendingSlot(p); },
      "ai.error": (p, env) => { if (accept(env)) setError(p?.message ?? "알 수 없는 오류"); },
      "ai.done": (_p, env) => { accept(env); },
    },
  });

  const send = useCallback(async (message: string, slotKey?: string) => {
    sentRef.current = true;
    const myTurn = ++turnRef.current;
    setError(null);
    setPendingSlot(null);
    lastSeqRef.current = 0; // seq는 턴마다 새로 시작한다
    setStreaming(true);
    setTurns((prev) => [...prev, { role: "user", text: message }]);
    setTrace([]);
    const outcome = await start({ message, conversationId: conversationIdRef.current, slotKey: slotKey ?? null });
    if (myTurn !== turnRef.current) return; // 더 새 턴(또는 reset)이 이미 상태를 가져갔다
    if (outcome.reason === "error") setError(outcome.error?.message ?? "스트림 오류");
    setStreaming(false);
  }, [start]);

  /** 되묻기 답변 — 같은 엔드포인트. 별도 재개 API가 없다 */
  const answerSlot = useCallback((slotKey: string, value: string) => send(value, slotKey), [send]);

  // 저장된 대화 복원 — 트레이스는 복원하지 않는다(지난 턴의 사건이라 방금 일어난 것처럼 보인다)
  useEffect(() => {
    const id = conversationIdRef.current;
    if (id === null) return;
    let cancelled = false;
    api.get<{ role: string; text: string }[]>(agentTranscript(id))
      .then((res) => {
        if (cancelled || sentRef.current) return;
        setTurns(res.data.filter((l) => l.role === "user" || l.role === "assistant").map((l) => ({ role: l.role as Turn["role"], text: l.text })));
      })
      .catch(() => { /* 복원 실패는 새 대화처럼 이어간다 */ })
      .finally(() => { if (!cancelled) setRestoring(false); });
    return () => { cancelled = true; };
  }, []);

  const reset = useCallback(() => {
    turnRef.current += 1; // 진행 중이던 send의 뒤늦은 마무리가 비운 상태를 건드리지 않게 한다
    abort();
    conversationIdRef.current = null;
    storeConversationId(null);
    // 복원 중이던 옛 대화가 reset 이후 도착해 turns를 덮어쓰지 않게 한다(sentRef 가드 재사용)
    sentRef.current = true;
    setRestoring(false);
    setTrace([]); setTurns([]); setPendingSlot(null); setError(null); setStreaming(false);
  }, [abort]);

  return { trace, turns, pendingSlot, error, streaming, restoring, connectionState, send, answerSlot, reset };
}
