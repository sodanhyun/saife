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
  const plans = useApiData({ fetchFn: (s) => workPlanApi.list(0, 20, s).then((p) => p.content), deps: [] });
  const incidents = useApiData({ fetchFn: (s) => incidentApi.list(0, 20, s).then((p) => p.content), deps: [], skipFirstSkeleton: true });
  const [form, setForm] = useState<IncidentFormState>(defaultIncidentForm);
  const [response, setResponse] = useState<IncidentRegisterResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useToastStore();

  // 첫 설비를 기본 선택 — 렌더 중 파생(effect+setState로 동기화하지 않는다)
  const defaultEquipmentId = equipment.data?.[0] ? String(equipment.data[0].id) : "";
  const effectiveForm = form.equipmentId ? form : { ...form, equipmentId: defaultEquipmentId };

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      setResponse(await incidentApi.register(toRegisterRequest(effectiveForm)));
      toast.success("사고를 등록했습니다");
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
    form: effectiveForm,
    setForm,
    submit,
    busy,
    response,
    error,
  };
}
