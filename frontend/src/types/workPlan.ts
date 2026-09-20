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
}
