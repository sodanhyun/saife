// actionApi.ts — 감소대책 이행. 등록은 위험요인 문맥이라 visionApi.createAction에 있다
import { api } from "@/api/client";
import type { ActionView } from "@/types/action";

export const actionApi = {
  /** 이행 완료. 멱등이다(이미 완료면 그대로 돌려준다) */
  complete: (actionId: number) => api.post<ActionView>(`/api/action/${actionId}/complete`).then((r) => r.data),
};
