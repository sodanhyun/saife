import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";

/** 점검표의 유사 재해사례(원인과 대책). 점검표가 바뀌면 다시 받는다 */
export function useSimilarCases(workPlanId: number) {
  const { data } = useApiData({
    fetchFn: (signal) => workPlanApi.cases(workPlanId, signal),
    deps: [workPlanId],
    skipFirstSkeleton: true,
  });
  return data ?? [];
}
