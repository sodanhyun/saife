import { useEffect, useRef, useState } from "react";

import { useSearchParams } from "react-router-dom";

import { actionApi } from "@/api/actionApi";
import { equipmentApi } from "@/api/equipmentApi";
import { visionApi } from "@/api/visionApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import { getServerMessage } from "@/utils/errorMessage";
import { useVisionStream } from "@/pages/Vision/hooks/useVisionStream";
import type { VisionCandidate } from "@/types/vision";

/** 감소대책 등록 폼 입력 */
export interface ActionInput { content: string; owner: string; dueDate: string }

/** 진입 컨텍스트 — 설비 홈·상세에서 "사진 점검" 버튼으로 들어오면 ?equipmentId=가 붙는다. */
function initialEquipmentIdFromQuery(raw: string | null): number | undefined {
  if (!raw) return undefined;
  const n = Number(raw);
  return Number.isFinite(n) ? n : undefined;
}

export function useVision() {
  const [searchParams] = useSearchParams();
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const rate = useApiData({ fetchFn: (s) => visionApi.adoptionRate(s), deps: [], skipFirstSkeleton: true });
  // undefined = 아직 사람이 선택하지 않음(첫 설비로 파생). null = "(설비 지정 없음)"을 직접 골랐다 — effect로 되돌리지 않는다.
  // 쿼리에 equipmentId가 있으면(진입 컨텍스트) 그 값을 명시적 선택으로 시작한다.
  const [selectedId, setSelectedId] = useState<number | null | undefined>(() =>
    initialEquipmentIdFromQuery(searchParams.get("equipmentId")),
  );
  const equipmentId = selectedId === undefined ? (equipment.data?.[0]?.id ?? null) : selectedId;
  const [preview, setPreview] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const stream = useVisionStream();
  const toastError = useToastStore((s) => s.error);
  // 미리보기 object URL — 언마운트·재선택 시 revoke해서 누수시키지 않는다.
  const previewRef = useRef<string | null>(null);

  // 판독이 끝나면(analyzing true→false 전이) 채택률을 다시 읽는다 — 서버 값에서만 파생.
  // 첫 렌더에는 useApiData가 이미 읽고 있으므로 다시 부르지 않는다(마운트 시 이중 요청 방지).
  const refetchRate = rate.refetch;
  const prevAnalyzingRef = useRef(stream.analyzing);
  useEffect(() => {
    const wasAnalyzing = prevAnalyzingRef.current;
    prevAnalyzingRef.current = stream.analyzing;
    if (wasAnalyzing && !stream.analyzing) refetchRate();
  }, [stream.analyzing, refetchRate]);

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
      toastError(getServerMessage(e) ?? "처리하지 못했습니다");
    } finally {
      setBusyId(null);
    }
  };

  /** 감소대책 등록. 평가는 이 판독이 만든 평가에 묶는다. 응답(서버 값)으로 카드를 덮는다 */
  const createAction = async (c: VisionCandidate, input: ActionInput) => {
    setBusyId(c.hazardId);
    try {
      const action = await visionApi.createAction(c.hazardId, {
        assessmentId: stream.result?.assessmentId ?? null,
        content: input.content.trim(),
        owner: input.owner.trim() || null,
        dueDate: input.dueDate || null,
        guideRef: c.suggestedAction?.guideRef ?? null,
      });
      stream.patchCandidate(c.hazardId, { action });
    } catch (e) {
      toastError(getServerMessage(e) ?? "감소대책을 등록하지 못했습니다");
    } finally {
      setBusyId(null);
    }
  };

  /** 이행 완료. 서버가 멱등이라 연타해도 완료 시각이 밀리지 않는다 */
  const completeAction = async (hazardId: number, actionId: number) => {
    setBusyId(hazardId);
    try {
      const action = await actionApi.complete(actionId);
      stream.patchCandidate(hazardId, { action });
    } catch (e) {
      toastError(getServerMessage(e) ?? "이행 완료를 기록하지 못했습니다");
    } finally {
      setBusyId(null);
    }
  };

  const selectedEquipment = equipment.data?.find((e) => e.id === equipmentId) ?? null;

  return { selectedEquipment, createAction, completeAction, equipment: equipment.data ?? [], loading: equipment.loading, loadError: equipment.error, equipmentId, setEquipmentId: setSelectedId, rate: rate.data, preview, pick, decide, busyId, stream };
}
