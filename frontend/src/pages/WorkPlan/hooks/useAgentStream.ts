// 에이전트 대화 도메인 훅 — useSSEStream 위에 이벤트 핸들러만 얹는다.
// 되묻기는 평범한 멀티턴이다. 모델이 질문하며 턴을 끝내는 것 자체가 일시정지다.
// 대화 ID는 sessionStorage에 남긴다(새로고침 한 번에 맥락이 사라지면 계획서가 쪼개진다 — QA 2026-09-21).
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { api } from "@/api/client";
import { agentTranscript, AGENT_CHAT } from "@/api/endpoints";
import { useSSEStream } from "@/hooks/useSSEStream";
import type { Evidence } from "@/types/evidence";
import type { AiErrorPayload, EvidencePayload, RecallPayload, SlotRequestPayload, SseEnvelope, ToolDonePayload, ToolStartPayload, ToolTraceRow } from "@/types/sse";

const CONVERSATION_KEY = "saife.conversationId";
// F4: 저장된 대화가 어느 설비로 시작됐는지 같이 남긴다. 진입 설비(?equipmentId=)가 이 값과
// 다르면(또는 저장된 대화엔 설비가 없었으면) 그 대화를 이어가지 않는다 — 안 그러면 새 설비
// 카드로 들어와도 이전 설비 대화가 이어져 equipmentId가 안 실리고 ai.recall도 안 뜬다.
const CONVERSATION_EQUIPMENT_KEY = "saife.conversationEquipmentId";

function readStoredConversationId(): string | null {
  try { return sessionStorage.getItem(CONVERSATION_KEY); } catch { return null; }
}
function storeConversationId(id: string | null): void {
  try {
    if (id === null) sessionStorage.removeItem(CONVERSATION_KEY);
    else sessionStorage.setItem(CONVERSATION_KEY, id);
  } catch { /* 저장 실패가 대화를 막으면 안 된다 */ }
}

function readStoredEquipmentId(): number | null {
  try {
    const raw = sessionStorage.getItem(CONVERSATION_EQUIPMENT_KEY);
    const n = raw === null ? NaN : Number(raw);
    return Number.isFinite(n) ? n : null;
  } catch { return null; }
}
function storeEquipmentId(id: number | null): void {
  try {
    if (id === null) sessionStorage.removeItem(CONVERSATION_EQUIPMENT_KEY);
    else sessionStorage.setItem(CONVERSATION_EQUIPMENT_KEY, String(id));
  } catch { /* 저장 실패가 대화를 막으면 안 된다 */ }
}

/**
 * 저장된 대화를 복원할지 결정한다. 진입 설비가 있는데 저장된 대화의 설비와 다르면(또는
 * 저장된 대화에 설비가 없었으면) 그 대화를 버리고 새 대화로 시작한다 — 첫 턴에 새
 * equipmentId가 실려야 findLocationEquipment가 바로 확정 매칭하고 ai.recall이 뜬다.
 * 진입 설비가 없으면(주소창 직접 진입 등) 저장된 대화를 그대로 둔다.
 */
function resolveInitialConversationId(entryEquipmentId: number | null): string | null {
  const stored = readStoredConversationId();
  if (stored === null) return null;
  if (entryEquipmentId !== null && readStoredEquipmentId() !== entryEquipmentId) {
    storeConversationId(null);
    storeEquipmentId(null);
    return null;
  }
  return stored;
}

export interface Turn { role: "user" | "assistant"; text: string; evidence: Evidence[] }

/** 근거 no로 중복 제거 — 먼저 온 것을 유지한다 */
function dedupeByNo(items: Evidence[]): Evidence[] {
  const seen = new Map<number, Evidence>();
  for (const e of items) if (!seen.has(e.no)) seen.set(e.no, e);
  return [...seen.values()];
}

interface AgentEventMap {
  "ai.token": string;
  "ai.tool.start": ToolStartPayload;
  "ai.tool.done": ToolDonePayload;
  "ai.slot.request": SlotRequestPayload;
  "ai.recall": RecallPayload;
  "ai.evidence": EvidencePayload;
  "ai.error": AiErrorPayload;
  "ai.done": null;
}

/**
 * @param entryEquipmentId 진입 컨텍스트(`?equipmentId=`)의 설비 ID. useEntryEquipment가 이미
 * URL에서 동기적으로 뽑아 두므로 마운트 첫 렌더부터 쓸 수 있다(F4). 텍스트만으로 시작하는
 * 진입(주소창 직접 진입 등)은 null을 넘기면 기존 유사도 매칭 경로가 그대로 유지된다.
 */
export function useAgentStream(entryEquipmentId: number | null = null) {
  const [trace, setTrace] = useState<ToolTraceRow[]>([]);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [pendingSlot, setPendingSlot] = useState<SlotRequestPayload | null>(null);
  // 회상 카드 — 항상 마지막 한 장만 보관한다(같은 설비든 다른 설비든 새 ai.recall이 통째로 교체).
  const [recall, setRecall] = useState<RecallPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);
  // 최초 렌더에서 한 번만 계산한다(lazy initializer) — 진입 설비와 다른 저장된 대화는
  // 여기서 버려지므로, 이후 재렌더마다 다시 판정하며 sessionStorage를 건드리면 안 된다.
  const [initialConversationId] = useState(() => resolveInitialConversationId(entryEquipmentId));
  const [restoring, setRestoring] = useState(() => initialConversationId !== null);

  const conversationIdRef = useRef<string | null>(initialConversationId);
  const lastSeqRef = useRef(0);
  // send가 한 번이라도 불리면 복원 결과로 turns를 덮지 않는다
  const sentRef = useRef(false);
  // 턴 세대 번호 — send가 겹치면(앞 턴이 밀려나 aborted로 끝나면) 뒤늦게 끝난 앞 턴이
  // 새 턴의 streaming·error를 덮지 않게, 가장 최근 턴만 마무리 상태를 반영한다.
  const turnRef = useRef(0);
  // ai.evidence가 ai.token보다 먼저 오면(정상 순서) 잠시 여기 쌓아뒀다가 첫 토큰이
  // assistant 턴을 만들 때 옮겨 붙인다. 토큰이 아예 없으면 ai.done에서 빈 턴을 만들어 붙인다.
  const pendingEvidenceRef = useRef<Evidence[]>([]);
  // 이번 턴에 assistant 턴이 이미 생겼는지 — 생겼으면 뒤이어 오는 ai.evidence를
  // pendingEvidenceRef가 아니라 그 턴에 직접(중복 제거하며) 이어붙인다.
  const assistantStartedRef = useRef(false);

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
          if (last && last.role === "assistant") return [...prev.slice(0, -1), { ...last, text: last.text + text }];
          const evidence = pendingEvidenceRef.current;
          pendingEvidenceRef.current = [];
          return [...prev, { role: "assistant", text, evidence }];
        });
        assistantStartedRef.current = true;
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
      "ai.recall": (p, env) => { if (accept(env)) setRecall(p); },
      "ai.evidence": (items, env) => {
        if (!accept(env)) return;
        const incoming = items ?? [];
        if (assistantStartedRef.current) {
          // assistant 턴이 이미 있다 — 그 턴에만 이어붙인다(이전 턴은 절대 건드리지 않는다)
          setTurns((prev) => {
            const last = prev[prev.length - 1];
            if (!last || last.role !== "assistant") return prev;
            return [...prev.slice(0, -1), { ...last, evidence: dedupeByNo([...last.evidence, ...incoming]) }];
          });
        } else {
          pendingEvidenceRef.current = dedupeByNo([...pendingEvidenceRef.current, ...incoming]);
        }
      },
      "ai.error": (p, env) => { if (accept(env)) setError(p?.message ?? "알 수 없는 오류"); },
      "ai.done": (_p, env) => {
        if (!accept(env)) return;
        // 토큰 없이 근거만 온 경우(빈 답변) — 빈 텍스트 assistant 턴을 만들어 근거를 붙인다
        if (!assistantStartedRef.current && pendingEvidenceRef.current.length > 0) {
          const evidence = pendingEvidenceRef.current;
          pendingEvidenceRef.current = [];
          setTurns((prev) => [...prev, { role: "assistant", text: "", evidence }]);
        }
      },
    },
  });

  const send = useCallback(async (message: string, slotKey?: string, equipmentId?: number) => {
    sentRef.current = true;
    const myTurn = ++turnRef.current;
    setError(null);
    setPendingSlot(null);
    lastSeqRef.current = 0; // seq는 턴마다 새로 시작한다
    setStreaming(true);
    pendingEvidenceRef.current = [];
    assistantStartedRef.current = false;
    setTurns((prev) => [...prev, { role: "user", text: message, evidence: [] }]);
    setTrace([]);
    // 새 대화의 첫 턴에만 넣는다 — conversationId가 아직 없을 때가 그 순간이다.
    // 백엔드는 2b에서 읽기 전까지 이 필드를 무시한다(계약은 이미 여기서 맞춘다).
    const isFirstTurn = conversationIdRef.current === null;
    const body: { message: string; conversationId: string | null; slotKey: string | null; equipmentId?: number } =
      { message, conversationId: conversationIdRef.current, slotKey: slotKey ?? null };
    if (isFirstTurn && equipmentId !== undefined) body.equipmentId = equipmentId;
    // F4: 새 대화가 어느 설비로 시작됐는지 같이 남긴다 — 다음 마운트(새로고침 등)에서
    // 진입 설비가 바뀌었는지 판정하는 데 쓴다(resolveInitialConversationId).
    if (isFirstTurn) storeEquipmentId(equipmentId ?? null);
    const outcome = await start(body);
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
    api.get<{ role: string; text: string; evidence?: Evidence[] }[]>(agentTranscript(id))
      .then((res) => {
        if (cancelled || sentRef.current) return;
        setTurns(res.data.filter((l) => l.role === "user" || l.role === "assistant").map((l) => ({ role: l.role as Turn["role"], text: l.text, evidence: l.evidence ?? [] })));
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
    storeEquipmentId(null);
    // 복원 중이던 옛 대화가 reset 이후 도착해 turns를 덮어쓰지 않게 한다(sentRef 가드 재사용)
    sentRef.current = true;
    setRestoring(false);
    pendingEvidenceRef.current = [];
    assistantStartedRef.current = false;
    // recall도 항상 비운다 — 진입 회상(?equipmentId=)이 있으면 useEntryEquipment가
    // 독립적으로 들고 있는 값이 그대로 다시 보이므로 "유지"가 성립하고, 없으면 카드가 사라진다.
    setTrace([]); setTurns([]); setPendingSlot(null); setRecall(null); setError(null); setStreaming(false);
  }, [abort]);

  // 대화 전체에 등장한 근거 번호 — 복원+실시간 통틀어, 인용 칩이 "아는 번호"인지 판단하는 데 쓴다.
  const knownNos = useMemo(() => new Set(turns.flatMap((t) => t.evidence.map((e) => e.no))), [turns]);

  // 종류별 개수 — 근거 no로 중복 제거한 뒤 센다(같은 카드가 여러 턴에 다시 인용될 수 있다).
  const evidenceCount = useMemo(() => {
    const items = dedupeByNo(turns.flatMap((t) => t.evidence));
    return {
      total: items.length,
      photos: items.filter((e) => e.kind.startsWith("CASE_") && !!e.thumbnailUrl).length,
      guides: items.filter((e) => e.kind === "GUIDE").length,
      laws: items.filter((e) => e.kind === "LAW").length,
      msds: items.filter((e) => e.kind === "MSDS").length,
    };
  }, [turns]);

  return { trace, turns, pendingSlot, recall, error, streaming, restoring, connectionState, knownNos, evidenceCount, send, answerSlot, reset };
}
