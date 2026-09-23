import { useState } from "react";

import { equipmentApi } from "@/api/equipmentApi";
import { timelineApi } from "@/api/timelineApi";
import { useApiData } from "@/hooks/useApiData";

export function useTimeline() {
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const [selectedId, setEquipmentId] = useState<number | null>(null);
  // 설비 목록이 오면 첫 설비를 기본 선택한다 — 렌더 중 파생(effect+setState로 동기화하지 않는다)
  const equipmentId = selectedId ?? equipment.data?.[0]?.id ?? null;
  const timeline = useApiData({
    fetchFn: (s) => timelineApi.byEquipment(equipmentId!, s),
    deps: [equipmentId],
    enabled: equipmentId !== null,
    errorMessage: "타임라인을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  return {
    equipment: equipment.data ?? [],
    equipmentId, setEquipmentId,
    timeline: timeline.data,
    loading: equipment.loading || (timeline.loading && !timeline.initialLoaded),
    initialLoaded: timeline.initialLoaded,
  };
}
