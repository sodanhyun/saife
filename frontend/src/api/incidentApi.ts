// incidentApi.ts
import { api } from "@/api/client";
import { INCIDENT } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type { IncidentListItem, IncidentRegisterResponse, RegisterIncidentRequest } from "@/types/incident";

export const incidentApi = {
  register: (body: RegisterIncidentRequest) => api.post<IncidentRegisterResponse>(INCIDENT, body).then((r) => r.data),
  detail: (id: number) => api.get<IncidentRegisterResponse>(`${INCIDENT}/${id}`).then((r) => r.data),
  list: (page = 0, size = 20, signal?: AbortSignal) =>
    api.get<PaginationResponse<IncidentListItem>>(INCIDENT, { params: { page, size }, signal }).then((r) => r.data),
};
