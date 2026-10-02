// inspection.ts — 순회점검 기록 상태. 백엔드 InspectionRules.complete와 같은 규칙이다(서식 상태와 화면이 같아야 한다).
import { daysUntil } from "@/pages/Vision/utils/dates";
import type { ActionView } from "@/types/action";
import type { VisionCandidate } from "@/types/vision";

/** 기한이 지난 미이행 조치인가 */
export function isOverdue(a: ActionView | null): boolean {
  const d = daysUntil(a?.dueDate ?? null);
  return a !== null && a.status !== "DONE" && d !== null && d < 0;
}

/**
 * 개선대책이 있는가: 이 점검에서 등록했거나, 기한이 남은 기존 조치가 있다.
 * 기한 지난 기존 조치는 인정하지 않는다(고시 제12조④, 잠정조치를 포함해 다시 세운다)
 */
export function hasImprovement(c: VisionCandidate): boolean {
  return c.action !== null || (c.priorOpenAction !== null && !isOverdue(c.priorOpenAction));
}

/**
 * 확정 조건: 참여 근로자가 있고, 모든 위험요인을 반영 또는 제외했고,
 * 반영한 것 중 허용 불가인 위험요인에는 개선대책이 있다.
 */
export function isComplete(participants: string[], candidates: VisionCandidate[]): boolean {
  if (participants.filter((p) => p.trim()).length === 0) return false;
  return candidates.every((c) => {
    if (c.adopted === null) return false;
    if (c.adopted === false) return true;
    return c.acceptable || hasImprovement(c);
  });
}

/** 참여 근로자 칩 입력: 쉼표나 공백 구분으로 여러 명을 한 번에 넣어도 나눈다. 중복은 버린다 */
export function addParticipants(current: string[], raw: string): string[] {
  const names = raw.split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
  const next = [...current];
  for (const n of names) if (!next.includes(n)) next.push(n);
  return next;
}
