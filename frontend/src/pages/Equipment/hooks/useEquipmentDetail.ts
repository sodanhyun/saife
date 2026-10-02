// useEquipmentDetail.ts — 설비 상세가 읽는 이력. 없는 설비(404)는 오류가 아니라 "찾을 수 없음"으로 구분한다.
import { isAxiosError } from "axios";

import { timelineApi } from "@/api/timelineApi";
import { useApiData } from "@/hooks/useApiData";
import type { EquipmentTimeline } from "@/types/timeline";

const NOT_FOUND = "NOT_FOUND" as const;

/** id가 숫자가 아니면 요청하지 않고 바로 "찾을 수 없음"이다 */
export function useEquipmentDetail(id: number | null) {
  const { data, loading, error, initialLoaded, refetch } = useApiData<EquipmentTimeline | typeof NOT_FOUND>({
    fetchFn: (signal) => timelineApi.byEquipment(id!, signal).catch((e: unknown) => {
      if (isAxiosError(e) && e.response?.status === 404) return NOT_FOUND;
      throw e;
    }),
    deps: [id],
    enabled: id !== null,
    errorMessage: "설비 이력을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  return {
    timeline: data && data !== NOT_FOUND ? data : null,
    notFound: id === null || data === NOT_FOUND,
    loading: id !== null && loading && !initialLoaded,
    loadError: error,
    refetch,
  };
}
