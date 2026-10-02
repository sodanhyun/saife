import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/client", () => ({ fetchWithAuth: vi.fn(), ApiError: class extends Error {} }));

import { fetchWithAuth } from "@/api/client";
import { useVisionStream } from "@/pages/Vision/hooks/useVisionStream";

const mockFetch = vi.mocked(fetchWithAuth);
const env = (type: string, seq: number, payload: unknown) =>
  `event: ${type}\ndata: ${JSON.stringify({ type, correlationId: "v1", targetId: null, seq, ts: "t", payload })}\n\n`;
function sseResponse(chunks: string[]): Response {
  const enc = new TextEncoder(); let i = 0;
  return { ok: true, status: 200, body: { getReader: () => ({
    read: async () => (i < chunks.length ? { done: false, value: enc.encode(chunks[i++]) } : { done: true, value: undefined }),
    cancel: async () => {}, releaseLock: () => {} }) } } as unknown as Response;
}

const done = { assessmentId: 7, status: "ANALYZED", assessedOn: "2026-10-02", inspector: "이정훈", participants: ["김철수"], equipmentId: 3, candidates: [], demoMode: false };

beforeEach(() => { mockFetch.mockReset(); });

describe("useVisionStream", () => {
  it("멀티파트로 사진과 점검 정보(설비, 점검자, 참여 근로자)를 함께 올린다", async () => {
    mockFetch.mockResolvedValue(sseResponse([env("assess.progress", 1, { phase: "ANALYZING" }), env("assess.done", 2, done)]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => {
      await r.current.analyze(new File(["x"], "a.jpg", { type: "image/jpeg" }), { equipmentId: 3, inspector: " 이정훈 ", participants: ["김철수", "박민수"] });
    });
    const body = mockFetch.mock.calls[0][1]!.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect(body.get("equipmentId")).toBe("3");
    expect(body.get("inspector")).toBe("이정훈");
    expect(body.getAll("participants")).toEqual(["김철수", "박민수"]);
    expect(r.current.result?.assessmentId).toBe(7);
    expect(r.current.analyzing).toBe(false);
    expect(r.current.stage).toBe("DONE");
  });

  it("assess.failed면 error에 메시지가 들어가고 analyzing이 끝난다", async () => {
    mockFetch.mockResolvedValue(sseResponse([env("assess.failed", 1, { message: "사진을 분석하지 못했습니다" })]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => { await r.current.analyze(new File(["x"], "a.jpg"), { equipmentId: null, inspector: "", participants: [] }); });
    expect(r.current.error).toBe("사진을 분석하지 못했습니다");
    expect(r.current.analyzing).toBe(false);
    expect(r.current.stage).toBeNull();
  });

  it("patchInspection은 결과의 점검자와 참여 근로자를 서버 값으로 덮는다", async () => {
    mockFetch.mockResolvedValue(sseResponse([env("assess.done", 1, done)]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => { await r.current.analyze(new File(["x"], "a.jpg"), { equipmentId: 3, inspector: "이정훈", participants: [] }); });
    act(() => { r.current.patchInspection("이정훈", ["김철수", "박민수"]); });
    expect(r.current.result?.participants).toEqual(["김철수", "박민수"]);
  });
});
