import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/client", () => ({
  fetchWithAuth: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, message: string) { super(message); }
  },
}));

import { fetchWithAuth } from "@/api/client";
import { useSSEStream } from "@/hooks/useSSEStream";

const mockFetch = vi.mocked(fetchWithAuth);

/** 문자열 청크들을 SSE 본문으로 흘려주는 Response 목 */
function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  let i = 0;
  const reader = {
    read: async () =>
      i < chunks.length ? { done: false, value: encoder.encode(chunks[i++]) } : { done: true, value: undefined },
    cancel: async () => {},
    releaseLock: () => {},
  };
  return { ok: true, status: 200, body: { getReader: () => reader } } as unknown as Response;
}

// 주의: 화살표 함수의 암시적 반환으로 작성하지 않는다 — `mockReset()`은 체이닝을 위해
// mock 함수 자기 자신을 반환하는데, vitest는 beforeEach가 함수를 반환하면 그것을
// "테스트 후 정리 콜백"으로 해석해 테스트가 끝난 뒤 그 함수(=mockFetch)를 다시 호출한다.
// 이 시점엔 테스트가 설정해둔 구현(reject 등)이 남아있어 뒤늦은 거부가 다음 테스트를
// 오염시키거나 훅 타임아웃으로 이어진다. 블록 바디로 감싸 undefined를 반환하게 한다.
beforeEach(() => {
  mockFetch.mockReset();
});

describe("useSSEStream (명령형)", () => {
  it("봉투 type별 handler에 payload를 전달하고, 끝나면 ended로 돌아온다", async () => {
    const env = { type: "ai.token", correlationId: "c1", targetId: null, seq: 1, ts: "t", payload: "안녕" };
    mockFetch.mockResolvedValue(sseResponse([`event: ai.token\ndata: ${JSON.stringify(env)}\n\n`]));
    const onToken = vi.fn();
    const { result } = renderHook(() =>
      useSSEStream<{ "ai.token": string }>({ url: "/api/agent/chat", method: "POST", handlers: { "ai.token": onToken } }),
    );
    let outcome;
    await act(async () => { outcome = await result.current.start({ message: "hi" }); });
    expect(onToken).toHaveBeenCalledWith("안녕", expect.objectContaining({ seq: 1 }));
    expect(outcome).toEqual({ reason: "ended" });
    expect(result.current.connectionState).toBe("idle");
  });

  it("FormData body는 JSON 직렬화하지 않고 그대로 보낸다", async () => {
    mockFetch.mockResolvedValue(sseResponse([]));
    const { result } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/api/vision/analyze", method: "POST", handlers: {} }));
    const form = new FormData();
    await act(async () => { await result.current.start(form); });
    const init = mockFetch.mock.calls[0][1]!;
    expect(init.body).toBe(form);
    expect(new Headers(init.headers).get("Content-Type")).toBeNull();
  });

  it("non-ok 응답(fetchWithAuth가 던짐)이면 error 상태와 error outcome을 돌려준다", async () => {
    mockFetch.mockRejectedValue(new Error("업로드 실패 (500)"));
    const { result } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/x", method: "POST", handlers: {} }));
    let outcome: { reason: string; error?: Error } | undefined;
    await act(async () => { outcome = await result.current.start({}); });
    expect(outcome?.reason).toBe("error");
    expect(outcome?.error?.message).toBe("업로드 실패 (500)");
    expect(result.current.connectionState).toBe("error");
  });

  it("abort()로 중단하면 aborted outcome이고 error가 아니다", async () => {
    mockFetch.mockImplementation((_i, init) => new Promise((_, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    }));
    const { result } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/x", method: "POST", handlers: {} }));
    let p: Promise<{ reason: string }> | undefined;
    act(() => { p = result.current.start({}); });
    act(() => result.current.abort());
    const outcome = await p!;
    expect(outcome.reason).toBe("aborted");
  });

  it("새 start()가 이전 스트림을 중단해도 상태는 새 스트림의 것이다", async () => {
    // 첫 번째 스트림 — abort되면 거부되고, 그 전까지는 영원히 pending
    mockFetch.mockImplementationOnce((_i, init) => new Promise((_, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    }));
    // 두 번째 스트림 — 응답이 도착하지 않는다(헤더 수신 전까지 "connecting" 유지)
    mockFetch.mockImplementationOnce(() => new Promise(() => {}));

    const { result } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/x", method: "POST", handlers: {} }));

    let p1: Promise<{ reason: string; error?: Error }> | undefined;
    act(() => { p1 = result.current.start({}); });
    act(() => { result.current.start({}); }); // 새 start()가 이전 스트림을 중단한다

    const outcome1 = await p1!;
    expect(outcome1).toEqual({ reason: "aborted" });
    // 이전 스트림의 뒤늦은 "idle" 전이가 새 스트림의 "connecting"을 덮어쓰면 안 된다
    expect(result.current.connectionState).toBe("connecting");
  });

  it("언마운트하면 진행 중 스트림이 중단된다", async () => {
    let capturedSignal: AbortSignal | undefined;
    mockFetch.mockImplementation((_i, init) => {
      capturedSignal = init?.signal ?? undefined;
      return new Promise(() => {});
    });
    const { result, unmount } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/x", method: "POST", handlers: {} }));
    act(() => { result.current.start({}); });
    expect(capturedSignal).toBeDefined();
    expect(capturedSignal?.aborted).toBe(false);
    unmount();
    expect(capturedSignal?.aborted).toBe(true);
  });
});
