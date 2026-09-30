import type { Evidence } from "@/types/evidence";
import type { WorkPlanStatus } from "@/types/domain";

/** 백엔드 WorkPlanDtos와 1:1 */

export interface WorkPlanListItem {
  id: number;
  equipmentId: number | null;
  equipmentName: string | null;
  workName: string;
  workPlace: string | null;
  workDate: string;
  status: WorkPlanStatus;
  briefingAcknowledged: boolean;
  briefingAckAt: string | null;
}

/** @property conflicted 대장 기록과 오늘 답변이 다르다. 그대로 보여준다 */
export interface WorkPlanSlot {
  slotKey: string;
  question: string;
  ledgerValue: string | null;
  answeredValue: string | null;
  conflicted: boolean;
  answeredAt: string | null;
}

export interface WorkPlanWorker {
  id: number;
  name: string;
  position: string | null;
  duty: string | null;
}

export interface WorkPlanDetail {
  id: number;
  siteId: number;
  equipmentId: number | null;
  equipmentName: string | null;
  conversationId: string | null;
  workName: string;
  workPlace: string | null;
  workDate: string;
  workHours: number | null;
  method: string | null;
  notes: string | null;
  briefing: string | null;
  briefingAckAt: string | null;
  status: WorkPlanStatus;
  approvalNote: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  slots: WorkPlanSlot[];
  workers: WorkPlanWorker[];
  /** 백엔드 B1 Task 4가 아직 안 내려주면 undefined — 모달은 있고 비어있지 않을 때만 그린다 */
  evidence?: Evidence[];
  /** 사고 연쇄(UC2)가 붙인 경고 — 백엔드 `IncidentDtos.AffectedWorkPlan`이 같은 문구를 여기 남긴다 */
  warningNote?: string | null;
}
