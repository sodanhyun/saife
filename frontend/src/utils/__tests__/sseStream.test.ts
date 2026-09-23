// src/utils/__tests__/sseStream.test.ts
// readSseStream 스트림 정리(cleanup) 경로 검증.
import { describe, expect, it, vi } from "vitest";

import { readSseStream } from "@/utils/sseStream";

/** abort된 스트림 Response 목 — read()가 AbortError로 거부하고 cancel()도 거부하는 실제 중단 시나리오. */
function makeAbortedResponse(
  readMock: ReturnType<typeof vi.fn>,
  cancelMock: ReturnType<typeof vi.fn>,
): Response {
  const reader = { read: readMock, cancel: cancelMock, releaseLock: () => {} };
  return { body: { getReader: () => reader } } as unknown as Response;
}

const abortError = () => new DOMException("BodyStreamBuffer was aborted", "AbortError");

describe("readSseStream", () => {
  // Regression: ISSUE-002 — reader.cancel()은 Promise를 반환하는데 동기 try/catch로만 감싸
  // 이미 abort된 스트림에서 cancel() 거부가 unhandled rejection("BodyStreamBuffer was aborted")으로
  // 콘솔에 노출됐다(SSE 연결 해제마다 — export 완료·페이지 이탈 등).
  // Found by /qa on 2026-07-23. Report: .gstack/qa-reports/qa-report-metalflow-2026-07-23.md
  it("정리 시 reader.cancel() 거부에 핸들러가 부착된다(unhandled rejection 방지)", async () => {
    const readMock = vi.fn(() => Promise.reject(abortError())); // abort 순간 read가 거부
    // cancel()이 돌려주는 "거부 예정" thenable — 거부 핸들러(onRejected) 부착 여부를 관찰한다.
    // 핸들러가 하나도 부착되지 않으면 실제 런타임에서 unhandled rejection으로 콘솔에 노출된다.
    let rejectionHandled = false;
    const cancelMock = vi.fn(() => ({
      then(_onFulfilled?: unknown, onRejected?: unknown) {
        if (typeof onRejected === "function") rejectionHandled = true;
        return Promise.resolve();
      },
      catch(onRejected?: unknown) {
        if (typeof onRejected === "function") rejectionHandled = true;
        return Promise.resolve();
      },
    }));
    const gen = readSseStream(makeAbortedResponse(readMock, cancelMock));

    // read 거부는 제너레이터 밖으로 전파(소비자 catch 몫) — finally의 cancel() 경로가 함께 실행된다
    await expect(gen.next()).rejects.toThrow("BodyStreamBuffer was aborted");

    expect(cancelMock).toHaveBeenCalled();
    expect(rejectionHandled).toBe(true);
  });

  it("signal이 이미 aborted면 read 없이 즉시 종료하고 reader를 해제한다", async () => {
    const readMock = vi.fn();
    const cancelMock = vi.fn(() => Promise.resolve());
    const controller = new AbortController();
    controller.abort();

    const events = [];
    for await (const ev of readSseStream(makeAbortedResponse(readMock, cancelMock), controller.signal)) {
      events.push(ev);
    }

    expect(events).toEqual([]);
    expect(readMock).not.toHaveBeenCalled();
    expect(cancelMock).toHaveBeenCalled();
  });
});
