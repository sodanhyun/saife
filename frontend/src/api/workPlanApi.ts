// workPlanApi.ts
import { api } from "@/api/client";
import { WORK_PLAN } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type { WorkPlanDetail, WorkPlanListItem } from "@/types/workPlan";

export const workPlanApi = {
  list: (page = 0, size = 20, signal?: AbortSignal) =>
    api.get<PaginationResponse<WorkPlanListItem>>(WORK_PLAN, { params: { page, size }, signal }).then((r) => r.data),
  detail: (id: number) => api.get<WorkPlanDetail>(`${WORK_PLAN}/${id}`).then((r) => r.data),
  acknowledge: (id: number) => api.post<WorkPlanDetail>(`${WORK_PLAN}/${id}/ack`).then((r) => r.data),
  approve: (id: number, approver?: string, condition?: string) =>
    api.post<WorkPlanDetail>(`${WORK_PLAN}/${id}/approve`, { approver, condition }).then((r) => r.data),
};
