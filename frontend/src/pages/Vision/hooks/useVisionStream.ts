// 사진 판독 스트림(UC1). 업로드 응답 자체가 SSE다: assess.progress → assess.done | assess.failed.
import { useCallback, useState } from "react";

import { VISION_ANALYZE } from "@/api/endpoints";
import { useSSEStream } from "@/hooks/useSSEStream";
import type { SseErrorPayload } from "@/types/sse";
import type { VisionAnalysisResult } from "@/types/vision";

interface VisionEventMap {
  "assess.progress": { message?: string };
  "assess.done": VisionAnalysisResult;
  "assess.failed": SseErrorPayload;
}

export function useVisionStream() {
  const [result, setResult] = useState<VisionAnalysisResult | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);

  const { connectionState, start } = useSSEStream<VisionEventMap>({
    url: VISION_ANALYZE,
    method: "POST",
    handlers: {
      "assess.progress": (p) => setProgress(p?.message ?? "판독 중"),
      "assess.done": (p) => { setResult(p); setProgress(null); },
      "assess.failed": (p) => { setError(p?.message ?? "판독에 실패했습니다"); setProgress(null); },
    },
  });

  const analyze = useCallback(async (file: File, equipmentId: number | null) => {
    setResult(null); setError(null); setProgress("업로드 중"); setAnalyzing(true);
    const form = new FormData();
    form.append("image", file);
    if (equipmentId !== null) form.append("equipmentId", String(equipmentId));
    const outcome = await start(form);
    if (outcome.reason === "error") setError(outcome.error?.message ?? "업로드 실패");
    setProgress(null);
    setAnalyzing(false);
  }, [start]);

  /** 채택·반려 후 서버 값을 그대로 반영한다 */
  const applyDecision = useCallback((hazardId: number, adopted: boolean | null) => {
    setResult((prev) => prev && { ...prev, candidates: prev.candidates.map((c) => (c.hazardId === hazardId ? { ...c, adopted } : c)) });
  }, []);

  return { result, progress, error, analyzing, connectionState, analyze, applyDecision };
}
