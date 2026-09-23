import { useEffect, useState } from "react";

import { equipmentApi } from "@/api/equipmentApi";
import { visionApi } from "@/api/visionApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import { getServerMessage } from "@/utils/errorMessage";
import { useVisionStream } from "@/pages/Vision/hooks/useVisionStream";

export function useVision() {
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const rate = useApiData({ fetchFn: (s) => visionApi.adoptionRate(s), deps: [], skipFirstSkeleton: true });
  // undefined = 아직 사람이 선택하지 않음(첫 설비로 파생). null = "(설비 지정 없음)"을 직접 골랐다 — effect로 되돌리지 않는다.
  const [selectedId, setSelectedId] = useState<number | null | undefined>(undefined);
  const equipmentId = selectedId === undefined ? (equipment.data?.[0]?.id ?? null) : selectedId;
  const [preview, setPreview] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const stream = useVisionStream();
  const toast = useToastStore();

  // 판독이 끝나면 채택률을 다시 읽는다 — 서버 값에서만 파생
  useEffect(() => { if (!stream.analyzing) rate.refetch(); }, [stream.analyzing, stream.result]); // eslint-disable-line react-hooks/exhaustive-deps

  const pick = (file: File | undefined) => {
    if (!file) return;
    setPreview(URL.createObjectURL(file));
    void stream.analyze(file, equipmentId);
  };

  const decide = async (hazardId: number, adopt: boolean) => {
    setBusyId(hazardId);
    try {
      const updated = adopt ? await visionApi.adopt(hazardId) : await visionApi.reject(hazardId);
      stream.applyDecision(hazardId, updated.adopted);
      rate.refetch();
    } catch (e) {
      toast.error(getServerMessage(e) ?? "처리하지 못했습니다");
    } finally {
      setBusyId(null);
    }
  };

  return { equipment: equipment.data ?? [], loading: equipment.loading, equipmentId, setEquipmentId: setSelectedId, rate: rate.data, preview, pick, decide, busyId, stream };
}
