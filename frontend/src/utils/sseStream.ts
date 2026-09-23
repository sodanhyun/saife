// src/utils/sseStream.ts

export interface ParsedSseEvent {
  event: string;
  data: string;
}

/** JSON 안전 파싱 — 실패 시 null 반환. DEV에서 경고 로그. */
export function safeJson<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch (e) {
    if (import.meta.env.DEV) {
      console.warn("[SSE] Failed to parse event data:", raw.slice(0, 200), e);
    }
    return null;
  }
}

/** 백엔드 SSE data 필드의 이스케이프된 개행 복원 */
export function unescapeNewlines(s: string): string {
  return s.replace(/\\n/g, "\n");
}

export async function* readSseStream(
  response: Response,
  signal?: AbortSignal
): AsyncGenerator<ParsedSseEvent> {
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      // 외부에서 중단 요청 시 루프 종료 → finally에서 reader 해제
      if (signal?.aborted) break;

      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      const parts = buffer.split("\n\n");
      buffer = parts.pop() ?? "";

      for (const block of parts) {
        if (!block.trim()) continue;
        let eventName = "message";
        const dataLines: string[] = [];

        for (const line of block.split("\n")) {
          if (line.startsWith("event:")) {
            eventName = line.slice(6).trim();
          } else if (line.startsWith("data:")) {
            dataLines.push(line.startsWith("data: ") ? line.slice(6) : line.slice(5));
          }
        }

        if (dataLines.length > 0) {
          const rawData = dataLines.join("\n");
          yield { event: eventName, data: rawData };
        }
      }
    }
  } finally {
    // 루프 종료 시(정상/예외/break/return 모두) reader 해제 — 고아 커넥션 방지.
    // cancel()은 Promise 반환 — 이미 abort된 스트림에서는 거부되므로 .catch로 삼켜야
    // unhandled rejection("BodyStreamBuffer was aborted") 콘솔 노출이 없다(동기 catch는 무효).
    try {
      void reader.cancel().catch(() => { /* 이미 닫힌 경우 무시 */ });
    } catch { /* getReader 락 등 동기 예외 무시 */ }
  }
}
