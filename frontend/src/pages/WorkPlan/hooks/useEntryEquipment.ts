// useEntryEquipment.ts — URL의 ?equipmentId= 진입 컨텍스트.
// 진입 회상(RecallView) 전체를 들고 있는다 — 첫 사용자 메시지 전에 RecallCard로 고정 렌더된다.
// 회상 엔드포인트는 설비 하나만 조회해 카드 목록(cards)보다 가볍다 — 그래서 이걸 쓴다.
import { useSearchParams } from "react-router-dom";

import { dashboardApi } from "@/api/dashboardApi";
import { useApiData } from "@/hooks/useApiData";

export function useEntryEquipment() {
  const [searchParams] = useSearchParams();
  const raw = searchParams.get("equipmentId");
  const parsed = raw ? Number(raw) : NaN;
  const equipmentId = Number.isFinite(parsed) ? parsed : null;

  const { data } = useApiData({
    fetchFn: (s) => dashboardApi.recall(equipmentId!, s),
    deps: [equipmentId],
    enabled: equipmentId !== null,
  });

  return {
    equipmentId,
    equipmentName: data?.equipmentName ?? null,
    locationTag: data?.locationTag ?? null,
    /** 진입 회상 카드 데이터. 대화 중 ai.recall이 오면 그쪽이 우선한다(카드는 한 장). */
    recall: data,
  };
}
