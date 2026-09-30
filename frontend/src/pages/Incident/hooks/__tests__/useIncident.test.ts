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
  incidentApi: { list: vi.fn(), register: vi.fn() },
}));
// useApiData가 실패 시 토스트를 띄운다 — 이 테스트에서는 토스트 자체는 관심사가 아니다.
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

beforeEach(() => {
  mockEquipmentList.mockReset();
  mockWorkPlanList.mockReset();
  mockIncidentList.mockReset();
  mockEquipmentList.mockResolvedValue([
    { id: 7, name: "A", locationTag: null, processName: null, introducedOn: null },
  ]);
  mockWorkPlanList.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } as never);
  mockIncidentList.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } as never);
});

// useSearchParams는 <Router> 컨텍스트가 있어야 한다(진입 컨텍스트 ?equipmentId= 지원 추가로 필요해짐).
// 이 파일은 .ts라 JSX 없이 createElement로 감싼다.
const wrapper = ({ children }: { children: React.ReactNode }) => createElement(MemoryRouter, null, children);
const wrapperWithEquipmentId = (id: string) =>
  function Wrapper({ children }: { children: React.ReactNode }) {
    return createElement(MemoryRouter, { initialEntries: [`/incident?equipmentId=${id}`] }, children);
  };

describe("useIncident", () => {
  it("설비 목록이 오면 첫 설비를 기본 선택하고, 사용자가 '(설비 미상)'을 고르면 되돌아가지 않는다", async () => {
    const { result } = renderHook(() => useIncident(), { wrapper });

    await waitFor(() => expect(result.current.form.equipmentId).toBe("7"));

    act(() => {
      result.current.setForm({ ...result.current.form, equipmentId: "" });
    });

    // 다음 렌더에서도 명시적으로 고른 "" (설비 미상)이 기본값으로 되돌아가지 않아야 한다
    expect(result.current.form.equipmentId).toBe("");
  });

  it("사고 목록 조회가 실패하면 loadError가 true가 된다", async () => {
    mockIncidentList.mockReset();
    mockIncidentList.mockRejectedValue(new Error("network error"));

    const { result } = renderHook(() => useIncident(), { wrapper });

    await waitFor(() => expect(result.current.loadError).toBe(true));
  });

  it("진입 컨텍스트(?equipmentId=)가 있으면 설비 목록보다 먼저 그 값으로 시작하고, 기본 설비로 되돌아가지 않는다", async () => {
    const { result } = renderHook(() => useIncident(), { wrapper: wrapperWithEquipmentId("42") });

    // 첫 설비(id=7)가 나중에 도착해도 진입 컨텍스트가 이미 명시적 선택이라 덮이지 않는다
    await waitFor(() => expect(result.current.equipment.length).toBe(1));
    expect(result.current.form.equipmentId).toBe("42");
  });

  it("진입 컨텍스트가 숫자가 아니면(?equipmentId=abc) 무시하고 기본 폼으로 시작해 첫 설비를 기본 선택한다", async () => {
    const { result } = renderHook(() => useIncident(), { wrapper: wrapperWithEquipmentId("abc") });

    // "abc"를 그대로 흘려보내면 제출 시 Number("abc")=NaN이 서버로 나간다 — 반드시 기본 폼으로 떨어져야 한다.
    await waitFor(() => expect(result.current.form.equipmentId).toBe("7"));
  });
});
