// useEquipmentCards.ts — 설비 홈이 읽는 카드 목록. 정렬은 백엔드가 emphasis 순으로 이미 마쳤다.
import { dashboardApi } from "@/api/dashboardApi";
import { useApiData } from "@/hooks/useApiData";

export function useEquipmentCards() {
  const { data, loading, error, refetch } = useApiData({
    fetchFn: (signal) => dashboardApi.cards(signal),
    deps: [],
    errorMessage: "설비 카드를 불러오지 못했습니다",
  });
  return { cards: data ?? [], loading, loadError: error, refetch };
}
