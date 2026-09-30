import { useState } from "react";

import { equipmentApi } from "@/api/equipmentApi";
import { timelineApi } from "@/api/timelineApi";
import { useApiData } from "@/hooks/useApiData";

/**
 * 설비 타임라인 조회 — `/timeline`(설비 선택기가 있는 화면)과 설비 상세(설비가 이미
 * 정해진 화면)가 공유한다. `fixedEquipmentId`를 주면 그 설비로 고정하고,
 * 선택기용 설비 목록은 아예 불러오지 않는다(불필요한 요청 제거).
 *
 * 2페이지 이상이 쓰는 도메인 훅이라 `components/timeline/`과 같은 이유로
 * `pages/Timeline/`이 아니라 여기(`src/hooks/`)로 승격했다.
 */
export function useEquipmentTimeline(fixedEquipmentId?: number) {
  const pickerMode = fixedEquipmentId === undefined;
  const equipment = useApiData({
    fetchFn: (s) => equipmentApi.list(s),
    deps: [],
    errorMessage: "설비 목록을 불러오지 못했습니다",
    enabled: pickerMode,
  });
  const [selectedId, setEquipmentId] = useState<number | null>(null);
  // 선택기 모드: 설비 목록이 오면 첫 설비를 기본 선택한다(렌더 중 파생).
  // 고정 모드: 항상 fixedEquipmentId를 쓴다 — 선택기 상태는 관여하지 않는다.
  const equipmentId = pickerMode ? (selectedId ?? equipment.data?.[0]?.id ?? null) : fixedEquipmentId;
  const timeline = useApiData({
    fetchFn: (s) => timelineApi.byEquipment(equipmentId!, s),
    deps: [equipmentId],
    enabled: equipmentId !== null && equipmentId !== undefined,
    errorMessage: "타임라인을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  return {
    equipment: equipment.data ?? [],
    equipmentId: equipmentId ?? null,
    setEquipmentId,
    timeline: timeline.data,
    loading: pickerMode
      ? equipment.loading || (timeline.loading && !timeline.initialLoaded)
      : timeline.loading && !timeline.initialLoaded,
    loadError: (pickerMode && equipment.error) || timeline.error,
    initialLoaded: timeline.initialLoaded,
  };
}
