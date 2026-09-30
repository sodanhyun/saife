import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/client", () => ({
  fetchWithAuth: vi.fn(),
  api: { get: vi.fn() },
  ApiError: class extends Error {},
}));

import { api, fetchWithAuth } from "@/api/client";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";
import type { Evidence } from "@/types/evidence";

const mockFetch = vi.mocked(fetchWithAuth);
const mockGet = vi.mocked(api.get);

function envelope(type: string, seq: number, payload: unknown, correlationId = "conv-1") {
  return `event: ${type}\ndata: ${JSON.stringify({ type, correlationId, targetId: null, seq, ts: "t", payload })}\n\n`;
}

/** 근거 카드 픽스처 — no만 바꿔가며 쓴다 */
function ev(no: number): Evidence {
  return {
    no, kind: "GUIDE", refId: no, refKey: `G:${no}`, title: `근거 ${no}`, snippet: "",
    sourceUrl: null, mediaUrl: null, thumbnailUrl: null, origin: "CACHE", score: 0.5,
    fetchedAt: "2026-09-28T00:00:00+09:00", meta: {},
  };
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
      { role: "user", text: "내일 사다리 작업", evidence: [] },
      { role: "assistant", text: "확인했습니다", evidence: [] },
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

  it("ai.recall은 항상 마지막 한 장만 남긴다 — 같은 설비든 다른 설비든 통째로 교체된다", async () => {
    mockFetch.mockResolvedValue(sseResponse([
      envelope("ai.recall", 1, { equipmentId: 1, equipmentName: "이동식 사다리 A", locationTag: "공장동", headline: "첫 회상", predicted: false, warnedAt: null, priorHazards: [], unfinishedActions: [], priorWorkPlans: [], priorIncidents: [], knownSlots: [] }),
      envelope("ai.recall", 2, { equipmentId: 1, equipmentName: "이동식 사다리 A", locationTag: "공장동", headline: "같은 설비 최신 회상", predicted: false, warnedAt: null, priorHazards: [], unfinishedActions: [], priorWorkPlans: [], priorIncidents: [], knownSlots: [] }),
      envelope("ai.recall", 3, { equipmentId: 2, equipmentName: "고소작업대", locationTag: "본관 옥상", headline: "다른 설비 회상", predicted: false, warnedAt: null, priorHazards: [], unfinishedActions: [], priorWorkPlans: [], priorIncidents: [], knownSlots: [] }),
    ]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("공장동 후면 차양부"); });
    expect(result.current.recall?.equipmentId).toBe(2);
    expect(result.current.recall?.headline).toBe("다른 설비 회상");
  });

  it("reset()은 recall도 비운다", async () => {
    mockFetch.mockResolvedValue(sseResponse([
      envelope("ai.recall", 1, { equipmentId: 1, equipmentName: "이동식 사다리 A", locationTag: null, headline: "회상", predicted: false, warnedAt: null, priorHazards: [], unfinishedActions: [], priorWorkPlans: [], priorIncidents: [], knownSlots: [] }),
      envelope("ai.done", 2, null),
    ]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("공장동 후면 차양부"); });
    expect(result.current.recall).not.toBeNull();
    act(() => { result.current.reset(); });
    expect(result.current.recall).toBeNull();
  });

  it("새 대화의 첫 턴에만 body에 equipmentId를 싣는다", async () => {
    mockFetch.mockResolvedValue(sseResponse([envelope("ai.done", 1, null)]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("첫 메시지", undefined, 7); });
    await act(async () => { await result.current.send("두 번째 메시지", undefined, 7); });
    const bodies = mockFetch.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string));
    expect(bodies[0]).toMatchObject({ equipmentId: 7 });
    expect(bodies[1].equipmentId).toBeUndefined();
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

  it("턴별 근거 누적 — ai.evidence는 그 턴의 assistant에만 붙는다", async () => {
    mockFetch.mockResolvedValueOnce(sseResponse([
      envelope("ai.evidence", 1, [ev(1)]),
      envelope("ai.token", 2, "답 1 [#1]"),
      envelope("ai.done", 3, null),
    ]));
    mockFetch.mockResolvedValueOnce(sseResponse([
      envelope("ai.evidence", 1, [ev(2)]),
      envelope("ai.token", 2, "답 2 [#2]"),
      envelope("ai.done", 3, null),
    ]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("첫 질문"); });
    await act(async () => { await result.current.send("둘째 질문"); });
    const asst = result.current.turns.filter((t) => t.role === "assistant");
    expect(asst[0].evidence.map((e) => e.no)).toEqual([1]);
    expect(asst[1].evidence.map((e) => e.no)).toEqual([2]);
    expect([...result.current.knownNos]).toEqual([1, 2]);
    expect(result.current.evidenceCount.total).toBe(2);
  });

  it("복원 근거 — transcript의 evidence가 턴에 붙는다", async () => {
    sessionStorage.setItem("saife.conversationId", "old");
    mockGet.mockResolvedValueOnce({
      data: [
        { role: "user", text: "q", evidence: [] },
        { role: "assistant", text: "a [#1]", evidence: [ev(1)] },
      ],
    });
    const { result } = renderHook(() => useAgentStream());
    await waitFor(() => expect(result.current.restoring).toBe(false));
    expect(result.current.turns[1].evidence).toHaveLength(1);
    expect(result.current.knownNos.has(1)).toBe(true);
  });

  // F4 — 진입 설비가 저장된 대화의 설비를 삼키지 않는다.
  describe("F4 — 진입 설비와 저장된 대화의 설비가 다르면 새 대화로 시작한다", () => {
    it("설비 A 대화가 저장된 상태에서 설비 B로 진입하면 복원하지 않고, 첫 요청에 B가 실린다", async () => {
      sessionStorage.setItem("saife.conversationId", "conv-A");
      sessionStorage.setItem("saife.conversationEquipmentId", "1");
      mockFetch.mockResolvedValue(sseResponse([envelope("ai.done", 1, null, "conv-B")]));

      const { result } = renderHook(() => useAgentStream(2));
      // 진입 설비가 저장된 대화의 설비(1)와 다르므로(2) 복원 대상이 아니다.
      expect(result.current.restoring).toBe(false);
      expect(sessionStorage.getItem("saife.conversationId")).toBeNull();

      await act(async () => { await result.current.send("내일 작업 신고", undefined, 2); });
      const body = JSON.parse((mockFetch.mock.calls[0][1] as RequestInit).body as string);
      expect(body).toMatchObject({ conversationId: null, equipmentId: 2 });
      expect(sessionStorage.getItem("saife.conversationEquipmentId")).toBe("2");
    });

    it("저장된 대화에 설비가 없었는데(구버전 저장값) 진입 설비가 있으면 새 대화로 시작한다", async () => {
      sessionStorage.setItem("saife.conversationId", "conv-old");
      mockFetch.mockResolvedValue(sseResponse([envelope("ai.done", 1, null, "conv-new")]));

      const { result } = renderHook(() => useAgentStream(5));
      expect(result.current.restoring).toBe(false);

      await act(async () => { await result.current.send("작업 신고", undefined, 5); });
      const body = JSON.parse((mockFetch.mock.calls[0][1] as RequestInit).body as string);
      expect(body).toMatchObject({ conversationId: null, equipmentId: 5 });
    });

    it("진입 설비가 저장된 대화의 설비와 같으면 대화를 복원한다", async () => {
      sessionStorage.setItem("saife.conversationId", "conv-A");
      sessionStorage.setItem("saife.conversationEquipmentId", "1");
      mockGet.mockResolvedValueOnce({ data: [{ role: "user", text: "이전 질문", evidence: [] }] });

      const { result } = renderHook(() => useAgentStream(1));
      expect(result.current.restoring).toBe(true);
      await waitFor(() => expect(result.current.restoring).toBe(false));
      expect(result.current.turns.map((t) => t.text)).toEqual(["이전 질문"]);
      expect(sessionStorage.getItem("saife.conversationId")).toBe("conv-A");
    });

    it("진입 설비가 없으면(주소창 직접 진입) 저장된 대화를 그대로 복원한다", async () => {
      sessionStorage.setItem("saife.conversationId", "conv-A");
      sessionStorage.setItem("saife.conversationEquipmentId", "1");
      mockGet.mockResolvedValueOnce({ data: [{ role: "user", text: "이전 질문", evidence: [] }] });

      const { result } = renderHook(() => useAgentStream(null));
      expect(result.current.restoring).toBe(true);
      await waitFor(() => expect(result.current.restoring).toBe(false));
      expect(result.current.turns.map((t) => t.text)).toEqual(["이전 질문"]);
    });
  });
});
