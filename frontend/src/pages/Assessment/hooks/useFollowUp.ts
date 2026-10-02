import { useState } from "react";

import { isAxiosError } from "axios";

import { incidentApi } from "@/api/incidentApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import type { FollowUpDetail, FollowUpRequest } from "@/types/incident";
import { getServerMessage } from "@/utils/errorMessage";

/**
 * 사고가 만든 수시평가 한 건. 없는 번호(404)는 오류가 아니라 "찾을 수 없음"(data=null)으로 읽는다.
 * 확정하면 서버가 돌려준 최신 상태(확정일, 보류 해제)로 바꾼다. 낙관적으로 상태를 바꾸지 않는다.
 */
export function useFollowUp(assessmentId: number | null) {
  const q = useApiData<FollowUpDetail | null>({
    fetchFn: async (signal) => {
      try {
        return await incidentApi.followUp(assessmentId as number, signal);
      } catch (e) {
        if (isAxiosError(e) && e.response?.status === 404) return null;
        throw e;
      }
    },
    deps: [assessmentId],
    enabled: assessmentId !== null,
  });
  const [saved, setSaved] = useState<FollowUpDetail | null>(null);
  const [confirming, setConfirming] = useState(false);
  const toastSuccess = useToastStore((s) => s.success);
  const toastError = useToastStore((s) => s.error);

  const detail = saved && saved.assessmentId === assessmentId ? saved : (q.data ?? null);

  /** 확정. 성공하면 true */
  const confirm = async (body: FollowUpRequest): Promise<boolean> => {
    if (assessmentId === null) return false;
    setConfirming(true);
    try {
      const next = await incidentApi.confirmFollowUp(assessmentId, body);
      setSaved(next);
      toastSuccess(next.workPlans.length > 0 ? "수시평가를 확정했습니다. 작업 보류를 풀었습니다" : "수시평가를 확정했습니다");
      return true;
    } catch (e) {
      toastError(getServerMessage(e) ?? "수시평가를 확정하지 못했습니다");
      return false;
    } finally {
      setConfirming(false);
    }
  };

  return {
    detail,
    loading: assessmentId !== null && q.loading && !q.initialLoaded,
    notFound: assessmentId === null || (q.initialLoaded && !q.error && q.data === null),
    error: q.error,
    refetch: q.refetch,
    confirm,
    confirming,
  };
}
