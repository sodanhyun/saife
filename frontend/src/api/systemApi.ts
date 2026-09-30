// systemApi.ts — 전역 시스템 상태(데모 모드·임베딩·근거 수·회로 상태). 읽기 전용.
import { api } from "@/api/client";
import { SYSTEM_STATUS } from "@/api/endpoints";
import type { SystemStatus } from "@/types/system";

export const systemApi = {
  status: (signal?: AbortSignal) => api.get<SystemStatus>(SYSTEM_STATUS, { signal }).then((r) => r.data),
};
