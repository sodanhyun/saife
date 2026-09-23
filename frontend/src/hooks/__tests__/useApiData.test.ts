// src/hooks/__tests__/useApiData.test.ts
import { act } from "react";

import { renderHook, waitFor } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";

import { useApiData } from "@/hooks/useApiData";

// useToastStore mock
vi.mock("@/stores/useToastStore", () => ({
  useToastStore: {
    getState: () => ({ error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn() }),
  },
}));

// isAbortError mock
vi.mock("@/utils/abortRegistry", () => ({
  isAbortError: (err: unknown) =>
    err instanceof Error && err.name === "AbortError",
}));

describe("useApiData", () => {
  it("fetch 성공 시 data를 반환하고 loading=false", async () => {
    const mockFn = vi.fn().mockResolvedValue({ value: 42 });

    const { result } = renderHook(() =>
      useApiData({
        fetchFn: mockFn,
        deps: ["test"],
        errorMessage: "실패",
      })
    );

    expect(result.current.loading).toBe(true);

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.data).toEqual({ value: 42 });
    expect(result.current.error).toBe(false);
    expect(result.current.initialLoaded).toBe(true);
  });

  it("fetch 실패 시 error=true", async () => {
    const mockFn = vi.fn().mockRejectedValue(new Error("Network error"));

    const { result } = renderHook(() =>
      useApiData({
        fetchFn: mockFn,
        deps: ["test"],
        errorMessage: "실패 메시지",
      })
    );

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.error).toBe(true);
    expect(result.current.data).toBeNull();
  });

  it("enabled=false이면 fetch를 실행하지 않음", async () => {
    const mockFn = vi.fn().mockResolvedValue("data");

    const { result } = renderHook(() =>
      useApiData({
        fetchFn: mockFn,
        deps: ["test"],
        enabled: false,
      })
    );

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(mockFn).not.toHaveBeenCalled();
    expect(result.current.data).toBeNull();
  });

  it("deps 변경으로 구 요청이 abort된 뒤에도 신 요청의 loading을 덮어쓰지 않음 (레이스, P2-3)", async () => {
    // 호출마다 별도 resolve/reject 핸들을 잡아, 구 요청(idx0)의 abort reject가
    // 신 요청(idx1) 완료보다 먼저 처리되는 실제 레이스 타이밍을 재현한다.
    let callCount = 0;
    const rejecters: Array<(e: Error) => void> = [];
    const resolvers: Array<(v: string) => void> = [];
    const mockFn = vi.fn((signal: AbortSignal) => {
      const idx = callCount++;
      return new Promise<string>((resolve, reject) => {
        resolvers[idx] = resolve;
        rejecters[idx] = reject;
        signal.addEventListener("abort", () => {
          const err = new Error("Aborted");
          err.name = "AbortError";
          reject(err);
        });
      });
    });

    const { result, rerender } = renderHook(
      ({ dep }: { dep: string }) => useApiData({ fetchFn: mockFn, deps: [dep] }),
      { initialProps: { dep: "a" } }
    );

    expect(result.current.loading).toBe(true);

    // deps 변경 → 구 effect cleanup(abort, idx0 reject) → 신 effect 시작(idx1 fetch, loading=true)
    await act(async () => {
      rerender({ dep: "b" });
      // 구 요청의 abort reject 마이크로태스크가 처리될 시간을 준다.
      await Promise.resolve();
      await Promise.resolve();
    });

    // 신 요청(idx1)이 아직 진행 중 — 구 요청의 finally가 loading을 꺼뜨리면 안 된다.
    expect(result.current.loading).toBe(true);
    expect(rejecters[0]).toBeDefined();

    await act(async () => {
      resolvers[1]("second");
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });
    expect(result.current.data).toBe("second");
    expect(result.current.error).toBe(false);
  });
});
