// useToday.ts — "오늘 할 일" 인박스가 읽는 데이터. 폴링 없음, RefreshButton으로만 갱신한다.
// 백엔드 4a 이전에는 GET /api/dashboard/today가 500을 낸다 — 그 에러는 여기서 그대로
// error=true로만 노출하고, 화면 쪽(TodayInbox)이 조용히 흡수한다(토스트·배너 없음).
import { dashboardApi } from "@/api/dashboardApi";
import { useApiData } from "@/hooks/useApiData";

export function useToday() {
  const { data, loading, error, refetch } = useApiData({
    fetchFn: (signal) => dashboardApi.today(signal),
    deps: [],
  });
  return { view: data, loading, error, refetch };
}
