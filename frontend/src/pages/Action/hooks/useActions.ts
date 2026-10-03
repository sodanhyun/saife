import { useCallback, useState } from "react";

import { useSearchParams } from "react-router-dom";

import { actionApi } from "@/api/actionApi";
import { useApiData } from "@/hooks/useApiData";
import { useDebounce } from "@/hooks/useDebounce";
import { parseFilter } from "@/pages/Action/utils/actionRow";
import { useToastStore } from "@/stores/useToastStore";
import type { ActionListFilter, ActionListItem } from "@/types/action";
import { getServerMessage } from "@/utils/errorMessage";

/** 한 번에 보이는 줄 수. "더 보기"마다 이만큼 늘린다 */
export const PAGE_SIZE = 20;

/**
 * 개선대책 목록(탭, 검색, 더 보기)과 이행 완료.
 * 탭은 URL ?status=가 원천이다(홈에서 ?status=OVERDUE로 들어온다). 목록은 서버 데이터에서만 파생한다.
 */
export function useActions() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filter = parseFilter(searchParams.get("status"));
  const [keyword, setKeywordState] = useState("");
  const [size, setSize] = useState(PAGE_SIZE);
  const debounced = useDebounce(keyword, 300);

  const list = useApiData({
    fetchFn: (signal) => actionApi.search({ status: filter, keyword: debounced, page: 0, size }, signal),
    deps: [filter, debounced, size],
    errorMessage: "개선대책을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  const counts = useApiData({ fetchFn: (signal) => actionApi.counts(signal), deps: [], skipFirstSkeleton: true });

  const [target, setTarget] = useState<ActionListItem | null>(null);
  const [completing, setCompleting] = useState(false);
  const toastSuccess = useToastStore((s) => s.success);
  const toastError = useToastStore((s) => s.error);

  const refetchList = list.refetch;
  const refetchCounts = counts.refetch;

  const confirmComplete = useCallback(async () => {
    if (!target) return;
    setCompleting(true);
    try {
      await actionApi.complete(target.id);
      toastSuccess("이행 완료로 기록함");
      setTarget(null);
      refetchList();
      refetchCounts();
    } catch (e) {
      toastError(getServerMessage(e) ?? "이행 완료를 기록하지 못했습니다");
    } finally {
      setCompleting(false);
    }
  }, [target, toastSuccess, toastError, refetchList, refetchCounts]);

  const setFilter = (next: ActionListFilter) => {
    setSize(PAGE_SIZE);
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (next === "OPEN") p.delete("status");
        else p.set("status", next);
        return p;
      },
      { replace: true },
    );
  };

  const actions = list.data?.content ?? [];
  const total = list.data?.totalElements ?? 0;

  return {
    filter,
    setFilter,
    keyword,
    setKeyword: (v: string) => {
      setKeywordState(v);
      setSize(PAGE_SIZE);
    },
    searching: debounced.trim() !== "",
    actions,
    total,
    hasMore: actions.length < total,
    loadMore: () => setSize((s) => s + PAGE_SIZE),
    counts: counts.data,
    loading: list.loading && !list.initialLoaded,
    loadError: list.error,
    refetch: () => {
      refetchList();
      refetchCounts();
    },
    target,
    requestComplete: (row: ActionListItem) => setTarget(row),
    cancelComplete: () => {
      if (!completing) setTarget(null);
    },
    confirmComplete: () => void confirmComplete(),
    completing,
  };
}
