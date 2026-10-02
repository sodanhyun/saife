import { useState } from "react";

import { useSearchParams } from "react-router-dom";

import { equipmentApi } from "@/api/equipmentApi";
import { incidentApi } from "@/api/incidentApi";
import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import { defaultIncidentForm, toRegisterRequest, type IncidentFormState } from "@/pages/Incident/utils/incidentForm";
import { useToastStore } from "@/stores/useToastStore";
import type { IncidentRegisterResponse } from "@/types/incident";
import { getServerMessage } from "@/utils/errorMessage";

/**
 * 진입 컨텍스트 값 검증 — useVision의 `initialEquipmentIdFromQuery`·`useEntryEquipment`와
 * 같은 기준(유효한 양의 정수)을 쓴다. 잘못된 값("abc", "0", "-1" 등)이면 기본 폼으로
 * 떨어진다 — 원문 문자열을 그대로 폼에 흘려보내면 제출 시 `Number("abc")`가
 * `NaN`으로 서버에 실려 나간다.
 */
function initialEquipmentIdFromQuery(raw: string | null): string | null {
  if (!raw) return null;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? raw : null;
}

export function useIncident() {
  const [searchParams] = useSearchParams();
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const plans = useApiData({ fetchFn: (s) => workPlanApi.list(0, 20, s).then((p) => p.content), deps: [], errorMessage: "작업계획서 목록을 불러오지 못했습니다" });
  const incidents = useApiData({ fetchFn: (s) => incidentApi.list(0, 20, s).then((p) => p.content), deps: [], skipFirstSkeleton: true, errorMessage: "사고 목록을 불러오지 못했습니다" });
  // 진입 컨텍스트 — 설비 홈·상세에서 "사고 신고"로 들어오면 ?equipmentId=가 붙는다.
  // 명시적으로 골랐다고 취급해(effectiveForm의 "null이면 기본 설비" 파생을 건너뛰게) 그 값이 유지된다.
  const [form, setForm] = useState<IncidentFormState>(() => {
    const entryEquipmentId = initialEquipmentIdFromQuery(searchParams.get("equipmentId"));
    return entryEquipmentId ? { ...defaultIncidentForm(), equipmentId: entryEquipmentId } : defaultIncidentForm();
  });
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

  // 결과를 닫고 새 사고 입력으로 돌아간다. 고른 설비는 남기고 시각만 지금으로 되돌린다
  const reset = () => {
    setResponse(null);
    setError(null);
    setForm({ ...defaultIncidentForm(), equipmentId: effectiveForm.equipmentId });
    window.scrollTo({ top: 0 });
  };

  return {
    reset,
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
