// visionApi.ts
import { api } from "@/api/client";
import type { ActionView, CreateActionRequest } from "@/types/action";
import type { AdoptionRate, VisionAnalysisResult, VisionCandidate } from "@/types/vision";

export const visionApi = {
  result: (assessmentId: number) => api.get<VisionAnalysisResult>(`/api/vision/result/${assessmentId}`).then((r) => r.data),
  adopt: (hazardId: number) => api.post<VisionCandidate>(`/api/vision/hazard/${hazardId}/adopt`).then((r) => r.data),
  reject: (hazardId: number) => api.post<VisionCandidate>(`/api/vision/hazard/${hazardId}/reject`).then((r) => r.data),
  /** 감소대책 등록. 채택한 위험요인에만 된다(아니면 409) */
  createAction: (hazardId: number, body: CreateActionRequest) => api.post<ActionView>(`/api/vision/hazard/${hazardId}/action`, body).then((r) => r.data),
  adoptionRate: (signal?: AbortSignal) => api.get<AdoptionRate>("/api/vision/adoption-rate", { signal }).then((r) => r.data),
};
