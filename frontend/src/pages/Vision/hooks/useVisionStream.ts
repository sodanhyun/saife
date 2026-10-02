// 사진 판독 스트림(UC1). 업로드 응답 자체가 SSE다: assess.progress(ANALYZING → GRADING → EVIDENCE) → assess.done | assess.failed.
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

export interface StageMark { stage: AnalysisStage; at: number }

export function useVisionStream() {
  const [result, setResult] = useState<VisionAnalysisResult | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [stage, setStage] = useState<AnalysisStage | null>(null);
  /** 단계가 바뀐 시각들. 화면이 단계별 소요 시간을 여기서 계산한다 */
  const [stageLog, setStageLog] = useState<StageMark[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [finishedAt, setFinishedAt] = useState<number | null>(null);

  const { connectionState, start } = useSSEStream<VisionEventMap>({
    url: VISION_ANALYZE,
    method: "POST",
    handlers: {
      "assess.progress": (p) => {
        setProgress(p?.message ?? "판독 중");
        const phase = p?.phase;
        if (phase) { setStage(phase); setStageLog((log) => (log.some((m) => m.stage === phase) ? log : [...log, { stage: phase, at: Date.now() }])); }
      },
      "assess.done": (p) => {
        const now = Date.now();
        setResult(p); setProgress(null); setStage("DONE"); setFinishedAt(now);
        setStageLog((log) => [...log, { stage: "DONE", at: now }]);
      },
      "assess.failed": (p) => { setError(p?.message ?? "판독에 실패했습니다"); setProgress(null); setStage(null); },
    },
  });

  const analyze = useCallback(async (file: File, equipmentId: number | null) => {
    setResult(null); setError(null); setProgress("사진을 올리고 있습니다"); setStage("UPLOADING");
    const now = Date.now();
    setStartedAt(now); setFinishedAt(null); setAnalyzing(true); setStageLog([{ stage: "UPLOADING", at: now }]);
    const form = new FormData();
    form.append("image", file);
    if (equipmentId !== null) form.append("equipmentId", String(equipmentId));
    const outcome = await start(form);
    if (outcome.reason === "error") { setError(outcome.error?.message ?? "업로드 실패"); setStage(null); }
    setProgress(null);
    setAnalyzing(false);
  }, [start]);

  /** 서버가 돌려준 값으로 후보 한 건을 덮는다(채택 여부, 감소대책). 낙관적으로 가정하지 않는다 */
  const patchCandidate = useCallback((hazardId: number, patch: Partial<Pick<VisionCandidate, "adopted" | "action">>) => {
    setResult((prev) => prev && { ...prev, candidates: prev.candidates.map((c) => (c.hazardId === hazardId ? { ...c, ...patch } : c)) });
  }, []);

  /** 채택·반려 후 서버 값을 그대로 반영한다 */
  const applyDecision = useCallback((hazardId: number, adopted: boolean | null) => patchCandidate(hazardId, { adopted }), [patchCandidate]);

  return { result, progress, stage, stageLog, error, analyzing, startedAt, finishedAt, connectionState, analyze, applyDecision, patchCandidate };
}
