// 미리보기 object URL 누수 방지, 점검 정보 저장, 허용 가능 여부와 개선대책 요청 계약.
import { createElement } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

vi.mock("@/api/equipmentApi", () => ({
  equipmentApi: { list: vi.fn() },
}));
vi.mock("@/api/visionApi", () => ({
  visionApi: { recent: vi.fn(), adopt: vi.fn(), reject: vi.fn(), createAction: vi.fn(), updateInspection: vi.fn(), setAcceptable: vi.fn() },
}));
vi.mock("@/api/actionApi", () => ({ actionApi: { complete: vi.fn() } }));
vi.mock("@/api/client", () => ({ fetchWithAuth: vi.fn(), ApiError: class extends Error {} }));

import { actionApi } from "@/api/actionApi";
import { equipmentApi } from "@/api/equipmentApi";
import { fetchWithAuth } from "@/api/client";
import { visionApi } from "@/api/visionApi";
import { useVision } from "@/pages/Vision/hooks/useVision";
import type { VisionCandidate } from "@/types/vision";

const mockEquipmentList = vi.mocked(equipmentApi.list);
const mockRecent = vi.mocked(visionApi.recent);
const mockFetch = vi.mocked(fetchWithAuth);

const env = (type: string, seq: number, payload: unknown) =>
  `event: ${type}\ndata: ${JSON.stringify({ type, correlationId: "v1", targetId: null, seq, ts: "t", payload })}\n\n`;
function sseResponse(chunks: string[] = []): Response {
  const enc = new TextEncoder(); let i = 0;
  return { ok: true, status: 200, body: { getReader: () => ({
    read: async () => (i < chunks.length ? { done: false, value: enc.encode(chunks[i++]) } : { done: true, value: undefined }),
    cancel: async () => {}, releaseLock: () => {} }) } } as unknown as Response;
}

const cand: VisionCandidate = {
  hazardId: 1, accidentType: "FALL", accidentLabel: "떨어짐", missingControl: "최상부 디딤대 사용", evidence: null, confidence: null,
  riskLevel: "HIGH", ruleTrace: "r", adopted: true, alreadyKnown: false, gateStatus: "PHOTO", acceptable: false,
  suggestedAction: { content: "c", lawRef: "l", lawTitle: null, guideRef: "B-5-2011", priority: "ENGINEERING" }, action: null, priorOpenAction: null,
};
const done = { assessmentId: 40, status: "ANALYZED", assessedOn: "2026-10-02", inspector: "이정훈", participants: [], equipmentId: 1, candidates: [cand], demoMode: false };

beforeEach(() => {
  vi.clearAllMocks();
  mockEquipmentList.mockResolvedValue([]);
  mockRecent.mockResolvedValue([]);
  mockFetch.mockResolvedValue(sseResponse());
  Object.defineProperty(URL, "createObjectURL", { value: vi.fn(() => "blob:x"), writable: true });
  Object.defineProperty(URL, "revokeObjectURL", { value: vi.fn(), writable: true });
});

const wrapper = ({ children }: { children: React.ReactNode }) => createElement(MemoryRouter, null, children);
const wrapperWithEquipmentId = (id: string) =>
  function Wrapper({ children }: { children: React.ReactNode }) {
    return createElement(MemoryRouter, { initialEntries: [`/vision?equipmentId=${id}`] }, children);
  };

describe("useVision", () => {
  it("pick()마다 이전 미리보기 URL을 revoke하고, 언마운트 시 마지막 URL도 정리한다", async () => {
    const createSpy = vi.fn().mockReturnValueOnce("blob:1").mockReturnValueOnce("blob:2");
    const revokeSpy = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { value: createSpy, writable: true });
    Object.defineProperty(URL, "revokeObjectURL", { value: revokeSpy, writable: true });

    const { result, unmount } = renderHook(() => useVision(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => { result.current.pick(new File(["a"], "a.jpg", { type: "image/jpeg" })); await Promise.resolve(); });
    expect(result.current.preview).toBe("blob:1");
    expect(revokeSpy).not.toHaveBeenCalled();

    await act(async () => { result.current.pick(new File(["b"], "b.jpg", { type: "image/jpeg" })); await Promise.resolve(); });
    expect(result.current.preview).toBe("blob:2");
    expect(revokeSpy).toHaveBeenCalledWith("blob:1");

    unmount();
    expect(revokeSpy).toHaveBeenCalledWith("blob:2");
  });

  it("점검자는 로그인 사용자로 시작하고, 분석이 끝나면 최근 점검을 다시 읽는다", async () => {
    const { result } = renderHook(() => useVision(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.inspector).toBe("이정훈");
    expect(mockRecent).toHaveBeenCalledTimes(1);

    await act(async () => { result.current.pick(new File(["a"], "a.jpg", { type: "image/jpeg" })); });
    await waitFor(() => expect(result.current.stream.analyzing).toBe(false));
    await waitFor(() => expect(mockRecent).toHaveBeenCalledTimes(2));
  });

  it("진입 컨텍스트(?equipmentId=)가 있으면 명시적 선택으로 시작한다", async () => {
    mockEquipmentList.mockResolvedValue([{ id: 9, name: "다른 설비", locationTag: null, processName: null, introducedOn: null }]);
    const { result } = renderHook(() => useVision(), { wrapper: wrapperWithEquipmentId("42") });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.equipmentId).toBe(42);
  });

  it("점검이 생긴 뒤 참여 근로자를 바꾸면 서버에 저장하고 결과를 서버 값으로 덮는다", async () => {
    mockFetch.mockResolvedValue(sseResponse([env("assess.done", 1, done)]));
    vi.mocked(visionApi.updateInspection).mockResolvedValue({ assessmentId: 40, inspector: "이정훈", participants: ["김철수"] });
    const { result } = renderHook(() => useVision(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => { result.current.pick(new File(["a"], "a.jpg", { type: "image/jpeg" })); });
    await waitFor(() => expect(result.current.stream.result?.assessmentId).toBe(40));

    await act(async () => { result.current.changeParticipants(["김철수"]); });
    expect(visionApi.updateInspection).toHaveBeenCalledWith(40, { inspector: "이정훈", participants: ["김철수"] });
    await waitFor(() => expect(result.current.stream.result?.participants).toEqual(["김철수"]));
  });

  it("허용 가능 여부와 개선대책은 이 점검에 묶고, 이행 완료는 actionApi로 보낸다", async () => {
    mockFetch.mockResolvedValue(sseResponse([env("assess.done", 1, done)]));
    const view = { id: 9, hazardId: 1, assessmentId: 40, equipmentId: 1, content: "c", owner: "김철수", dueDate: "2026-10-16", status: "PENDING" as const, guideRef: "B-5-2011", completedAt: null, createdAt: "t", priority: "ENGINEERING" as const };
    vi.mocked(visionApi.setAcceptable).mockResolvedValue({ hazardId: 1, acceptable: true });
    vi.mocked(visionApi.createAction).mockResolvedValue(view);
    vi.mocked(actionApi.complete).mockResolvedValue({ ...view, status: "DONE", completedAt: "t2" });
    const { result } = renderHook(() => useVision(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => { result.current.pick(new File(["a"], "a.jpg", { type: "image/jpeg" })); });
    await waitFor(() => expect(result.current.stream.result?.assessmentId).toBe(40));

    await act(async () => { await result.current.setAcceptable(1, true); });
    expect(visionApi.setAcceptable).toHaveBeenCalledWith(40, 1, true);
    expect(result.current.stream.result?.candidates[0].acceptable).toBe(true);

    await act(async () => { await result.current.createAction(cand, { content: "  c  ", owner: "김철수", dueDate: "2026-10-16", priority: "ENGINEERING", guideRef: "B-5-2011" }); });
    expect(visionApi.createAction).toHaveBeenCalledWith(1, { assessmentId: 40, content: "c", owner: "김철수", dueDate: "2026-10-16", guideRef: "B-5-2011", priority: "ENGINEERING" });

    await act(async () => { await result.current.completeAction(1, 9); });
    expect(actionApi.complete).toHaveBeenCalledWith(9);
    expect(result.current.stream.result?.candidates[0].action?.status).toBe("DONE");
  });
});
