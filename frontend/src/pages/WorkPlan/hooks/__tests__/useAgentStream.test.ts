import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/client", () => ({
  fetchWithAuth: vi.fn(),
  api: { get: vi.fn() },
  ApiError: class extends Error {},
}));

import { api, fetchWithAuth } from "@/api/client";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";

const mockFetch = vi.mocked(fetchWithAuth);
const mockGet = vi.mocked(api.get);

function envelope(type: string, seq: number, payload: unknown, correlationId = "conv-1") {
  return `event: ${type}\ndata: ${JSON.stringify({ type, correlationId, targetId: null, seq, ts: "t", payload })}\n\n`;
}

function sseResponse(chunks: string[]): Response {
  const enc = new TextEncoder();
  let i = 0;
  return {
    ok: true, status: 200,
    body: { getReader: () => ({
      read: async () => (i < chunks.length ? { done: false, value: enc.encode(chunks[i++]) } : { done: true, value: undefined }),
      cancel: async () => {}, releaseLock: () => {},
    }) },
  } as unknown as Response;
}

/** 청크를 다 내보낸 뒤 abort될 때까지 매달려 있는 응답 — 스트리밍 도중 상태를 보기 위한 목 */
function hangingResponse(chunks: string[], signal: AbortSignal | null | undefined): Response {
  const enc = new TextEncoder();
  let i = 0;
  return {
    ok: true, status: 200,
    body: { getReader: () => ({
      read: () => (i < chunks.length
        ? Promise.resolve({ done: false, value: enc.encode(chunks[i++]) })
        : new Promise((_, reject) => {
          signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        })),
      cancel: async () => {}, releaseLock: () => {},
    }) },
  } as unknown as Response;
}

/** abort될 때까지 응답하지 않는 fetch 목 */
function abortableFetch(_url: unknown, init?: RequestInit): Promise<Response> {
  return new Promise((_, reject) => {
    init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
  });
}

beforeEach(() => {
  sessionStorage.clear();
  mockFetch.mockReset();
  mockGet.mockReset();
});

describe("useAgentStream", () => {
  it("토큰을 assistant 턴에 이어 붙이고, 도구 시작/완료로 트레이스를 갱신하며, 끝나면 streaming=false", async () => {
    mockFetch.mockResolvedValue(sseResponse([
      envelope("ai.tool.start", 1, { toolName: "findLocationEquipment", params: "{}", callOrder: 1 }),
      envelope("ai.tool.done", 2, { toolName: "findLocationEquipment", callOrder: 1, success: true, durationMs: 17 }),
      envelope("ai.token", 3, "확인"), envelope("ai.token", 4, "했습니다"),
      envelope("ai.done", 5, null),
    ]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("내일 사다리 작업"); });
    expect(result.current.turns).toEqual([
      { role: "user", text: "내일 사다리 작업" },
      { role: "assistant", text: "확인했습니다" },
    ]);
    expect(result.current.trace).toEqual([
      expect.objectContaining({ toolName: "findLocationEquipment", status: "ok", durationMs: 17 }),
    ]);
    expect(result.current.streaming).toBe(false);
    expect(sessionStorage.getItem("saife.conversationId")).toBe("conv-1");
  });

  it("ai.slot.request 뒤 스트림이 닫혀도 pendingSlot은 남고 streaming은 false다", async () => {
    mockFetch.mockResolvedValue(sseResponse([
      envelope("ai.slot.request", 1, { slotKey: "work_height", question: "작업 높이는?", options: ["2m 미만", "2m 이상"] }),
    ]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("천장 도장"); });
    expect(result.current.pendingSlot?.slotKey).toBe("work_height");
    expect(result.current.streaming).toBe(false);
  });

  it("역행 seq는 버린다", async () => {
    mockFetch.mockResolvedValue(sseResponse([envelope("ai.token", 2, "b"), envelope("ai.token", 1, "a"), envelope("ai.token", 3, "c")]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("x"); });
    expect(result.current.turns[1].text).toBe("bc");
  });

  it("복원 응답이 send 이후 도착해도 turns를 덮지 않는다", async () => {
    sessionStorage.setItem("saife.conversationId", "conv-old");
    let resolveTranscript!: (v: { data: { role: string; text: string }[] }) => void;
    mockGet.mockReturnValue(new Promise((r) => { resolveTranscript = r; }) as never);
    mockFetch.mockResolvedValue(sseResponse([envelope("ai.token", 1, "새 답", "conv-old"), envelope("ai.done", 2, null, "conv-old")]));
    const { result } = renderHook(() => useAgentStream());
    expect(result.current.restoring).toBe(true);
    await act(async () => { await result.current.send("새 질문"); });
    await act(async () => { resolveTranscript({ data: [{ role: "user", text: "옛 질문" }] }); });
    await waitFor(() => expect(result.current.restoring).toBe(false));
    expect(result.current.turns.map((t) => t.text)).toEqual(["새 질문", "새 답"]);
  });

  it("ai.error 페이로드는 error로 드러나고 streaming은 false로 끝난다", async () => {
    mockFetch.mockResolvedValue(sseResponse([envelope("ai.error", 1, { message: "모델 호출에 실패했습니다" })]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("x"); });
    expect(result.current.error).toBe("모델 호출에 실패했습니다");
    expect(result.current.streaming).toBe(false);
  });

  it("start()가 error로 끝나면(fetch 거부) 그 메시지를 error로 둔다", async () => {
    mockFetch.mockRejectedValue(new Error("네트워크 연결이 끊겼습니다"));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("x"); });
    expect(result.current.error).toBe("네트워크 연결이 끊겼습니다");
    expect(result.current.streaming).toBe(false);
  });

  it("send가 겹치면 밀려난 앞 턴이 새 턴의 streaming·error를 덮지 않는다", async () => {
    mockFetch.mockImplementationOnce(abortableFetch);
    let resolveSecond!: (r: Response) => void;
    mockFetch.mockImplementationOnce(() => new Promise<Response>((r) => { resolveSecond = r; }));
    const { result } = renderHook(() => useAgentStream());

    let p1: Promise<void> | undefined;
    let p2: Promise<void> | undefined;
    act(() => { p1 = result.current.send("첫 질문"); });
    // 두 번째 send가 첫 스트림을 abort한다 — 첫 send는 aborted로 끝난다
    await act(async () => { p2 = result.current.send("두 번째 질문"); await p1; });
    expect(result.current.streaming).toBe(true); // 앞 턴의 마무리가 새 턴을 끝난 것으로 만들면 안 된다

    await act(async () => { resolveSecond(sseResponse([envelope("ai.token", 1, "답"), envelope("ai.done", 2, null)])); await p2; });
    expect(result.current.streaming).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.turns.map((t) => t.text)).toEqual(["첫 질문", "두 번째 질문", "답"]);
  });

  it("스트리밍 도중 reset()하면 스트림을 중단하고 turns·trace·pendingSlot을 비운다", async () => {
    let signal: AbortSignal | null | undefined;
    mockFetch.mockImplementation((_u, init) => {
      signal = init?.signal;
      return Promise.resolve(hangingResponse([
        envelope("ai.tool.start", 1, { toolName: "findLocationEquipment", params: "{}", callOrder: 1 }),
        envelope("ai.slot.request", 2, { slotKey: "work_height", question: "작업 높이는?", options: [] }),
      ], init?.signal));
    });
    const { result } = renderHook(() => useAgentStream());
    let p: Promise<void> | undefined;
    act(() => { p = result.current.send("천장 도장"); });
    await waitFor(() => expect(result.current.pendingSlot?.slotKey).toBe("work_height"));
    expect(result.current.trace).toHaveLength(1);
    expect(result.current.streaming).toBe(true);

    await act(async () => { result.current.reset(); await p; });
    expect(signal?.aborted).toBe(true);
    expect(result.current.turns).toEqual([]);
    expect(result.current.trace).toEqual([]);
    expect(result.current.pendingSlot).toBeNull();
    expect(result.current.streaming).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it("복원 도중 reset()하면 뒤늦게 도착한 옛 대화로 turns가 채워지지 않는다", async () => {
    sessionStorage.setItem("saife.conversationId", "conv-old");
    let resolveTranscript!: (v: { data: { role: string; text: string }[] }) => void;
    mockGet.mockReturnValue(new Promise((r) => { resolveTranscript = r; }) as never);
    const { result } = renderHook(() => useAgentStream());
    expect(result.current.restoring).toBe(true);

    act(() => { result.current.reset(); });
    expect(result.current.restoring).toBe(false);
    expect(sessionStorage.getItem("saife.conversationId")).toBeNull();

    await act(async () => { resolveTranscript({ data: [{ role: "user", text: "옛 질문" }] }); });
    expect(result.current.turns).toEqual([]);
  });
});
