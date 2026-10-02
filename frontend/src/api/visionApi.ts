// visionApi.ts — 순회점검
import { api } from "@/api/client";
import type { ActionView, CreateActionRequest } from "@/types/action";
import type {
  AcceptableView,
  AdoptionRate,
  InspectionRequest,
  InspectionView,
  RecentInspection,
  VisionAnalysisResult,
  VisionCandidate,
} from "@/types/vision";

export const visionApi = {
  result: (assessmentId: number) => api.get<VisionAnalysisResult>(`/api/vision/result/${assessmentId}`).then((r) => r.data),
  recent: (signal?: AbortSignal) => api.get<RecentInspection[]>("/api/vision/recent", { signal }).then((r) => r.data),
  /** 반영 */
  adopt: (hazardId: number) => api.post<VisionCandidate>(`/api/vision/hazard/${hazardId}/adopt`).then((r) => r.data),
  /** 제외 */
  reject: (hazardId: number) => api.post<VisionCandidate>(`/api/vision/hazard/${hazardId}/reject`).then((r) => r.data),
  updateInspection: (assessmentId: number, body: InspectionRequest) =>
    api.put<InspectionView>(`/api/vision/assessment/${assessmentId}/inspection`, body).then((r) => r.data),
  setAcceptable: (assessmentId: number, hazardId: number, acceptable: boolean) =>
    api.put<AcceptableView>(`/api/vision/assessment/${assessmentId}/hazard/${hazardId}/acceptable`, { acceptable }).then((r) => r.data),
  /** 개선대책 등록. 반영한 위험요인에만 된다(아니면 409) */
  createAction: (hazardId: number, body: CreateActionRequest) => api.post<ActionView>(`/api/vision/hazard/${hazardId}/action`, body).then((r) => r.data),
  adoptionRate: (signal?: AbortSignal) => api.get<AdoptionRate>("/api/vision/adoption-rate", { signal }).then((r) => r.data),
};
