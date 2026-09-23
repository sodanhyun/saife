/**
 * 도메인 enum — 백엔드 enum과 1:1 union.
 * 규약: .claude/rules/api-contract.md
 */

export type AccidentType = "FALL" | "CAUGHT" | "DROP" | "STRUCK" | "FIRE" | "PPE";
export type RiskLevel = "HIGH" | "MEDIUM" | "LOW";
export type AssessmentKind = "INITIAL" | "OCCASIONAL" | "REGULAR" | "ROUTINE";
export type ActionStatus = "PENDING" | "DONE" | "OVERDUE";
export type WorkPlanStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "APPROVED"
  | "CONDITIONAL"
  | "REJECTED"
  | "CLOSED";
export type IncidentSeverity = "NEAR_MISS" | "INJURY" | "LOST_TIME" | "FATALITY";
export type ReportStatus = "UNDETERMINED" | "NOT_REQUIRED" | "REQUIRED" | "OVERDUE" | "SUBMITTED";

/** 화면 표기 — 백엔드 라벨과 같은 말을 쓴다. 같은 값을 다르게 부르면 안 된다 */
export const ACCIDENT_LABEL: Record<AccidentType, string> = {
  FALL: "추락",
  CAUGHT: "협착",
  DROP: "낙하",
  STRUCK: "부딪힘",
  FIRE: "화재",
  PPE: "보호구",
};

export const RISK_LABEL: Record<RiskLevel, string> = {
  HIGH: "상",
  MEDIUM: "중",
  LOW: "하",
};

/** @deprecated statusColors.riskColor()로 대체. Task 13에서 제거 */
export const RISK_CLASS: Record<RiskLevel, string> = {
  HIGH: "bg-red-100 text-red-800 border-red-300",
  MEDIUM: "bg-amber-100 text-amber-800 border-amber-300",
  LOW: "bg-emerald-100 text-emerald-800 border-emerald-300",
};

export const WORK_PLAN_STATUS_LABEL: Record<WorkPlanStatus, string> = {
  DRAFT: "작성 중",
  SUBMITTED: "승인 대기",
  APPROVED: "승인",
  CONDITIONAL: "조건부 승인",
  REJECTED: "반려",
  CLOSED: "완료",
};

export const SEVERITY_LABEL: Record<IncidentSeverity, string> = {
  NEAR_MISS: "아차사고",
  INJURY: "부상",
  LOST_TIME: "휴업",
  FATALITY: "사망",
};
