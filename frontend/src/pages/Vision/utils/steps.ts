// steps.ts — 사진 점검 5단계 진행. 화면 상태(사진, 판독 결과, 후보별 채택과 조치)에서만 파생한다.
import type { VisionAnalysisResult } from "@/types/vision";

export type StepKey = "photo" | "detect" | "adopt" | "action" | "done";
export type StepState = "idle" | "active" | "running" | "done";

export interface StepView {
  key: StepKey;
  title: string;
  detail: string | null;
  state: StepState;
}

interface Input {
  hasPhoto: boolean;
  analyzing: boolean;
  result: VisionAnalysisResult | null;
  /** 판독 중 진행 문구(서버 assess.progress) */
  progress: string | null;
}

const TITLES: Record<StepKey, string> = {
  photo: "사진",
  detect: "후보 판독",
  adopt: "사람의 채택",
  action: "감소대책",
  done: "이행",
};

/** 다섯 단계의 상태. 끝나지 않은 첫 단계가 "지금 여기"(active, 판독 중이면 running)다 */
export function deriveSteps({ hasPhoto, analyzing, result, progress }: Input): StepView[] {
  const candidates = result?.candidates ?? [];
  const decided = candidates.filter((c) => c.adopted !== null);
  const adopted = candidates.filter((c) => c.adopted === true);
  // 재확인 후보에 이미 걸린 미이행 조치가 있으면 그 조치가 대책을 대신한다(새로 걸 수도 있다)
  const needsAction = adopted.filter((c) => c.action !== null || c.priorOpenAction === null);
  const registered = adopted.filter((c) => c.action !== null);
  const completed = registered.filter((c) => c.action?.status === "DONE");

  const done: Record<StepKey, boolean> = {
    photo: hasPhoto,
    detect: result !== null && !analyzing,
    // 후보가 0건이면 채택할 것이 없다. 이후 단계는 열리지 않는다
    adopt: candidates.length > 0 && decided.length === candidates.length,
    action: needsAction.length > 0 && registered.length >= needsAction.length,
    done: registered.length > 0 && completed.length === registered.length && registered.length >= needsAction.length,
  };

  const detail: Record<StepKey, string | null> = {
    photo: hasPhoto ? "현장 사진 1장" : "사진을 올립니다",
    detect: analyzing ? progress ?? "판독 중" : result ? `후보 ${candidates.length}건` : "빠진 안전조치 6축",
    adopt: result && candidates.length > 0 ? `채택 ${adopted.length}건, 반려 ${decided.length - adopted.length}건` : "확정은 사람이",
    action: needsAction.length > 0 ? `등록 ${registered.length}/${needsAction.length}건` : "담당, 기한",
    done: registered.length > 0 ? `완료 ${completed.length}/${registered.length}건` : "완료 기록",
  };

  const keys: StepKey[] = ["photo", "detect", "adopt", "action", "done"];
  const firstOpen = keys.find((k) => !done[k]);
  return keys.map((key) => {
    let state: StepState = done[key] ? "done" : "idle";
    if (key === firstOpen) state = key === "detect" && analyzing ? "running" : "active";
    // 후보가 하나도 없으면 판독에서 흐름이 끝난다. 다음 단계를 "지금 여기"로 켜지 않는다
    if (key === firstOpen && key === "adopt" && result !== null && candidates.length === 0) state = "idle";
    return { key, title: TITLES[key], detail: detail[key], state };
  });
}
