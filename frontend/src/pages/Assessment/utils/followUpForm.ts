// followUpForm.ts — 수시평가 입력 상태와 검사. 확정 조건은 백엔드(FollowUpConfirmService)와 같다:
// 참여 근로자 1명 이상, 허용 불가 위험요인마다 개선대책(무엇을, 담당, 기한). 기한이 남은 기존 대책이 있으면 새 대책은 선택이다.
import dayjs from "dayjs";

import type { FollowUpDetail, FollowUpHazard, FollowUpRequest } from "@/types/incident";

export interface HazardDraft {
  acceptable: boolean;
  content: string;
  owner: string;
  dueDate: string;
}

export interface FollowUpFormState {
  inspector: string;
  participants: string[];
  hazards: Record<number, HazardDraft>;
}

export interface HazardErrors {
  content?: string;
  owner?: string;
  dueDate?: string;
}

export interface FollowUpErrors {
  participants?: string;
  hazards: Record<number, HazardErrors>;
}

/** 새 대책의 기본 기한: 오늘부터 14일 */
export function defaultDue(today: string): string {
  return dayjs(today).add(14, "day").format("YYYY-MM-DD");
}

/** 처음 상태: 저장된 값, 없으면 기준표 대책과 기존 대책의 담당으로 채운다 */
export function initialFollowUpForm(d: FollowUpDetail, today: string): FollowUpFormState {
  const hazards: Record<number, HazardDraft> = {};
  for (const h of d.hazards) {
    hazards[h.hazardId] = {
      acceptable: h.acceptable,
      content: h.action?.content ?? h.suggestion?.content ?? h.priorAction?.content ?? "",
      owner: h.action?.owner ?? h.priorAction?.owner ?? "",
      dueDate: h.action?.dueDate ?? defaultDue(today),
    };
  }
  return { inspector: d.inspector ?? "", participants: d.participants, hazards };
}

/** 기한이 남은 기존 대책(다른 평가에서 세운 것) */
export function priorStillValid(h: FollowUpHazard, today: string): boolean {
  return h.priorAction !== null && h.priorAction.dueDate !== null && h.priorAction.dueDate >= today;
}

/** 이 위험요인에 새 대책 입력이 필요한가: 허용 불가이고, 이번에 세운 대책도 기한이 남은 기존 대책도 없다 */
export function needsPlan(h: FollowUpHazard, draft: HazardDraft, today: string): boolean {
  return !draft.acceptable && h.action === null && !priorStillValid(h, today);
}

export function validateFollowUp(d: FollowUpDetail, f: FollowUpFormState, today: string): FollowUpErrors {
  const errors: FollowUpErrors = { hazards: {} };
  if (f.participants.length === 0) errors.participants = "참여 근로자를 입력하십시오";
  for (const h of d.hazards) {
    const draft = f.hazards[h.hazardId];
    if (!draft || !needsPlan(h, draft, today)) continue;
    const e: HazardErrors = {};
    if (!draft.content.trim()) e.content = "개선대책을 입력하십시오";
    if (!draft.owner.trim()) e.owner = "담당을 입력하십시오";
    if (!draft.dueDate) e.dueDate = "기한을 입력하십시오";
    else if (draft.dueDate < today) e.dueDate = "오늘 이후로";
    if (Object.keys(e).length > 0) errors.hazards[h.hazardId] = e;
  }
  return errors;
}

export function hasErrors(e: FollowUpErrors): boolean {
  return Boolean(e.participants) || Object.keys(e.hazards).length > 0;
}

/** 요청 본문. 허용 불가이고 이번 대책이 아직 없을 때만 대책 칸을 보낸다 */
export function toFollowUpRequest(d: FollowUpDetail, f: FollowUpFormState): FollowUpRequest {
  return {
    inspector: f.inspector.trim() || null,
    participants: f.participants,
    hazards: d.hazards.map((h) => {
      const draft = f.hazards[h.hazardId];
      const send = draft && !draft.acceptable && h.action === null && draft.content.trim() !== "";
      return {
        hazardId: h.hazardId,
        acceptable: draft ? draft.acceptable : h.acceptable,
        content: send ? draft.content.trim() : null,
        owner: send ? draft.owner.trim() || null : null,
        dueDate: send ? draft.dueDate || null : null,
      };
    }),
  };
}
