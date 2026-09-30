// dashboardApi.ts — UC4 설비 홈·상세·회상·오늘 할 일. 전부 읽기 전용.
import { api } from "@/api/client";
import { EQUIPMENT_CARDS, TODAY, equipmentRecall } from "@/api/endpoints";
import type { EquipmentCard, RecallView, TodayView } from "@/types/timeline";

export const dashboardApi = {
  cards: (signal?: AbortSignal) => api.get<EquipmentCard[]>(EQUIPMENT_CARDS, { signal }).then((r) => r.data),
  recall: (equipmentId: number, signal?: AbortSignal) =>
    api.get<RecallView>(equipmentRecall(equipmentId), { signal }).then((r) => r.data),
  // 백엔드 4a가 나올 때까지 500을 반환한다 — useToday의 에러 상태가 이걸 조용히 흡수한다.
  today: (signal?: AbortSignal) => api.get<TodayView>(TODAY, { signal }).then((r) => r.data),
};
