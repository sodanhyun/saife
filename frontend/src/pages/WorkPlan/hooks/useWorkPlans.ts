import { useCallback, useState } from "react";

import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import { useDebounce } from "@/hooks/useDebounce";
import { useToastStore } from "@/stores/useToastStore";
import type { WorkPlanStatus } from "@/types/domain";
import type { WorkPlanDetail } from "@/types/workPlan";
import { getServerMessage } from "@/utils/errorMessage";

/** 진행 중인 상세 모달 액션. 누른 버튼만 loading을 띄우기 위해 종류까지 기억한다. */
export type WorkPlanAction = "open" | "ack" | "approve" | "hold";

/** 점검 기록 한 번에 보이는 줄 수. "더 보기"마다 이만큼 늘린다 */
export const PAGE_SIZE = 10;

/** 화면 오류 문구에 내부 번호(ID, 상태 코드)가 섞여 나오지 않게 한다 */
function cleanMessage(raw: string | null | undefined, fallback: string): string {
  if (!raw) return fallback;
  if (/(id=|\[|\d{3}\b|Exception|Error)/i.test(raw)) return fallback;
  return raw;
}

/**
 * 점검 기록 목록(검색, 상태 필터, 더 보기), 상세, TBM 실시 확인, 승인, 작업 보류.
 * 목록은 서버 데이터에서만 파생한다(낙관적 플래그 없음).
 */
export function useWorkPlans() {
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState<WorkPlanStatus | null>(null);
  const [size, setSize] = useState(PAGE_SIZE);
  const debounced = useDebounce(keyword, 300);

  const { data, loading, error, refetch } = useApiData({
    fetchFn: (signal) => workPlanApi.search({ page: 0, size, keyword: debounced, status }, signal),
    deps: [debounced, status, size],
    errorMessage: "점검 기록을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  const [detail, setDetail] = useState<WorkPlanDetail | null>(null);
  const [busyAction, setBusyAction] = useState<WorkPlanAction | null>(null);
  // 셀렉터로 구독한다. 스토어 전체를 구독하면 토스트가 뜰 때마다 페이지가 다시 렌더된다
  const toastSuccess = useToastStore((s) => s.success);
  const toastError = useToastStore((s) => s.error);

  /** done이 없으면 조용히 처리한다. 목록을 여는 것까지 토스트를 띄우지 않는다. */
  const run = useCallback(async (action: WorkPlanAction, fn: () => Promise<WorkPlanDetail>, done?: string) => {
    setBusyAction(action);
    try {
      setDetail(await fn());
      if (done) toastSuccess(done);
      refetch();
    } catch (e) {
      toastError(cleanMessage(getServerMessage(e), action === "open" ? "점검 기록을 열지 못했습니다" : "점검 기록을 처리하지 못했습니다"));
    } finally {
      setBusyAction(null);
    }
  }, [refetch, toastSuccess, toastError]);

  const total = data?.totalElements ?? 0;
  const plans = data?.content ?? [];

  return {
    plans,
    total,
    hasMore: plans.length < total,
    loadMore: () => setSize((s) => s + PAGE_SIZE),
    keyword,
    setKeyword: (v: string) => { setKeyword(v); setSize(PAGE_SIZE); },
    status,
    setStatus: (v: WorkPlanStatus | null) => { setStatus(v); setSize(PAGE_SIZE); },
    loading,
    loadError: error,
    refetch,
    detail,
    busyAction,
    openDetail: (id: number) => run("open", () => workPlanApi.detail(id)),
    closeDetail: () => setDetail(null),
    acknowledge: (id: number) => run("ack", () => workPlanApi.acknowledge(id), "TBM 실시를 기록했습니다"),
    /** 승인자는 관리감독자(작업 담당 반장). condition이 있으면 조건부 승인(잠정조치) */
    approve: (id: number, approver: string, condition?: string) =>
      run("approve", () => workPlanApi.approve(id, approver || undefined, condition), condition ? "조건부 승인했습니다" : "승인했습니다"),
    /** 잠정조치가 작업 금지면 승인 대신 보류 */
    hold: (id: number, reason: string) => run("hold", () => workPlanApi.hold(id, reason), "작업 보류로 두었습니다"),
  };
}
