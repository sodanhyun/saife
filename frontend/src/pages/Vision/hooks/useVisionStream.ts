// 순회점검 사진 분석 스트림. 업로드 응답 자체가 SSE다: assess.progress(ANALYZING → GRADING → EVIDENCE) → assess.done | assess.failed.
import { useCallback, useState } from "react";

import { VISION_ANALYZE } from "@/api/endpoints";
import { useSSEStream } from "@/hooks/useSSEStream";
import type { SseErrorPayload } from "@/types/sse";
import type { VisionAnalysisResult, VisionCandidate, VisionPhase, VisionProgress } from "@/types/vision";

interface VisionEventMap {
  "assess.progress": VisionProgress;
  "assess.done": VisionAnalysisResult;
  "assess.failed": SseErrorPayload;
}

/** 진행 단계. UPLOADING은 서버 이벤트 전(클라이언트), DONE은 assess.done 수신 후 */
export type AnalysisStage = "UPLOADING" | VisionPhase | "DONE";

/** 사진 위 한 줄 진행 표시 문구 */
export const STAGE_TEXT: Record<AnalysisStage, string> = {
  UPLOADING: "사진 올리는 중",
  ANALYZING: "사진 분석 중",
  GRADING: "위험성 결정 중",
  EVIDENCE: "근거 확인 중",
  DONE: "완료",
};

/** 분석 요청에 함께 싣는 점검 정보 */
export interface AnalyzeInput {
  equipmentId: number | null;
  inspector: string;
  participants: string[];
}

export function useVisionStream() {
  const [result, setResult] = useState<VisionAnalysisResult | null>(null);
  const [stage, setStage] = useState<AnalysisStage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);

  const { connectionState, start } = useSSEStream<VisionEventMap>({
    url: VISION_ANALYZE,
    method: "POST",
    handlers: {
      "assess.progress": (p) => { if (p?.phase) setStage(p.phase); },
      "assess.done": (p) => { setResult(p); setStage("DONE"); },
      "assess.failed": (p) => { setError(p?.message ?? "사진을 분석하지 못했습니다"); setStage(null); },
    },
  });

  const analyze = useCallback(async (file: File, input: AnalyzeInput) => {
    setResult(null); setError(null); setStage("UPLOADING"); setAnalyzing(true);
    const form = new FormData();
    form.append("image", file);
    if (input.equipmentId !== null) form.append("equipmentId", String(input.equipmentId));
    if (input.inspector.trim()) form.append("inspector", input.inspector.trim());
    for (const p of input.participants) form.append("participants", p);
    const outcome = await start(form);
    if (outcome.reason === "error") { setError(outcome.error?.message ?? "사진을 올리지 못했습니다"); setStage(null); }
    setAnalyzing(false);
  }, [start]);

  /** 서버가 돌려준 값으로 위험요인 한 건을 덮는다. 낙관적으로 가정하지 않는다 */
  const patchCandidate = useCallback((hazardId: number, patch: Partial<Pick<VisionCandidate, "adopted" | "action" | "acceptable">>) => {
    setResult((prev) => prev && { ...prev, candidates: prev.candidates.map((c) => (c.hazardId === hazardId ? { ...c, ...patch } : c)) });
  }, []);

  /** 점검 정보(점검자, 참여 근로자)를 서버 값으로 덮는다 */
  const patchInspection = useCallback((inspector: string | null, participants: string[]) => {
    setResult((prev) => prev && { ...prev, inspector, participants });
  }, []);

  /** 서버가 다시 계산한 결과로 바꾼다(설비 기록 연결 뒤) */
  const replaceResult = useCallback((next: VisionAnalysisResult) => setResult(next), []);

  return { result, stage, error, analyzing, connectionState, analyze, patchCandidate, patchInspection, replaceResult };
}
