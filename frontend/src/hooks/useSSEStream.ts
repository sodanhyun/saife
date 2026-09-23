// 봉투 파싱 훅 — 프레임 파서(utils/sseStream) 위에 type별 handler 디스패치만 얹는다.
// 재연결이 없다: SAIFE의 스트림은 전부 POST(대화 턴·사진 업로드)라 재연결이 곧 재요청이다.
import { useCallback, useEffect, useRef, useState } from "react";

import { fetchWithAuth } from "@/api/client";
import type { ConnectionState, SseEnvelope } from "@/types/sse";
import { isAbortError, registerAbortController, unregisterAbortController } from "@/utils/abortRegistry";
import { readSseStream, safeJson } from "@/utils/sseStream";

type HandlerMap<TEventMap> = {
  [K in keyof TEventMap]?: (payload: TEventMap[K], envelope: SseEnvelope<TEventMap[K]>) => void;
};

export interface StreamOutcome {
  reason: "ended" | "aborted" | "error";
  error?: Error;
}

export interface UseSSEStreamOptions<TEventMap> {
  url: string;
  method?: "GET" | "POST";
  /** 기본 body — start(body)로 덮을 수 있다. FormData면 그대로 보낸다. */
  body?: unknown;
  headers?: Record<string, string>;
  handlers: HandlerMap<TEventMap>;
  onConnectionChange?: (state: ConnectionState) => void;
}

export interface UseSSEStreamReturn {
  connectionState: ConnectionState;
  /** 스트림 시작. 끝날 때까지 기다릴 수 있다(도메인 훅이 "턴이 끝났다"를 알아야 한다). */
  start: (body?: unknown) => Promise<StreamOutcome>;
  abort: () => void;
}

export function useSSEStream<TEventMap>(options: UseSSEStreamOptions<TEventMap>): UseSSEStreamReturn {
  const { url, method = "GET", body, headers, handlers, onConnectionChange } = options;
  const [connectionState, setConnectionState] = useState<ConnectionState>("idle");
  const controllerRef = useRef<AbortController | null>(null);
  // 최신 콜백·옵션 참조 — 렌더 중 ref 쓰기 금지 → 커밋 후 동기화
  const handlersRef = useRef(handlers);
  const onChangeRef = useRef(onConnectionChange);
  const bodyRef = useRef(body);
  const headersRef = useRef(headers);
  useEffect(() => {
    handlersRef.current = handlers;
    onChangeRef.current = onConnectionChange;
    bodyRef.current = body;
    headersRef.current = headers;
  });

  const updateState = useCallback((s: ConnectionState) => {
    setConnectionState(s);
    onChangeRef.current?.(s);
  }, []);

  const start = useCallback(
    async (overrideBody?: unknown): Promise<StreamOutcome> => {
      // 이전 스트림 중단 — 한 번에 하나만 산다
      if (controllerRef.current) {
        controllerRef.current.abort();
        unregisterAbortController(controllerRef.current);
      }
      const controller = new AbortController();
      controllerRef.current = controller;
      registerAbortController(controller);
      updateState("connecting");

      // 이 스트림이 여전히 "현재" 스트림일 때만 상태를 반영한다 — 새 start()가 이미
      // 이 스트림을 밀어냈다면(controllerRef.current가 바뀌었다면) 뒤늦게 도착하는
      // 이전 스트림의 완료/실패가 새 스트림의 connectionState를 덮어쓰면 안 된다.
      const setIfLive = (s: ConnectionState) => {
        if (controllerRef.current === controller) updateState(s);
      };

      try {
        const fetchBody = overrideBody ?? bodyRef.current;
        const reqHeaders: Record<string, string> = { ...headersRef.current };
        const init: RequestInit = { method, signal: controller.signal, headers: reqHeaders };
        if (fetchBody instanceof FormData) {
          init.body = fetchBody; // Content-Type은 브라우저가 boundary와 함께 붙인다
        } else if (fetchBody != null) {
          reqHeaders["Content-Type"] = "application/json";
          init.body = JSON.stringify(fetchBody);
        }

        const response = await fetchWithAuth(url, init);
        setIfLive("connected");

        for await (const ev of readSseStream(response, controller.signal)) {
          const envelope = safeJson<SseEnvelope>(ev.data);
          if (!envelope) continue;
          if (envelope.type === "system.heartbeat") continue;
          const handler = handlersRef.current[envelope.type as keyof TEventMap];
          if (handler) {
            (handler as (p: unknown, e: SseEnvelope) => void)(envelope.payload, envelope);
          }
        }
        // signal이 이미 abort된 상태면 readSseStream이 정상 종료한 것처럼 보여도
        // 실제로는 중단된 것 — "ended"가 아니라 "aborted"로 보고한다.
        if (controller.signal.aborted) return { reason: "aborted" };
        setIfLive("idle");
        return { reason: "ended" };
      } catch (err) {
        if (isAbortError(err)) {
          setIfLive("idle");
          return { reason: "aborted" };
        }
        setIfLive("error");
        return { reason: "error", error: err instanceof Error ? err : new Error(String(err)) };
      } finally {
        if (controllerRef.current === controller) controllerRef.current = null;
        unregisterAbortController(controller);
      }
    },
    [url, method, updateState],
  );

  const abort = useCallback(() => {
    const c = controllerRef.current;
    if (c) {
      c.abort();
      unregisterAbortController(c);
    }
  }, []);

  // 언마운트 시 진행 중 스트림 정리
  useEffect(() => () => controllerRef.current?.abort(), []);

  return { connectionState, start, abort };
}
