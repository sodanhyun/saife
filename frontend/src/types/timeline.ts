import type { AccidentType, RiskLevel } from "@/types/domain";

/** 백엔드 TimelineDtos와 1:1 */

export type EventType = "ASSESSMENT" | "ACTION" | "WORK_PLAN" | "INCIDENT";
export type Emphasis = "NORMAL" | "WARNING" | "CRITICAL";

export const EVENT_LABEL: Record<EventType, string> = {
  ASSESSMENT: "위험성평가",
  ACTION: "감소대책",
  WORK_PLAN: "작업계획서",
  INCIDENT: "산업재해",
};

export interface EquipmentHead {
  id: number;
  name: string;
  locationTag: string | null;
  processName: string | null;
  objectCode: string | null;
  introducedOn: string | null;
}

/** @property headline 프로젝터에서 이 줄만 읽혀도 논지가 전달돼야 한다 */
export interface TimelineSummary {
  currentRiskLevel: RiskLevel | null;
  currentRiskAxis: AccidentType | null;
  lastAssessedOn: string | null;
  assessmentCount: number;
  workPlanCount: number;
  incidentCount: number;
  unfinishedActionCount: number;
  overdueActionCount: number;
  headline: string;
}

/**
 * @property linkedEventIds 연결선의 반대쪽 끝. 백엔드가 계산한다
 * @property linkedLabels   그 대상을 사람이 읽는 말로. 화면에는 이쪽을 띄운다
 * @property causalOrder    같은 날짜 안의 인과 순서 (백엔드가 정렬까지 마쳐서 준다)
 */
export interface TimelineEvent {
  id: string;
  type: EventType;
  at: string;
  occurredAt: string | null;
  title: string;
  detail: string;
  riskLevel: RiskLevel | null;
  accidentType: AccidentType | null;
  status: string | null;
  refId: number;
  linkedEventIds: string[];
  linkedLabels: string[];
  causalOrder: number;
  emphasis: Emphasis;
}

export interface EquipmentTimeline {
  equipment: EquipmentHead;
  summary: TimelineSummary;
  events: TimelineEvent[];
}
