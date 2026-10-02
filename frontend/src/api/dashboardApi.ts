// dashboardApi.ts — UC4 설비 홈·상세·회상·오늘 할 일. 전부 읽기 전용.
import { api } from "@/api/client";
import { EQUIPMENT_CARDS, TODAY, equipmentRecall } from "@/api/endpoints";
import type { EquipmentCard, RecallView, TodayView } from "@/types/timeline";

export const dashboardApi = {
  cards: (signal?: AbortSignal) => api.get<EquipmentCard[]>(EQUIPMENT_CARDS, { signal }).then((r) => r.data),
  recall: (equipmentId: number, signal?: AbortSignal) =>
    api.get<RecallView>(equipmentRecall(equipmentId), { signal }).then((r) => r.data),
  // 실패하면 useToday의 에러 상태로만 남고, 홈은 KPI와 인박스 없이 설비 카드만 그린다.
  today: (signal?: AbortSignal) => api.get<TodayView>(TODAY, { signal }).then((r) => r.data),
};
