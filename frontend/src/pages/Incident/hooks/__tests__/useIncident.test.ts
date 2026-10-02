import { createElement } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

vi.mock("@/api/equipmentApi", () => ({
  equipmentApi: { list: vi.fn() },
}));
vi.mock("@/api/workPlanApi", () => ({
  workPlanApi: { list: vi.fn() },
}));
vi.mock("@/api/incidentApi", () => ({
  incidentApi: { list: vi.fn(), register: vi.fn(), detail: vi.fn(), markSubmitted: vi.fn() },
}));
// useIncident는 셀렉터로 구독하므로(useToastStore((s) => s.success)) 셀렉터를 받는 함수이면서 getState도 갖는 형태로 만든다.
vi.mock("@/stores/useToastStore", () => {
  const state = { error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn() };
  type State = typeof state;
  const useToastStore = Object.assign(
    (selector?: (s: State) => unknown) => (selector ? selector(state) : state),
    { getState: () => state },
  );
  return { useToastStore };
});

import { equipmentApi } from "@/api/equipmentApi";
import { incidentApi } from "@/api/incidentApi";
import { workPlanApi } from "@/api/workPlanApi";
import { useIncident } from "@/pages/Incident/hooks/useIncident";

const mockEquipmentList = vi.mocked(equipmentApi.list);
const mockWorkPlanList = vi.mocked(workPlanApi.list);
const mockIncidentList = vi.mocked(incidentApi.list);
const mockRegister = vi.mocked(incidentApi.register);

beforeEach(() => {
  mockEquipmentList.mockReset();
  mockWorkPlanList.mockReset();
  mockIncidentList.mockReset();
  mockRegister.mockReset();
  mockEquipmentList.mockResolvedValue([
    { id: 7, name: "A", locationTag: null, processName: null, introducedOn: null },
  ]);
  mockWorkPlanList.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } as never);
  mockIncidentList.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } as never);
});

// useSearchParams는 <Router> 컨텍스트가 있어야 한다. 이 파일은 .ts라 JSX 없이 createElement로 감싼다.
const wrapper = ({ children }: { children: React.ReactNode }) => createElement(MemoryRouter, null, children);
const wrapperAt = (url: string) =>
  function Wrapper({ children }: { children: React.ReactNode }) {
    return createElement(MemoryRouter, { initialEntries: [url] }, children);
  };

describe("useIncident", () => {
  it("설비 목록이 와도 설비를 기본 선택하지 않는다(사람이 고른다)", async () => {
    const { result } = renderHook(() => useIncident(), { wrapper });
    await waitFor(() => expect(result.current.equipment.length).toBe(1));
    expect(result.current.form.equipmentId).toBe("");
    expect(result.current.form.incidentType).toBe("");
    expect(result.current.form.severity).toBe("");
  });

  it("필수 칸이 비면 제출하지 않고 칸별 오류를 낸다", async () => {
    const { result } = renderHook(() => useIncident(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      await result.current.submit();
    });
    expect(mockRegister).not.toHaveBeenCalled();
    expect(result.current.fieldErrors.equipmentId).toBeDefined();
    expect(result.current.fieldErrors.incidentType).toBeDefined();
    expect(result.current.fieldErrors.severity).toBeDefined();
  });

  it("사고 목록 조회가 실패하면 listError가 true가 된다", async () => {
    mockIncidentList.mockReset();
    mockIncidentList.mockRejectedValue(new Error("network error"));
    const { result } = renderHook(() => useIncident(), { wrapper });
    await waitFor(() => expect(result.current.listError).toBe(true));
  });

  it("진입 컨텍스트(?equipmentId=)가 있으면 그 설비로 시작한다", async () => {
    const { result } = renderHook(() => useIncident(), { wrapper: wrapperAt("/incident?equipmentId=42") });
    await waitFor(() => expect(result.current.equipment.length).toBe(1));
    expect(result.current.form.equipmentId).toBe("42");
  });

  it("진입 컨텍스트가 숫자가 아니면(?equipmentId=abc) 무시한다", async () => {
    const { result } = renderHook(() => useIncident(), { wrapper: wrapperAt("/incident?equipmentId=abc") });
    await waitFor(() => expect(result.current.equipment.length).toBe(1));
    expect(result.current.form.equipmentId).toBe("");
  });

  it("open(id)은 그 사고의 상세를 불러와 결과 화면으로 연다", async () => {
    const detail = vi.mocked(incidentApi.detail);
    detail.mockResolvedValue({ incident: { id: 3 } } as never);
    const scroll = vi.spyOn(window, "scrollTo").mockImplementation(() => {});

    const { result } = renderHook(() => useIncident(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      await result.current.open(3);
    });

    expect(detail).toHaveBeenCalledWith(3);
    expect(result.current.response?.incident.id).toBe(3);
    expect(result.current.opening).toBeNull();
    scroll.mockRestore();
  });

  it("?incidentId=로 들어오면 그 사고를 바로 연다", async () => {
    const detail = vi.mocked(incidentApi.detail);
    detail.mockReset();
    detail.mockResolvedValue({ incident: { id: 6 } } as never);
    const scroll = vi.spyOn(window, "scrollTo").mockImplementation(() => {});

    const { result } = renderHook(() => useIncident(), { wrapper: wrapperAt("/incident?incidentId=6") });
    await waitFor(() => expect(result.current.response?.incident.id).toBe(6));
    expect(detail).toHaveBeenCalledTimes(1);
    scroll.mockRestore();
  });
});
