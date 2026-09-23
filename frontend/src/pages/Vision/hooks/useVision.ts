import { useEffect, useRef, useState } from "react";

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
  // 미리보기 object URL — 언마운트·재선택 시 revoke해서 누수시키지 않는다.
  const previewRef = useRef<string | null>(null);

  // 판독이 끝나면 채택률을 다시 읽는다 — 서버 값에서만 파생
  useEffect(() => { if (!stream.analyzing) rate.refetch(); }, [stream.analyzing, stream.result]); // eslint-disable-line react-hooks/exhaustive-deps

  // 언마운트 시 마지막 미리보기 URL을 정리한다. setState 업데이터 안에서 revoke하지 않는다 —
  // StrictMode가 업데이터를 두 번 실행해 살아있는 URL을 먼저 revoke해버릴 수 있다.
  useEffect(() => () => {
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
  }, []);

  const pick = (file: File | undefined) => {
    if (!file) return;
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    const url = URL.createObjectURL(file);
    previewRef.current = url;
    setPreview(url);
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

  return { equipment: equipment.data ?? [], loading: equipment.loading, loadError: equipment.error, equipmentId, setEquipmentId: setSelectedId, rate: rate.data, preview, pick, decide, busyId, stream };
}
