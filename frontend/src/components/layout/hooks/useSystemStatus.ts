// useSystemStatus.ts — 사이드바 하단 SystemStatusLine이 쓰는 전역 시스템 상태 조회.
// 폴링 없음, 최초 1회 + 수동 새로고침만. 로딩·에러 표시는 SystemStatusLine 자체 판단에 맡긴다.
import { systemApi } from "@/api/systemApi";
import { useApiData } from "@/hooks/useApiData";

export function useSystemStatus() {
  const { data, loading, refetch } = useApiData({
    fetchFn: (signal) => systemApi.status(signal),
    deps: [],
  });
  return { status: data, loading, refetch };
}
