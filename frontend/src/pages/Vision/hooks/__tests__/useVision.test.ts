// 미리보기 object URL 누수 방지 — pick()이 이전 URL을 revoke하고, 언마운트 시 마지막 URL도 정리한다.
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/equipmentApi", () => ({
  equipmentApi: { list: vi.fn() },
}));
vi.mock("@/api/visionApi", () => ({
  visionApi: { adoptionRate: vi.fn(), adopt: vi.fn(), reject: vi.fn() },
}));
vi.mock("@/api/client", () => ({ fetchWithAuth: vi.fn(), ApiError: class extends Error {} }));

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

describe("useVision", () => {
  it("pick()마다 이전 미리보기 URL을 revoke하고, 언마운트 시 마지막 URL도 정리한다", async () => {
    const createSpy = vi.fn().mockReturnValueOnce("blob:1").mockReturnValueOnce("blob:2");
    const revokeSpy = vi.fn();
    // jsdom엔 URL.createObjectURL이 없다 — 직접 정의한다.
    Object.defineProperty(URL, "createObjectURL", { value: createSpy, writable: true });
    Object.defineProperty(URL, "revokeObjectURL", { value: revokeSpy, writable: true });

    const { result, unmount } = renderHook(() => useVision());
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

    const { result } = renderHook(() => useVision());
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
});
