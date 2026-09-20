import type {
  AccidentType,
  ActionStatus,
  IncidentSeverity,
  ReportStatus,
  RiskLevel,
} from "@/types/domain";

/** 백엔드 IncidentDtos와 1:1. 필드를 바꾸면 양쪽을 같이 바꾼다 */

export interface RegisterIncidentRequest {
  equipmentId?: number | null;
  equipmentQuery?: string | null;
  workPlanId?: number | null;
  occurredAt: string;
  victimName?: string | null;
  severity?: IncidentSeverity | null;
  leaveDays?: number | null;
  accidentType?: AccidentType | null;
  description?: string | null;
}

export interface IncidentSummary {
  id: number;
  siteId: number;
  equipmentId: number | null;
  equipmentName: string | null;
  workPlanId: number | null;
  occurredAt: string;
  victimName: string | null;
  severity: IncidentSeverity | null;
  leaveDays: number | null;
  accidentType: AccidentType | null;
  description: string | null;
  followUpAssessmentId: number | null;
}

/** @property daysRemaining 음수면 기한이 지났다 */
export interface ReportDuty {
  status: ReportStatus;
  statusLabel: string;
  dueDate: string | null;
  daysRemaining: number | null;
  basis: string;
}

export interface PriorHazard {
  hazardId: number;
  accidentType: AccidentType | null;
  missingControl: string | null;
  description: string | null;
  lastRiskLevel: RiskLevel | null;
  lastAssessedOn: string | null;
  lastRuleTrace: string | null;
  sameAxisAsIncident: boolean;
}

export interface UnfinishedAction {
  actionId: number;
  content: string;
  dueDate: string | null;
  status: ActionStatus;
  overdueDays: number | null;
  guideRef: string | null;
}

export interface PriorWorkPlan {
  workPlanId: number;
  workName: string;
  workDate: string;
  briefingAckAt: string | null;
  status: string;
}

export interface PriorIncident {
  incidentId: number;
  occurredAt: string;
  accidentType: AccidentType | null;
  description: string | null;
}

/** @property predicted 같은 발생형태의 위험요인이 사고 전부터 있었다 */
export interface RecallView {
  equipmentId: number | null;
  equipmentName: string;
  locationTag: string | null;
  headline: string;
  predicted: boolean;
  warnedAt: string | null;
  priorHazards: PriorHazard[];
  unfinishedActions: UnfinishedAction[];
  priorWorkPlans: PriorWorkPlan[];
  priorIncidents: PriorIncident[];
}

export interface Regrade {
  hazardId: number;
  accidentType: AccidentType | null;
  missingControl: string | null;
  before: RiskLevel | null;
  after: RiskLevel;
  ruleTrace: string;
  changed: boolean;
}

export interface FollowUpView {
  assessmentId: number | null;
  kindLabel: string;
  legalBasis: string;
  regraded: Regrade[];
  newHazardId: number | null;
}

/** @property aiGenerated false면 모델이 아니라 폴백 문안이다. 화면에 구분해 표시한다 */
export interface DraftView {
  cause: string;
  prevention: string;
  aiGenerated: boolean;
  disclaimer: string;
}

export interface IncidentRegisterResponse {
  incident: IncidentSummary;
  reportDuty: ReportDuty;
  recall: RecallView;
  followUp: FollowUpView;
  draft: DraftView;
}

export interface IncidentListItem {
  id: number;
  equipmentId: number | null;
  equipmentName: string | null;
  occurredAt: string;
  accidentType: AccidentType | null;
  severity: IncidentSeverity | null;
  leaveDays: number | null;
  reportStatus: ReportStatus;
  reportStatusLabel: string;
  reportDueDate: string | null;
  daysRemaining: number | null;
  followUpAssessmentId: number | null;
}
