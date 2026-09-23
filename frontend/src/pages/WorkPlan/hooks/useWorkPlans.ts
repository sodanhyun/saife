import { useCallback, useState } from "react";

import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import type { WorkPlanDetail } from "@/types/workPlan";
import { getServerMessage } from "@/utils/errorMessage";

/** 진행 중인 상세 모달 액션 — 누른 버튼만 loading을 띄우기 위해 종류까지 기억한다. */
export type WorkPlanAction = "open" | "ack" | "approve";

/** 목록·상세·확인·승인. 목록은 서버 데이터에서만 파생한다(낙관적 플래그 없음). */
export function useWorkPlans() {
  const { data, loading, error, refetch } = useApiData({
    fetchFn: (signal) => workPlanApi.list(0, 10, signal).then((p) => p.content),
    deps: [],
    errorMessage: "작업계획서 목록을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  const [detail, setDetail] = useState<WorkPlanDetail | null>(null);
  const [busyAction, setBusyAction] = useState<WorkPlanAction | null>(null);
  // 셀렉터로 구독 — 스토어 전체를 구독하면 토스트가 뜰 때마다 페이지가 다시 렌더된다
  const toastSuccess = useToastStore((s) => s.success);
  const toastError = useToastStore((s) => s.error);

  /** done이 없으면 조용히 처리한다 — 목록을 여는 것까지 시끄러운 토스트를 띄우지 않는다. */
  const run = useCallback(async (action: WorkPlanAction, fn: () => Promise<WorkPlanDetail>, done?: string) => {
    setBusyAction(action);
    try {
      setDetail(await fn());
      if (done) toastSuccess(done);
      refetch();
    } catch (e) {
      toastError(getServerMessage(e) ?? "처리하지 못했습니다");
    } finally {
      setBusyAction(null);
    }
  }, [refetch, toastSuccess, toastError]);

  return {
    plans: data ?? [],
    loading,
    loadError: error,
    refetch,
    detail,
    busyAction,
    openDetail: (id: number) => run("open", () => workPlanApi.detail(id)),
    closeDetail: () => setDetail(null),
    acknowledge: (id: number) => run("ack", () => workPlanApi.acknowledge(id), "브리핑 확인이 기록됐습니다 (TBM)"),
    approve: (id: number) => run("approve", () => workPlanApi.approve(id, "관리부"), "승인했습니다"),
  };
}
