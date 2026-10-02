// workPlanApi.ts
import { api } from "@/api/client";
import { WORK_PLAN } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type { WorkPlanStatus } from "@/types/domain";
import type { WorkPlanDetail, WorkPlanListItem } from "@/types/workPlan";

/** 점검 기록 목록 조건. keyword는 작업명 또는 설비명 부분 일치 */
export interface WorkPlanListQuery {
  page?: number;
  size?: number;
  keyword?: string;
  status?: WorkPlanStatus | null;
}

export const workPlanApi = {
  list: (page = 0, size = 20, signal?: AbortSignal) =>
    api.get<PaginationResponse<WorkPlanListItem>>(WORK_PLAN, { params: { page, size }, signal }).then((r) => r.data),
  /** 점검 기록 검색(작업명, 설비명, 상태) */
  search: ({ page = 0, size = 20, keyword, status }: WorkPlanListQuery = {}, signal?: AbortSignal) =>
    api.get<PaginationResponse<WorkPlanListItem>>(WORK_PLAN, {
      params: { page, size, keyword: keyword?.trim() || undefined, status: status ?? undefined },
      signal,
    }).then((r) => r.data),
  detail: (id: number) => api.get<WorkPlanDetail>(`${WORK_PLAN}/${id}`).then((r) => r.data),
  acknowledge: (id: number) => api.post<WorkPlanDetail>(`${WORK_PLAN}/${id}/ack`).then((r) => r.data),
  approve: (id: number, approver?: string, condition?: string) =>
    api.post<WorkPlanDetail>(`${WORK_PLAN}/${id}/approve`, { approver, condition }).then((r) => r.data),
  /** 작업 보류. 잠정조치가 작업 금지라 승인할 수 없을 때 */
  hold: (id: number, reason: string) => api.post<WorkPlanDetail>(`${WORK_PLAN}/${id}/hold`, { reason }).then((r) => r.data),
};
