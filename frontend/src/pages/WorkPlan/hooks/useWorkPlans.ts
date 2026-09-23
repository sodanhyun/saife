import { useCallback, useState } from "react";

import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import type { WorkPlanDetail } from "@/types/workPlan";
import { getServerMessage } from "@/utils/errorMessage";

/** 목록·상세·확인·승인. 목록은 서버 데이터에서만 파생한다(낙관적 플래그 없음). */
export function useWorkPlans() {
  const { data, loading, refetch } = useApiData({
    fetchFn: (signal) => workPlanApi.list(0, 10, signal).then((p) => p.content),
    deps: [],
    errorMessage: "작업계획서 목록을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  const [detail, setDetail] = useState<WorkPlanDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const toast = useToastStore();

  /** done이 없으면 조용히 처리한다 — 목록을 여는 것까지 시끄러운 토스트를 띄우지 않는다. */
  const run = useCallback(async (fn: () => Promise<WorkPlanDetail>, done?: string) => {
    setBusy(true);
    try {
      setDetail(await fn());
      if (done) toast.success(done);
      refetch();
    } catch (e) {
      toast.error(getServerMessage(e) ?? "처리하지 못했습니다");
    } finally {
      setBusy(false);
    }
  }, [refetch, toast]);

  return {
    plans: data ?? [],
    loading,
    refetch,
    detail,
    busy,
    openDetail: (id: number) => run(() => workPlanApi.detail(id)),
    closeDetail: () => setDetail(null),
    acknowledge: (id: number) => run(() => workPlanApi.acknowledge(id), "브리핑 확인이 기록됐습니다 (TBM)"),
    approve: (id: number) => run(() => workPlanApi.approve(id, "관리부"), "승인했습니다"),
  };
}
