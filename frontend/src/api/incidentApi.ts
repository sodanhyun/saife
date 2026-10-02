// incidentApi.ts
import { api } from "@/api/client";
import { INCIDENT } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type {
  FollowUpDetail,
  FollowUpRequest,
  IncidentListItem,
  IncidentRegisterResponse,
  RegisterIncidentRequest,
} from "@/types/incident";

export const incidentApi = {
  register: (body: RegisterIncidentRequest) => api.post<IncidentRegisterResponse>(INCIDENT, body).then((r) => r.data),
  detail: (id: number) => api.get<IncidentRegisterResponse>(`${INCIDENT}/${id}`).then((r) => r.data),
  list: (page = 0, size = 20, signal?: AbortSignal) =>
    api.get<PaginationResponse<IncidentListItem>>(INCIDENT, { params: { page, size }, signal }).then((r) => r.data),
  /** 산업재해조사표 제출 완료 처리(제출일은 오늘) */
  markSubmitted: (id: number) => api.post<IncidentListItem>(`${INCIDENT}/${id}/submitted`).then((r) => r.data),
  /** 사고가 만든 수시평가 */
  followUp: (assessmentId: number, signal?: AbortSignal) =>
    api.get<FollowUpDetail>(`${INCIDENT}/follow-up/${assessmentId}`, { signal }).then((r) => r.data),
  saveFollowUp: (assessmentId: number, body: FollowUpRequest) =>
    api.put<FollowUpDetail>(`${INCIDENT}/follow-up/${assessmentId}`, body).then((r) => r.data),
  /** 확정. 같은 설비의 작업 보류를 재승인 대기로 돌린다 */
  confirmFollowUp: (assessmentId: number, body: FollowUpRequest) =>
    api.post<FollowUpDetail>(`${INCIDENT}/follow-up/${assessmentId}/confirm`, body).then((r) => r.data),
};
