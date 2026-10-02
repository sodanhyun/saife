import type { AccidentType, RiskLevel } from "@/types/domain";
import type { PriorHazard, PriorIncident, PriorWorkPlan, UnfinishedAction } from "@/types/incident";

/** 백엔드 TimelineDtos와 1:1 */

export type EventType = "ASSESSMENT" | "ACTION" | "WORK_PLAN" | "INCIDENT";
export type Emphasis = "NORMAL" | "WARNING" | "CRITICAL";

export const EVENT_LABEL: Record<EventType, string> = {
  ASSESSMENT: "위험성평가",
  ACTION: "개선대책",
  WORK_PLAN: "작업 전 점검",
  INCIDENT: "사고",
};

export interface EquipmentHead {
  id: number;
  name: string;
  locationTag: string | null;
  processName: string | null;
  objectCode: string | null;
  introducedOn: string | null;
}

/**
 * @property currentRiskLevel 발생형태마다 가장 최근 평가 등급을 보고 그중 가장 높은 등급
 * @property incidentCount    사고 수(아차사고 제외)
 * @property nearMissCount    아차사고 수
 * @property headline 상태 칩 하나("기한 경과 1", "사고 1", "최초 평가 필요", "미이행 1", "아차사고 1"). 해당 없으면 빈 문자열
 */
export interface TimelineSummary {
  currentRiskLevel: RiskLevel | null;
  currentRiskAxis: AccidentType | null;
  lastAssessedOn: string | null;
  assessmentCount: number;
  workPlanCount: number;
  incidentCount: number;
  nearMissCount: number;
  unfinishedActionCount: number;
  overdueActionCount: number;
  headline: string;
}

/**
 * @property linkedEventIds 연결선의 반대쪽 끝. 백엔드가 계산한다
 * @property linkedLabels   그 대상을 사람이 읽는 말로. 화면에는 이쪽을 띄운다
 * @property causalOrder    같은 날짜 안의 인과 순서 (백엔드가 정렬까지 마쳐서 준다)
 * @property ruleTrace      평가 사건의 최고 등급 룰 근거. 평가가 아닌 사건은 null
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
  ruleTrace: string | null;
}

export interface EquipmentTimeline {
  equipment: EquipmentHead;
  summary: TimelineSummary;
  events: TimelineEvent[];
}

/**
 * 설비 홈 카드 — 설비 하나의 현재 상태를 한 장으로. `unfinishedActionCount`·
 * `overdueActionCount`·`incidentCount`·`currentRiskLevel`은 같은 설비의
 * `EquipmentTimeline.summary`와 항상 같다(백엔드가 같은 계산을 재사용해서 낸다).
 * @property emphasis 카드 테두리·배지 톤. 백엔드가 정한다 — 화면마다 다르게 판단하면 안 된다
 * @property headline 상태 칩. TimelineSummary.headline과 같은 값
 */
export interface EquipmentCard {
  id: number;
  name: string;
  locationTag: string | null;
  processName: string | null;
  currentRiskLevel: RiskLevel | null;
  currentRiskAxis: AccidentType | null;
  lastAssessedOn: string | null;
  unfinishedActionCount: number;
  overdueActionCount: number;
  upcomingWorkPlanCount: number;
  /** 사고 수(아차사고 제외) */
  incidentCount: number;
  /** 아차사고 수 */
  nearMissCount: number;
  lastEventOn: string | null;
  emphasis: Emphasis;
  headline: string;
}

/**
 * 설비 회상 뷰 — 백엔드 `TimelineDtos.RecallView`와 1:1. `IncidentDtos.RecallView`가
 * 같은 구조를 재사용하므로(백엔드가 delegate) `types/incident.ts`의 `RecallView`와
 * 필드가 겹친다 — Prior* 타입은 그쪽 정의를 그대로 가져와 중복 선언하지 않는다.
 * @property knownSlots 이 설비에 대해 시스템이 이미 아는 항목의 사람 말
 *   (["장소","설비","공정/작업유형","최근 평가 등급","미이행 조치"] 중 값이 있는 것만)
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

/** 오늘 할 일 인박스 — 백엔드 `TodayView`/`TodayItem`(dashboard.service.TodayService)와 1:1. */
export type TodayKind =
  | "OVERDUE_ACTION"
  | "DUE_ACTION"
  | "RISKY_WORK_PLAN"
  | "PENDING_APPROVAL"
  | "REPORT_DUE"
  | "PATROL_DUE"
  | "PERIODIC_DUE"
  | "WORK_HOLD";

/** 클릭 시 이동 대상 종류. 화면 쪽 행동 버튼 표는 `pages/EquipmentHome/utils/todayModel.ts`가 정한다. */
export type TodayLinkType = "EQUIPMENT" | "WORK_PLAN" | "INCIDENT" | "ASSESSMENT";

export interface TodayItem {
  kind: TodayKind;
  emphasis: Emphasis;
  title: string;
  detail: string;
  equipmentId: number | null;
  equipmentName: string | null;
  dueDate: string | null;
  daysRemaining: number | null;
  linkType: TodayLinkType;
  refId: number | null;
  /** 작업 보류(WORK_HOLD) 항목: 보류를 푸는 수시평가 id. 다른 항목은 null */
  assessmentId: number | null;
}

export interface TodayView {
  asOf: string;
  items: TodayItem[];
  criticalCount: number;
  warningCount: number;
}
