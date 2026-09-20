import { useCallback, useRef, useState } from "react";
import type { SseEnvelope } from "@/types/sse";
import type { VisionAnalysisResult } from "@/types/vision";

/**
 * 사진 판독 스트림 구독 훅 (UC1).
 *
 * 판독이 3~10초 걸려(2026-09-20 실측) 동기 응답으로 두면 화면이 죽은 것처럼 보인다.
 * 업로드 응답 자체가 SSE 스트림이다: assess.progress → assess.done | assess.failed.
 *
 * `useAgentStream`과 프레임 파싱이 겹치지만 아직 분리하지 않는다. 소비자가 둘뿐이고,
 * 미리 추상화하면 두 스트림의 다른 점(멀티턴 vs 단발)이 억지로 같아진다.
 * 세 번째 소비자가 생기면 그때 나눈다 — 규약: .claude/rules/sse-streaming.md
 */
export function useVisionStream() {
  const [result, setResult] = useState<VisionAnalysisResult | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);

  const abortRef = useRef<AbortController | null>(null);

  const consume = useCallback(async (res: Response) => {
    if (!res.body) throw new Error("응답 본문이 없습니다");
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      const chunks = buffer.split("\n\n");
      buffer = chunks.pop() ?? "";

      for (const chunk of chunks) {
        const dataLine = chunk.split("\n").find((l) => l.startsWith("data:"));
        if (!dataLine) continue;
        try {
          const env = JSON.parse(dataLine.slice(5).trim()) as SseEnvelope;
          if (env.type === "assess.progress") {
            setProgress((env.payload as { message?: string })?.message ?? "판독 중");
          } else if (env.type === "assess.done") {
            setResult(env.payload as VisionAnalysisResult);
            setProgress(null);
            setAnalyzing(false);
          } else if (env.type === "assess.failed") {
            setError((env.payload as { message?: string })?.message ?? "판독에 실패했습니다");
            setProgress(null);
            setAnalyzing(false);
          }
        } catch {
          // 청크 하나가 깨져도 스트림 전체를 죽이지 않는다
        }
      }
    }
  }, []);

  const analyze = useCallback(
    async (file: File, equipmentId: number | null) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setResult(null);
      setError(null);
      setProgress("업로드 중");
      setAnalyzing(true);

      const form = new FormData();
      form.append("image", file);
      if (equipmentId !== null) form.append("equipmentId", String(equipmentId));

      try {
        const res = await fetch("/api/vision/analyze", {
          method: "POST",
          body: form,
          signal: controller.signal,
        });
        if (!res.ok) {
          throw new Error(`업로드 실패 (${res.status})`);
        }
        await consume(res);
      } catch (e) {
        if ((e as Error).name !== "AbortError") {
          setError((e as Error).message);
        }
        setProgress(null);
        setAnalyzing(false);
      }
    },
    [consume],
  );

  /** 채택·반려 후 화면 상태를 갱신한다. 서버 값을 그대로 반영한다 */
  const applyDecision = useCallback((hazardId: number, adopted: boolean | null) => {
    setResult((prev) =>
      prev === null
        ? prev
        : {
            ...prev,
            candidates: prev.candidates.map((c) =>
              c.hazardId === hazardId ? { ...c, adopted } : c,
            ),
          },
    );
  }, []);

  return { result, progress, error, analyzing, analyze, applyDecision };
}
