import { useEffect, useRef, useState } from "react";

import { useSearchParams } from "react-router-dom";

import { actionApi } from "@/api/actionApi";
import { equipmentApi } from "@/api/equipmentApi";
import { visionApi } from "@/api/visionApi";
import { CURRENT_USER } from "@/components/layout/menu";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import { getServerMessage } from "@/utils/errorMessage";
import { useVisionStream } from "@/pages/Vision/hooks/useVisionStream";
import type { ControlPriority } from "@/types/action";
import type { VisionCandidate } from "@/types/vision";

/** 개선대책 등록 폼 입력 */
/** guideRef: 폼을 채운 초안의 지침 번호(지침이 없는 초안이면 null) */
export interface ActionInput { content: string; owner: string; dueDate: string; priority: ControlPriority | null; guideRef: string | null }

/** 진입 컨텍스트: 설비 홈, 상세에서 "순회점검"으로 들어오면 ?equipmentId=가 붙는다 */
function initialEquipmentIdFromQuery(raw: string | null): number | undefined {
  if (!raw) return undefined;
  const n = Number(raw);
  return Number.isFinite(n) ? n : undefined;
}

export function useVision() {
  const [searchParams] = useSearchParams();
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const recent = useApiData({ fetchFn: (s) => visionApi.recent(s), deps: [], skipFirstSkeleton: true });
  // 진입 컨텍스트가 없으면 설비를 고르지 않은 채 시작한다. 첫 설비를 대신 골라 두면 엉뚱한 설비에 기록된다
  const [equipmentId, setEquipmentId] = useState<number | null>(() =>
    initialEquipmentIdFromQuery(searchParams.get("equipmentId")) ?? null,
  );
  const [inspector, setInspector] = useState<string>(CURRENT_USER.name);
  const [participants, setParticipants] = useState<string[]>([]);
  const [preview, setPreview] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const stream = useVisionStream();
  const toastError = useToastStore((s) => s.error);
  // 미리보기 object URL. 언마운트, 재선택 시 revoke해서 누수시키지 않는다
  const previewRef = useRef<string | null>(null);
  const assessmentId = stream.result?.assessmentId ?? null;

  // 분석이 끝나면(analyzing true→false) 최근 점검 목록을 다시 읽는다. 서버 값에서만 파생
  const refetchRecent = recent.refetch;
  const prevAnalyzingRef = useRef(stream.analyzing);
  useEffect(() => {
    const was = prevAnalyzingRef.current;
    prevAnalyzingRef.current = stream.analyzing;
    if (was && !stream.analyzing) refetchRecent();
  }, [stream.analyzing, refetchRecent]);

  // 언마운트 시 마지막 미리보기 URL을 정리한다(업데이터 안에서 revoke하지 않는다: StrictMode 이중 실행)
  useEffect(() => () => {
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
  }, []);

  const pick = (file: File | undefined) => {
    if (!file) return;
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    const url = URL.createObjectURL(file);
    previewRef.current = url;
    setPreview(url);
    void stream.analyze(file, { equipmentId, inspector, participants });
  };

  /** 점검 정보 저장. 점검(평가)이 아직 없으면 사진을 올릴 때 같이 보낸다 */
  const saveInspection = async (nextInspector: string, nextParticipants: string[]) => {
    if (assessmentId === null) return;
    try {
      const saved = await visionApi.updateInspection(assessmentId, { inspector: nextInspector.trim() || null, participants: nextParticipants });
      stream.patchInspection(saved.inspector, saved.participants);
    } catch (e) {
      toastError(getServerMessage(e) ?? "점검 정보를 저장하지 못했습니다");
    }
  };

  const changeParticipants = (next: string[]) => {
    setParticipants(next);
    void saveInspection(inspector, next);
  };

  /** 점검자 입력이 끝났을 때(blur) 저장한다 */
  const commitInspector = () => {
    if (assessmentId !== null && (stream.result?.inspector ?? "") !== inspector.trim()) void saveInspection(inspector, participants);
  };

  const run = async (hazardId: number, work: () => Promise<void>, failMessage: string) => {
    setBusyId(hazardId);
    try {
      await work();
    } catch (e) {
      toastError(getServerMessage(e) ?? failMessage);
    } finally {
      setBusyId(null);
    }
  };

  /** 반영(true) / 제외(false) */
  const decide = (hazardId: number, reflect: boolean) =>
    run(hazardId, async () => {
      const updated = reflect ? await visionApi.adopt(hazardId) : await visionApi.reject(hazardId);
      stream.patchCandidate(hazardId, { adopted: updated.adopted });
    }, "처리하지 못했습니다");

  /** 허용 가능 여부. 이 점검에 기록된다 */
  const setAcceptable = (hazardId: number, acceptable: boolean) =>
    run(hazardId, async () => {
      if (assessmentId === null) return;
      const saved = await visionApi.setAcceptable(assessmentId, hazardId, acceptable);
      stream.patchCandidate(hazardId, { acceptable: saved.acceptable });
    }, "허용 가능 여부를 저장하지 못했습니다");

  /** 개선대책 등록. 이 점검(평가)에 묶는다. 응답(서버 값)으로 카드를 덮는다 */
  const createAction = (c: VisionCandidate, input: ActionInput) =>
    run(c.hazardId, async () => {
      const action = await visionApi.createAction(c.hazardId, {
        assessmentId,
        content: input.content.trim(),
        owner: input.owner.trim() || null,
        dueDate: input.dueDate || null,
        guideRef: input.guideRef,
        priority: input.priority,
      });
      stream.patchCandidate(c.hazardId, { action });
    }, "개선대책을 등록하지 못했습니다");

  /** 이행 완료. 서버가 멱등이라 연타해도 완료 시각이 밀리지 않는다 */
  const completeAction = (hazardId: number, actionId: number) =>
    run(hazardId, async () => {
      const action = await actionApi.complete(actionId);
      stream.patchCandidate(hazardId, { action });
    }, "이행 결과를 기록하지 못했습니다");

  return {
    equipment: equipment.data ?? [],
    loading: equipment.loading,
    loadError: equipment.error,
    /** 설비 목록과 최근 점검을 다시 읽는다(조회 실패 후 새로고침) */
    refetch: () => { equipment.refetch(); recent.refetch(); },
    equipmentId,
    setEquipmentId,
    inspector,
    setInspector,
    commitInspector,
    participants,
    changeParticipants,
    recent: recent.data ?? [],
    preview,
    pick,
    decide,
    setAcceptable,
    createAction,
    completeAction,
    busyId,
    stream,
  };
}
