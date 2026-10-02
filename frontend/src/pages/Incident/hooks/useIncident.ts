import { useEffect, useRef, useState } from "react";

import { useSearchParams } from "react-router-dom";

import { equipmentApi } from "@/api/equipmentApi";
import { incidentApi } from "@/api/incidentApi";
import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import {
  defaultIncidentForm,
  toRegisterRequest,
  validateIncidentForm,
  type IncidentFormErrors,
  type IncidentFormState,
} from "@/pages/Incident/utils/incidentForm";
import { useToastStore } from "@/stores/useToastStore";
import type { IncidentRegisterResponse } from "@/types/incident";
import { nowLocalInput } from "@/utils/datetime";
import { getServerMessage } from "@/utils/errorMessage";

/**
 * 진입 컨텍스트 값 검증(유효한 양의 정수). 잘못된 값("abc", "0", "-1" 등)이면 무시한다 —
 * 원문 문자열을 그대로 흘려보내면 제출 시 `Number("abc")`가 `NaN`으로 서버에 실려 나간다.
 */
function positiveId(raw: string | null): string | null {
  if (!raw) return null;
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? raw : null;
}

export function useIncident() {
  const [searchParams] = useSearchParams();
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [] });
  const plans = useApiData({ fetchFn: (s) => workPlanApi.list(0, 20, s).then((p) => p.content), deps: [] });
  const incidents = useApiData({
    fetchFn: (s) => incidentApi.list(0, 20, s).then((p) => p.content),
    deps: [],
    skipFirstSkeleton: true,
  });
  // 진입 컨텍스트: 설비 홈, 상세에서 "사고 보고"로 들어오면 ?equipmentId=가 붙는다. 설비 기본값은 없다(사람이 고른다)
  const [form, setForm] = useState<IncidentFormState>(() => {
    const entryEquipmentId = positiveId(searchParams.get("equipmentId"));
    return entryEquipmentId ? { ...defaultIncidentForm(), equipmentId: entryEquipmentId } : defaultIncidentForm();
  });
  const [fieldErrors, setFieldErrors] = useState<IncidentFormErrors>({});
  const [response, setResponse] = useState<IncidentRegisterResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [opening, setOpening] = useState<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 셀렉터로 구독: 스토어 전체를 구독하면 토스트가 뜰 때마다 이 훅(=페이지)이 다시 렌더된다
  const toastSuccess = useToastStore((s) => s.success);
  const toastError = useToastStore((s) => s.error);

  const updateForm = (next: IncidentFormState) => {
    setForm(next);
    // 고친 칸의 오류는 바로 지운다(다시 제출할 때 전체를 다시 검사한다)
    if (Object.keys(fieldErrors).length > 0) {
      const remaining = validateIncidentForm(next);
      setFieldErrors((prev) => {
        const out: IncidentFormErrors = {};
        (Object.keys(prev) as (keyof IncidentFormErrors)[]).forEach((k) => {
          if (remaining[k]) out[k] = remaining[k];
        });
        return out;
      });
    }
  };

  const submit = async () => {
    const errors = validateIncidentForm(form);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;
    setBusy(true);
    setError(null);
    try {
      setResponse(await incidentApi.register(toRegisterRequest(form)));
      toastSuccess("사고를 보고했습니다");
      incidents.refetch();
    } catch (e) {
      setError(getServerMessage(e) ?? "사고를 보고하지 못했습니다");
    } finally {
      setBusy(false);
    }
  };

  /** 이력 표에서 행을 누르면 그 사고의 결과 화면을 다시 연다. 불러오는 동안 그 행에 표시가 뜬다 */
  const open = async (id: number) => {
    setOpening(id);
    try {
      setResponse(await incidentApi.detail(id));
      window.scrollTo({ top: 0 });
    } catch (e) {
      toastError(getServerMessage(e) ?? "사고를 불러오지 못했습니다");
    } finally {
      setOpening(null);
    }
  };

  // ?incidentId=로 들어오면(홈 오늘 할 일, 수시평가 화면의 사고 링크) 그 사고를 바로 연다. 처음 한 번만
  const entryIncidentId = positiveId(searchParams.get("incidentId"));
  const openedEntry = useRef(false);
  useEffect(() => {
    if (!entryIncidentId || openedEntry.current) return;
    openedEntry.current = true;
    void open(Number(entryIncidentId));
    // open은 매 렌더 새로 만들어지지만 한 번만 부르므로 의존성에서 뺀다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entryIncidentId]);

  /** 산업재해조사표 제출 완료 처리. 결과 화면의 조사표 카드와 이력 표가 같이 바뀐다 */
  const markSubmitted = async () => {
    if (!response) return;
    setSubmitting(true);
    try {
      const item = await incidentApi.markSubmitted(response.incident.id);
      setResponse({
        ...response,
        reportDuty: {
          ...response.reportDuty,
          status: item.reportStatus,
          statusLabel: item.reportStatusLabel,
          daysRemaining: item.daysRemaining,
          submittedOn: item.reportSubmittedOn,
        },
      });
      toastSuccess("제출 완료로 기록했습니다");
      incidents.refetch();
    } catch (e) {
      toastError(getServerMessage(e) ?? "제출 완료로 기록하지 못했습니다");
    } finally {
      setSubmitting(false);
    }
  };

  // 결과를 닫고 새 사고 입력으로 돌아간다. 고른 설비는 남기고 시각만 지금으로 되돌린다
  const reset = () => {
    setResponse(null);
    setError(null);
    setFieldErrors({});
    setForm({ ...defaultIncidentForm(), equipmentId: form.equipmentId });
    window.scrollTo({ top: 0 });
  };

  return {
    reset,
    open,
    opening,
    markSubmitted,
    submitting,
    equipment: equipment.data ?? [],
    plans: plans.data ?? [],
    incidents: incidents.data ?? [],
    loading: equipment.loading || plans.loading,
    /** 폼 재료(설비, 작업 목록)를 못 불러왔다 */
    formLoadError: equipment.error || plans.error,
    /** 사고 이력을 못 불러왔다. 이때 빈 표를 함께 그리지 않는다 */
    listError: incidents.error,
    refetchList: incidents.refetch,
    form,
    setForm: updateForm,
    fieldErrors,
    maxOccurredAt: nowLocalInput(),
    submit,
    busy,
    response,
    error,
  };
}
