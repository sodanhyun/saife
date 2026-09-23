// src/hooks/useApiData.ts
import { useState, useEffect, useCallback, useRef } from "react";

import { isAbortError } from "@/utils/abortRegistry";
import { useToastStore } from "@/stores/useToastStore";

type PrimitiveDep = string | number | boolean | null | undefined;

interface UseApiDataOptions<T> {
  /** 데이터를 가져오는 함수. AbortSignal을 받아 취소를 지원해야 한다. */
  fetchFn: (signal: AbortSignal) => Promise<T>;
  /** useEffect 의존성 배열. 객체/배열 금지 — primitive만 허용. */
  deps: PrimitiveDep[];
  /** 에러 시 표시할 토스트 메시지. 생략하면 토스트 없음. */
  errorMessage?: string;
  /** true이면 initialLoaded 이후 재요청 시 loading을 true로 바꾸지 않는다. */
  skipFirstSkeleton?: boolean;
  /** false이면 fetch를 실행하지 않는다. 기본 true. */
  enabled?: boolean;
}

interface UseApiDataReturn<T> {
  data: T | null;
  loading: boolean;
  error: boolean;
  initialLoaded: boolean;
  refetch: () => void;
}

export function useApiData<T>({
  fetchFn,
  deps,
  errorMessage,
  skipFirstSkeleton = false,
  enabled = true,
}: UseApiDataOptions<T>): UseApiDataReturn<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [initialLoaded, setInitialLoaded] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);

  // skipFirstSkeleton: initialLoaded 이후에는 loading 상태를 바꾸지 않음
  const initialLoadedRef = useRef(false);

  useEffect(() => {
    if (!enabled) return;

    const controller = new AbortController();

    const load = async () => {
      // initialLoaded 이후 + skipFirstSkeleton이면 loading 유지
      if (!(skipFirstSkeleton && initialLoadedRef.current)) {
        setLoading(true);
      }
      setError(false);

      try {
        const result = await fetchFn(controller.signal);
        setData(result);
      } catch (err) {
        if (isAbortError(err)) return;
        setError(true);
        if (errorMessage) {
          useToastStore.getState().error(errorMessage);
        }
      } finally {
        // abort된(구) 요청의 finally가 새 요청의 loading=true를 덮어쓰는 레이스 방지(P2-3) —
        // 이 effect가 이미 cleanup(abort)됐다면 신 effect가 이미 자기 상태를 관리 중이므로 건너뛴다.
        if (!controller.signal.aborted) {
          setLoading(false);
          setInitialLoaded(true);
          initialLoadedRef.current = true;
        }
      }
    };

    load();
    return () => controller.abort();
  }, [...deps, refreshKey, enabled]); // eslint-disable-line react-hooks/exhaustive-deps

  const refetch = useCallback(() => setRefreshKey((k) => k + 1), []);

  // enabled=false면 fetch 자체가 없으므로 로딩·에러 아님 — effect 내 동기 setState 대신 파생
  return { data, loading: enabled ? loading : false, error: enabled ? error : false, initialLoaded, refetch };
}
