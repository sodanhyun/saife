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

beforeEach(() => { mockFetch.mockReset(); });

describe("useVisionStream", () => {
  it("멀티파트로 올리고 progress → done 순으로 상태를 갱신한다", async () => {
    const result = { assessmentId: 7, status: "DONE", candidates: [], demoMode: false };
    mockFetch.mockResolvedValue(sseResponse([env("assess.progress", 1, { message: "판독 중" }), env("assess.done", 2, result)]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => { await r.current.analyze(new File(["x"], "a.jpg", { type: "image/jpeg" }), 3); });
    const init = mockFetch.mock.calls[0][1]!;
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("equipmentId")).toBe("3");
    expect(r.current.result?.assessmentId).toBe(7);
    expect(r.current.analyzing).toBe(false);
    expect(r.current.progress).toBeNull();
  });

  it("assess.failed면 error에 메시지가 들어가고 analyzing이 끝난다", async () => {
    mockFetch.mockResolvedValue(sseResponse([env("assess.failed", 1, { message: "모델 응답 없음" })]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => { await r.current.analyze(new File(["x"], "a.jpg"), null); });
    expect(r.current.error).toBe("모델 응답 없음");
    expect(r.current.analyzing).toBe(false);
  });

  it("assess.progress의 phase로 단계를 기록하고 done에서 DONE으로 끝난다", async () => {
    const result = { assessmentId: 7, status: "ANALYZED", candidates: [], demoMode: false };
    mockFetch.mockResolvedValue(sseResponse([
      env("assess.progress", 1, { phase: "ANALYZING", message: "모델 판독" }),
      env("assess.progress", 2, { phase: "GRADING", message: "등급" }),
      env("assess.progress", 3, { phase: "EVIDENCE", message: "근거" }),
      env("assess.done", 4, result),
    ]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => { await r.current.analyze(new File(["x"], "a.jpg", { type: "image/jpeg" }), null); });
    expect(r.current.stage).toBe("DONE");
    expect(r.current.stageLog.map((m) => m.stage)).toEqual(["UPLOADING", "ANALYZING", "GRADING", "EVIDENCE", "DONE"]);
  });
});
