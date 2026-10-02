import type {
  AccidentType,
  ActionStatus,
  IncidentSeverity,
  ReportStatus,
  RiskLevel,
  WorkPlanStatus,
} from "@/types/domain";
import type { Evidence } from "@/types/evidence";
import type { Emphasis } from "@/types/timeline";

/** 백엔드 IncidentDtos와 1:1. 필드를 바꾸면 양쪽을 같이 바꾼다 */

/**
 * 사고 발생형태(공단 재해 발생형태 분류 중 제조업 주요 항목). 백엔드 `IncidentType`과 1:1.
 * 위험요인 6축(`AccidentType`)과 다르다. 보호구 미착용은 사고의 형태가 아니라 고를 수 없다.
 */
export type IncidentType =
  | "FALL"
  | "TRIP"
  | "CAUGHT"
  | "STRUCK"
  | "DROP"
  | "CRUSHED"
  | "OVERTURN"
  | "COLLAPSE"
  | "CUT"
  | "ELECTRIC"
  | "FIRE"
  | "EXPLOSION"
  | "CHEMICAL"
  | "TEMPERATURE"
  | "STRAIN";

export const INCIDENT_TYPE_LABEL: Record<IncidentType, string> = {
  FALL: "떨어짐",
  TRIP: "넘어짐",
  CAUGHT: "끼임",
  STRUCK: "부딪힘",
  DROP: "물체에 맞음",
  CRUSHED: "깔림",
  OVERTURN: "뒤집힘",
  COLLAPSE: "무너짐",
  CUT: "절단, 베임, 찔림",
  ELECTRIC: "감전",
  FIRE: "화재",
  EXPLOSION: "폭발, 파열",
  CHEMICAL: "화학물질 누출, 접촉",
  TEMPERATURE: "이상온도 접촉",
  STRAIN: "불균형 및 무리한 동작",
};

export interface RegisterIncidentRequest {
  equipmentId?: number | null;
  equipmentQuery?: string | null;
  workPlanId?: number | null;
  occurredAt: string;
  victimName?: string | null;
  severity?: IncidentSeverity | null;
  leaveDays?: number | null;
  incidentType: IncidentType;
  description?: string | null;
  /** 상해 종류(질병명). 조사표 항목 */
  injuryType?: string | null;
  /** 상해 부위(질병 부위). 조사표 항목 */
  injuryPart?: string | null;
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
  incidentType: IncidentType | null;
  incidentTypeLabel: string | null;
  /** 위험요인 6축 대응(사고 전 기록과 잇는 값). 대응이 없으면 null */
  accidentType: AccidentType | null;
  description: string | null;
  injuryType: string | null;
  injuryPart: string | null;
  followUpAssessmentId: number | null;
}

/**
 * @property daysRemaining 음수면 기한이 지났다
 * @property seriousAccidentPossible 사망 재해라 중대재해에 해당할 수 있다(판정 아님, 보고 안내 배너용)
 */
export interface ReportDuty {
  status: ReportStatus;
  statusLabel: string;
  dueDate: string | null;
  daysRemaining: number | null;
  basis: string;
  seriousAccidentPossible: boolean;
  /** 조사표 제출일. 제출 완료가 아니면 null */
  submittedOn: string | null;
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
  /** 조치 담당 */
  owner?: string | null;
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

/**
 * @property predicted 같은 발생형태의 위험요인이 사고 전부터 있었다
 * @property knownSlots 이 설비에 대해 시스템이 이미 아는 항목(연결성 개선 — `types/timeline.ts`의
 *   `RecallView`와 같은 백엔드 DTO를 공유한다). 이 화면(UC2)에서는 아직 쓰지 않지만
 *   응답에 실려 오므로 타입에 반영해 둔다(additive, 기존 필드는 그대로).
 */
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
  knownSlots: string[];
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

export type FollowUpStatus = "DRAFT" | "CONFIRMED";

export interface FollowUpView {
  assessmentId: number | null;
  kindLabel: string;
  legalBasis: string;
  regraded: Regrade[];
  newHazardId: number | null;
  status: FollowUpStatus;
  /** 확정일. 작성 중이면 null */
  confirmedOn: string | null;
}

/** @property aiGenerated false면 모델이 아니라 폴백 문안이다. 화면에 구분해 표시한다 */
export interface DraftView {
  cause: string;
  prevention: string;
  aiGenerated: boolean;
  disclaimer: string;
}

/** 사고 연쇄 4단계 중 하나 — 백엔드 `IncidentDtos.CascadeStep`과 1:1(record). 순서는 백엔드가 정한다(화면마다 다르게 판단하면 흔들린다).
 * order 1 RECALL: "이 설비의 사전 기록 소환" — predicted면 CRITICAL, headline을 detail로
 * order 2 FOLLOW_UP: "수시평가 #N 자동 생성" — 등급 변화 요약(중→상 n건, 유지 m건)
 * order 3 REPORT: "산업재해조사표 기한" — D-n · 법적 근거
 * order 4 WORK_PLAN: "진행 중 작업계획서 n건에 경고 부착" — affectedWorkPlans 요약(0건이면 detail "해당 없음", NORMAL)
 */
export type CascadeKind = "RECALL" | "FOLLOW_UP" | "REPORT" | "WORK_PLAN";

export interface CascadeStep {
  order: number;
  kind: CascadeKind;
  title: string;
  detail: string;
  emphasis: Emphasis;
  refId: number | null;
  refType: string | null;
}

/** 사고로 작업 보류(HOLD)된 작업 전 점검 1건. 백엔드 `IncidentDtos.AffectedWorkPlan`과 1:1(record).
 * 같은 설비, 사고 당시 상태 SUBMITTED/APPROVED/CONDITIONAL, workDate >= 사고일인 계획서만 온다 */
export interface AffectedWorkPlan {
  workPlanId: number;
  workName: string;
  workDate: string;
  status: WorkPlanStatus;
  warning: string;
}

/**
 * @property similarCases 동종 유사 사고(공단 사례) 근거 카드. B1 Task 5가 채울 때까지 응답에 없을 수 있다(optional)
 * @property evidence     조사표 초안(cause·prevention)의 인용 [#n]이 가리키는 근거 원장 전체.
 *   `RecallView`가 아니라 이 응답 최상위에 실린다(컨트롤러 판단 R28) — `RecallView`는 UC4와 공유하는
 *   DTO라 여기서 손대지 않는다.
 * @property cascade 사고 연쇄 4단계(순서 고정, order 1~4). Task 3a(백엔드)가 채울 때까지 응답에 없을 수 있다(optional)
 * @property affectedWorkPlans 사고 영향을 받는 진행 중 작업계획서 목록. cascade와 같은 시점에 Task 3a가 채운다(optional)
 */
export interface IncidentRegisterResponse {
  incident: IncidentSummary;
  reportDuty: ReportDuty;
  recall: RecallView;
  followUp: FollowUpView;
  draft: DraftView;
  similarCases?: Evidence[];
  evidence?: Evidence[];
  cascade?: CascadeStep[];
  affectedWorkPlans?: AffectedWorkPlan[];
}

export interface IncidentListItem {
  id: number;
  equipmentId: number | null;
  equipmentName: string | null;
  occurredAt: string;
  incidentType: IncidentType | null;
  incidentTypeLabel: string | null;
  severity: IncidentSeverity | null;
  leaveDays: number | null;
  reportStatus: ReportStatus;
  reportStatusLabel: string;
  reportDueDate: string | null;
  daysRemaining: number | null;
  reportSubmittedOn: string | null;
  followUpAssessmentId: number | null;
}

// ────────────────────────── 수시평가 (사고 후, 작업 재개 전) ──────────────────────────

/** 백엔드 `IncidentDtos.FollowUpAction`과 1:1 */
export interface FollowUpAction {
  actionId: number;
  content: string;
  owner: string | null;
  dueDate: string | null;
  status: ActionStatus;
  completedOn: string | null;
}

export interface FollowUpSuggestion {
  content: string;
  lawRef: string;
}

/**
 * @property before 사고 전 등급. 신규면 null
 * @property acceptable 허용 가능 여부(사람이 정하지 않았으면 등급 기본값: 상, 중은 불가)
 * @property action 이 수시평가에서 세운 개선대책
 * @property priorAction 다른 평가에서 세운, 아직 끝나지 않은 대책
 */
export interface FollowUpHazard {
  hazardId: number;
  accidentType: AccidentType | null;
  missingControl: string | null;
  description: string | null;
  before: RiskLevel | null;
  riskLevel: RiskLevel;
  ruleTrace: string | null;
  sameAxis: boolean;
  acceptable: boolean;
  action: FollowUpAction | null;
  priorAction: FollowUpAction | null;
  suggestion: FollowUpSuggestion | null;
}

/** 사고가 만든 수시평가 한 건. 백엔드 `IncidentDtos.FollowUpDetail`과 1:1 */
export interface FollowUpDetail {
  assessmentId: number;
  incidentId: number | null;
  status: FollowUpStatus;
  assessedOn: string;
  confirmedOn: string | null;
  equipmentId: number | null;
  equipmentName: string | null;
  locationTag: string | null;
  occurredAt: string | null;
  incidentType: IncidentType | null;
  incidentTypeLabel: string | null;
  severity: IncidentSeverity | null;
  legalBasis: string;
  inspector: string | null;
  participants: string[];
  hazards: FollowUpHazard[];
  workPlans: AffectedWorkPlan[];
}

export interface FollowUpHazardInput {
  hazardId: number;
  acceptable: boolean | null;
  content: string | null;
  owner: string | null;
  dueDate: string | null;
}

export interface FollowUpRequest {
  inspector: string | null;
  participants: string[];
  hazards: FollowUpHazardInput[];
}
