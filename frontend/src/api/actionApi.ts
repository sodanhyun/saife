// actionApi.ts: 개선대책 목록과 이행. 등록은 위험요인 문맥이라 visionApi.createAction에 있다
import { api } from "@/api/client";
import { ACTION } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type { ActionCounts, ActionListItem, ActionSearchParams, ActionView } from "@/types/action";

export const actionApi = {
  /** 개선대책 목록. status 기본 OPEN(미이행, 기한 경과 포함) */
  search: ({ status, keyword, page = 0, size = 20 }: ActionSearchParams, signal?: AbortSignal) =>
    api
      .get<PaginationResponse<ActionListItem>>(ACTION, {
        params: { status, keyword: keyword?.trim() || undefined, page, size },
        signal,
      })
      .then((r) => r.data),
  /** 목록 탭 건수 */
  counts: (signal?: AbortSignal) => api.get<ActionCounts>(`${ACTION}/counts`, { signal }).then((r) => r.data),
  /** 이행 완료. 멱등이다(이미 완료면 그대로 돌려준다) */
  complete: (actionId: number) => api.post<ActionView>(`${ACTION}/${actionId}/complete`).then((r) => r.data),
};
