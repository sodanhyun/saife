// useToday.ts — "오늘 할 일" 인박스가 읽는 데이터. 폴링 없음, RefreshButton으로만 갱신한다.
// 실패하면 error=true만 내고 토스트는 띄우지 않는다. 화면은 KPI와 인박스를 그리지 않고 조회 실패 안내를 보인다.
import { dashboardApi } from "@/api/dashboardApi";
import { useApiData } from "@/hooks/useApiData";

export function useToday() {
  const { data, loading, error, refetch } = useApiData({
    fetchFn: (signal) => dashboardApi.today(signal),
    deps: [],
  });
  return { view: data, loading, error, refetch };
}
