// actionApi.ts: 개선대책 목록과 이행 확인. 등록은 위험요인 문맥이라 visionApi.createAction에 있다
import { api } from "@/api/client";
import { ACTION } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type { ActionCounts, ActionListItem, ActionSearchParams, ActionView, VerifyActionRequest } from "@/types/action";

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
  /** 증빙 사진 첨부. 대책이 사진에 보이는지 대조한 결과(photoCheck)가 같이 온다 */
  attachEvidence: (actionId: number, file: File) => {
    const form = new FormData();
    form.append("image", file);
    return api.post<ActionView>(`${ACTION}/${actionId}/evidence`, form).then((r) => r.data);
  },
  /** 이행 확인. 증빙 사진, 확인자, 개선 후 위험성 필수. 멱등이다 */
  verify: (actionId: number, body: VerifyActionRequest) =>
    api.post<ActionView>(`${ACTION}/${actionId}/verify`, body).then((r) => r.data),
};
