import { get, post } from "@/api/client";
import type { PaginationResponse } from "@/types/common";
import type { EquipmentItem } from "@/types/equipment";
import type {
  IncidentListItem,
  IncidentRegisterResponse,
  RegisterIncidentRequest,
} from "@/types/incident";
import type { EquipmentTimeline } from "@/types/timeline";
import type { AdoptionRate, VisionAnalysisResult, VisionCandidate } from "@/types/vision";
import type { WorkPlanDetail, WorkPlanListItem } from "@/types/workPlan";

/** 도메인별로 파일을 나눌 만큼 크지 않다. 커지면 그때 나눈다 */

export const equipmentApi = {
  list: () => get<EquipmentItem[]>("/api/equipment"),
};

export const timelineApi = {
  byEquipment: (equipmentId: number) =>
    get<EquipmentTimeline>(`/api/dashboard/equipment/${equipmentId}/timeline`),
};

export const incidentApi = {
  register: (body: RegisterIncidentRequest) =>
    post<IncidentRegisterResponse>("/api/incident", body),
  detail: (id: number) => get<IncidentRegisterResponse>(`/api/incident/${id}`),
  list: (page = 0, size = 20) =>
    get<PaginationResponse<IncidentListItem>>(`/api/incident?page=${page}&size=${size}`),
};

export const workPlanApi = {
  list: (page = 0, size = 20) =>
    get<PaginationResponse<WorkPlanListItem>>(`/api/work-plan?page=${page}&size=${size}`),
  detail: (id: number) => get<WorkPlanDetail>(`/api/work-plan/${id}`),
  acknowledge: (id: number) => post<WorkPlanDetail>(`/api/work-plan/${id}/ack`),
  approve: (id: number, approver?: string, condition?: string) =>
    post<WorkPlanDetail>(`/api/work-plan/${id}/approve`, { approver, condition }),
};

export const visionApi = {
  result: (assessmentId: number) =>
    get<VisionAnalysisResult>(`/api/vision/result/${assessmentId}`),
  adopt: (hazardId: number) => post<VisionCandidate>(`/api/vision/hazard/${hazardId}/adopt`),
  reject: (hazardId: number) => post<VisionCandidate>(`/api/vision/hazard/${hazardId}/reject`),
  adoptionRate: () => get<AdoptionRate>("/api/vision/adoption-rate"),
};

/** 법정 서식은 새 탭에서 연다. 인쇄(Ctrl+P)로 PDF가 된다 */
export const formUrl = {
  assessment: (id: number) => `/form/assessment/${id}`,
  incident: (id: number) => `/form/incident/${id}`,
  workPlan: (id: number) => `/form/work-plan/${id}`,
};
