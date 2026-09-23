import { useState } from "react";

import { equipmentApi } from "@/api/equipmentApi";
import { incidentApi } from "@/api/incidentApi";
import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import { defaultIncidentForm, toRegisterRequest, type IncidentFormState } from "@/pages/Incident/utils/incidentForm";
import { useToastStore } from "@/stores/useToastStore";
import type { IncidentRegisterResponse } from "@/types/incident";
import { getServerMessage } from "@/utils/errorMessage";

export function useIncident() {
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const plans = useApiData({ fetchFn: (s) => workPlanApi.list(0, 20, s).then((p) => p.content), deps: [], errorMessage: "작업계획서 목록을 불러오지 못했습니다" });
  const incidents = useApiData({ fetchFn: (s) => incidentApi.list(0, 20, s).then((p) => p.content), deps: [], skipFirstSkeleton: true, errorMessage: "사고 목록을 불러오지 못했습니다" });
  const [form, setForm] = useState<IncidentFormState>(defaultIncidentForm);
  const [response, setResponse] = useState<IncidentRegisterResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 셀렉터로 구독 — 스토어 전체를 구독하면 토스트가 뜰 때마다 이 훅(=페이지)이 다시 렌더된다
  const toastSuccess = useToastStore((s) => s.success);

  // 첫 설비를 기본 선택 — 렌더 중 파생(effect+setState로 동기화하지 않는다).
  // equipmentId===null은 "아직 고르지 않음"이고, ""는 사용자가 명시적으로 고른 "(설비 미상)"이라
  // 한 번 골라지면 다시 기본값으로 되돌리지 않는다(null일 때만 기본 설비를 채운다).
  const defaultEquipmentId = equipment.data?.[0] ? String(equipment.data[0].id) : "";
  const effectiveForm = form.equipmentId === null ? { ...form, equipmentId: defaultEquipmentId } : form;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      setResponse(await incidentApi.register(toRegisterRequest(effectiveForm)));
      toastSuccess("사고를 등록했습니다");
      incidents.refetch();
    } catch (e) {
      setError(getServerMessage(e) ?? "사고를 등록하지 못했습니다");
    } finally {
      setBusy(false);
    }
  };

  return {
    equipment: equipment.data ?? [],
    plans: plans.data ?? [],
    incidents: incidents.data ?? [],
    loading: equipment.loading || plans.loading,
    loadError: equipment.error || plans.error || incidents.error,
    form: effectiveForm,
    setForm,
    submit,
    busy,
    response,
    error,
  };
}
