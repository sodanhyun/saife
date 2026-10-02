// 미리보기 object URL 누수 방지 — pick()이 이전 URL을 revoke하고, 언마운트 시 마지막 URL도 정리한다.
import { createElement } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

vi.mock("@/api/equipmentApi", () => ({
  equipmentApi: { list: vi.fn() },
}));
vi.mock("@/api/visionApi", () => ({
  visionApi: { adoptionRate: vi.fn(), adopt: vi.fn(), reject: vi.fn(), createAction: vi.fn() },
}));
vi.mock("@/api/actionApi", () => ({ actionApi: { complete: vi.fn() } }));
vi.mock("@/api/client", () => ({ fetchWithAuth: vi.fn(), ApiError: class extends Error {} }));

import { actionApi } from "@/api/actionApi";
import { equipmentApi } from "@/api/equipmentApi";
import { fetchWithAuth } from "@/api/client";
import { visionApi } from "@/api/visionApi";
import { useVision } from "@/pages/Vision/hooks/useVision";

const mockEquipmentList = vi.mocked(equipmentApi.list);
const mockAdoptionRate = vi.mocked(visionApi.adoptionRate);
const mockFetch = vi.mocked(fetchWithAuth);

/** SSE 응답을 흉내낸다 — 바로 done:true를 보고해 스트림이 즉시 끝난다. */
function sseResponse(): Response {
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: async () => ({ done: true, value: undefined }),
        cancel: async () => {},
        releaseLock: () => {},
      }),
    },
  } as unknown as Response;
}

beforeEach(() => {
  mockEquipmentList.mockReset();
  mockAdoptionRate.mockReset();
  mockFetch.mockReset();
  mockEquipmentList.mockResolvedValue([]);
  mockAdoptionRate.mockResolvedValue({ suggested: 0, adopted: 0, rate: null, axes: [], note: "" });
  mockFetch.mockResolvedValue(sseResponse());
});

// useSearchParams는 <Router> 컨텍스트가 있어야 한다(진입 컨텍스트 ?equipmentId= 지원 추가로 필요해짐).
// 이 파일은 .ts라 JSX 없이 createElement로 감싼다.
const wrapper = ({ children }: { children: React.ReactNode }) => createElement(MemoryRouter, null, children);
const wrapperWithEquipmentId = (id: string) =>
  function Wrapper({ children }: { children: React.ReactNode }) {
    return createElement(MemoryRouter, { initialEntries: [`/vision?equipmentId=${id}`] }, children);
  };

describe("useVision", () => {
  it("pick()마다 이전 미리보기 URL을 revoke하고, 언마운트 시 마지막 URL도 정리한다", async () => {
    const createSpy = vi.fn().mockReturnValueOnce("blob:1").mockReturnValueOnce("blob:2");
    const revokeSpy = vi.fn();
    // jsdom엔 URL.createObjectURL이 없다 — 직접 정의한다.
    Object.defineProperty(URL, "createObjectURL", { value: createSpy, writable: true });
    Object.defineProperty(URL, "revokeObjectURL", { value: revokeSpy, writable: true });

    const { result, unmount } = renderHook(() => useVision(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));

    const file1 = new File(["a"], "a.jpg", { type: "image/jpeg" });
    await act(async () => {
      result.current.pick(file1);
      await Promise.resolve();
    });
    expect(result.current.preview).toBe("blob:1");
    expect(revokeSpy).not.toHaveBeenCalled();

    const file2 = new File(["b"], "b.jpg", { type: "image/jpeg" });
    await act(async () => {
      result.current.pick(file2);
      await Promise.resolve();
    });
    expect(result.current.preview).toBe("blob:2");
    expect(revokeSpy).toHaveBeenCalledWith("blob:1");

    unmount();
    expect(revokeSpy).toHaveBeenCalledWith("blob:2");
  });

  it("채택률은 마운트 때 한 번만 읽고, 판독이 끝날 때(analyzing true→false) 다시 읽는다", async () => {
    Object.defineProperty(URL, "createObjectURL", { value: vi.fn(() => "blob:x"), writable: true });
    Object.defineProperty(URL, "revokeObjectURL", { value: vi.fn(), writable: true });
    let resolveFetch!: (r: Response) => void;
    mockFetch.mockImplementationOnce(() => new Promise<Response>((r) => { resolveFetch = r; }));

    const { result } = renderHook(() => useVision(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await waitFor(() => expect(result.current.rate).not.toBeNull());
    expect(mockAdoptionRate).toHaveBeenCalledTimes(1);

    await act(async () => { result.current.pick(new File(["a"], "a.jpg", { type: "image/jpeg" })); });
    expect(result.current.stream.analyzing).toBe(true);
    expect(mockAdoptionRate).toHaveBeenCalledTimes(1);

    await act(async () => { resolveFetch(sseResponse()); });
    await waitFor(() => expect(result.current.stream.analyzing).toBe(false));
    await waitFor(() => expect(mockAdoptionRate).toHaveBeenCalledTimes(2));
  });

  it("진입 컨텍스트(?equipmentId=)가 있으면 명시적 선택으로 시작해 설비 목록이 와도 되돌아가지 않는다", async () => {
    mockEquipmentList.mockResolvedValue([
      { id: 9, name: "다른 설비", locationTag: null, processName: null, introducedOn: null },
    ]);
    const { result } = renderHook(() => useVision(), { wrapper: wrapperWithEquipmentId("42") });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.equipmentId).toBe(42);
  });

  it("감소대책 등록은 판독이 만든 평가와 초안의 지침 번호를 싣고, 이행 완료는 actionApi로 보낸다", async () => {
    const view = { id: 9, hazardId: 1, assessmentId: null, equipmentId: 1, content: "c", owner: "관리부", dueDate: "2026-10-16", status: "PENDING" as const, guideRef: "B-5-2011", completedAt: null, createdAt: "t" };
    vi.mocked(visionApi.createAction).mockResolvedValue(view);
    vi.mocked(actionApi.complete).mockResolvedValue({ ...view, status: "DONE", completedAt: "t2" });
    const { result } = renderHook(() => useVision(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));

    const cand = { hazardId: 1, accidentType: "PPE" as const, accidentLabel: "보호구", missingControl: "안전대 미착용", evidence: null, confidence: null, riskLevel: "HIGH" as const, ruleTrace: "r", adopted: true, alreadyKnown: false, gateStatus: "PHOTO" as const, gateNote: null, suggestedAction: { content: "c", lawRef: "l", lawTitle: null, guideRef: "B-5-2011" }, action: null, priorOpenAction: null };
    await act(async () => { await result.current.createAction(cand, { content: "  c  ", owner: "관리부", dueDate: "2026-10-16" }); });
    expect(visionApi.createAction).toHaveBeenCalledWith(1, { assessmentId: null, content: "c", owner: "관리부", dueDate: "2026-10-16", guideRef: "B-5-2011" });

    await act(async () => { await result.current.completeAction(1, 9); });
    expect(actionApi.complete).toHaveBeenCalledWith(9);
  });
});
