import type { Evidence } from "@/types/evidence";
import type { RecallView } from "@/types/timeline";

/**
 * SSE 통합 봉투 — 백엔드 SseService.SseEvent와 1:1 매핑.
 * 필드를 바꾸면 양쪽을 같이 바꾼다. 규약: .claude/rules/sse-streaming.md
 */
export interface SseEnvelope<P = unknown> {
  type: SseEventType;
  correlationId: string;
  targetId: string | null;
  seq: number;
  ts: string;
  payload: P;
}

export type SseEventType =
  | "ai.token"
  | "ai.done"
  | "ai.error"
  | "ai.tool.start"
  | "ai.tool.done"
  | "ai.slot.request"
  | "ai.recall"
  | "ai.evidence"
  | "assess.progress"
  | "assess.done"
  | "assess.failed"
  | "system.heartbeat";

/** 도구 점등 — 트레이스 패널의 한 줄이 여기서 생긴다 */
export interface ToolStartPayload {
  toolName: string;
  params: string;
  callOrder: number;
}

export interface ToolDonePayload {
  toolName: string;
  callOrder: number;
  success: boolean;
  durationMs: number;
  errorMessage?: string;
}

/**
 * 되묻기 턴 — 스트림이 여기서 멈추고 사용자 입력을 기다린다.
 * ledgerValue가 있으면 "확인" UI, 없으면 "입력" UI로 그린다.
 */
export interface SlotRequestPayload {
  slotKey: string;
  question: string;
  options?: string[];
  ledgerValue?: string;
}

export interface AiErrorPayload {
  message: string;
  code?: string;
}

/**
 * 회상 카드 payload — `types/timeline.ts`의 `RecallView`를 그대로 재사용한다(중복 선언 금지).
 * `targetId` = conversationId. 도구가 설비 매칭에 성공하면 도구 레벨에서 발행한다(모델 문장과 무관).
 */
export type RecallPayload = RecallView;

/** 턴 종료 시 이번 턴에 새로 등록된 근거(bare array). 카드는 백엔드가 만든다 */
export type EvidencePayload = Evidence[];

/** 트레이스 패널이 들고 있는 한 줄의 상태 */
export interface ToolTraceRow {
  callOrder: number;
  toolName: string;
  params: string;
  status: "running" | "ok" | "failed";
  durationMs?: number;
  errorMessage?: string;
}

/** 스트림 연결 상태 — useSSEStream이 관리한다. 재연결은 없다(POST 스트림은 재연결이 곧 재요청). */
export type ConnectionState = "idle" | "connecting" | "connected" | "disconnected" | "error";

/** 에러 이벤트 표준 payload(ai.error · assess.failed 공통, sse-streaming.md) */
export interface SseErrorPayload {
  errorType?: string;
  message: string;
  detail?: string;
}
