// timelineApi.ts
import { api } from "@/api/client";
import { equipmentTimeline } from "@/api/endpoints";
import type { EquipmentTimeline } from "@/types/timeline";

export const timelineApi = {
  byEquipment: (equipmentId: number, signal?: AbortSignal) =>
    api.get<EquipmentTimeline>(equipmentTimeline(equipmentId), { signal }).then((r) => r.data),
};
