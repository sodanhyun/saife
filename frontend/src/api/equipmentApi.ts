// equipmentApi.ts
import { api } from "@/api/client";
import { EQUIPMENT } from "@/api/endpoints";
import type { EquipmentItem } from "@/types/equipment";

export const equipmentApi = {
  list: (signal?: AbortSignal) => api.get<EquipmentItem[]>(EQUIPMENT, { signal }).then((r) => r.data),
};
