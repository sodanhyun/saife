import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/equipmentApi", () => ({
  equipmentApi: { list: vi.fn() },
}));
vi.mock("@/api/workPlanApi", () => ({
  workPlanApi: { list: vi.fn() },
}));
vi.mock("@/api/incidentApi", () => ({
  incidentApi: { list: vi.fn(), register: vi.fn() },
}));

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

describe("useIncident", () => {
  it("설비 목록이 오면 첫 설비를 기본 선택하고, 사용자가 '(설비 미상)'을 고르면 되돌아가지 않는다", async () => {
    const { result } = renderHook(() => useIncident());

    await waitFor(() => expect(result.current.form.equipmentId).toBe("7"));

    act(() => {
      result.current.setForm({ ...result.current.form, equipmentId: "" });
    });

    // 다음 렌더에서도 명시적으로 고른 "" (설비 미상)이 기본값으로 되돌아가지 않아야 한다
    expect(result.current.form.equipmentId).toBe("");
  });
});
