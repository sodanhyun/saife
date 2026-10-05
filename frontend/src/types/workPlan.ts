import type { Evidence } from "@/types/evidence";
import type { AccidentType, RiskLevel, WorkPlanStatus } from "@/types/domain";

/** 백엔드 WorkPlanDtos와 1:1. 화면 이름은 「작업 전 안전점검표」 */

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
  /** TBM_CHECKLIST(작업 전 안전점검표) 또는 WORK_PLAN(제38조 작업계획서) */
  documentType: "TBM_CHECKLIST" | "WORK_PLAN";
}

/** @property conflicted 대장 기록과 오늘 답변이 다르다. 그대로 보여준다 */
export interface WorkPlanSlot {
  slotKey: string;
  /** 짧은 항목 이름(발판 높이 등) */
  label: string;
  /** 단위와 표기를 정리한 값(3.2 m, 사용, 없음). 답이 없으면 null */
  displayValue: string | null;
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
  /** 브리핑의 구조화 뷰(등급 배지 + 룰 근거). 브리핑이 없는 초안이면 null */
  briefingView?: BriefingView | null;
  /** TBM_CHECKLIST(작업 전 안전점검표) 또는 WORK_PLAN(제38조 작업계획서) */
  documentType: "TBM_CHECKLIST" | "WORK_PLAN";
  /** 화면과 서식 제목 */
  documentTitle: string;
  /** 관리감독자(작업 담당 반장 실명). 제38조 작업계획서면 작업지휘자(제39조). 없으면 null */
  supervisor: string | null;
  /** 작업 보류를 푸는 수시평가 ID(같은 설비 사고가 만든 평가). 없으면 null */
  holdAssessmentId: number | null;
}

/** 백엔드 WorkPlanDtos.BriefingView와 1:1. 문장 브리핑과 같은 룰 엔진 판정에서 나온다 */
export interface BriefingView {
  pendingActions: PendingActionView[];
  decisions: HazardDecision[];
  msds: MsdsSummary | null;
  /** 작업자 안내: 주의할 점(3개 이내) */
  riskPoints: string[];
  /** 작업자 안내: 안전수칙(3개 이내) */
  keepPoints: string[];
  /** 상 판정에 대책 미이행: 잠정조치를 적어야 승인할 수 있다(조건부 승인) */
  interimRequired: boolean;
  /** 제38조 작업계획서의 사전조사 항목. 작업 전 안전점검표면 빈 배열 */
  preSurvey: string[];
}

export interface PendingActionView {
  content: string;
  dueDate: string | null;
  overdueDays: number | null;
  lastGrade: RiskLevel | null;
}

export interface HazardDecision {
  accidentType: AccidentType;
  label: string;
  riskLevel: RiskLevel;
  frequency: number;
  severity: number;
  ruleTrace: string;
  /** 판정에 맞춘 권고 개선대책(기준표). 상, 중, 하 모두 값이 있다 */
  recommendation: string | null;
}

export interface MsdsSummary {
  chemName: string;
  productName: string;
  /** 제품명으로 찾지 못해 주성분을 추정했다(제품 MSDS 확인 필요) */
  inferred: boolean;
  lines: { item: string; text: string }[];
}

/** 유사 재해사례 항목. 머리말과 원문 설명 한 줄. 백엔드 CaseDigest.Item */
export interface CaseItem {
  head: string;
  detail: string | null;
}

/**
 * 유사 재해사례. 백엔드 SimilarCaseService.SimilarCase. 원문 문장을 자른 것이고 새로 쓴 문장이 없다
 * @property title    사례 제목(공단 키워드)
 * @property year     발생 연도 표기 (예: "2001년")
 * @property summary  재해 개요 한 문장
 */
export interface SimilarCase {
  id: number;
  title: string;
  business: string | null;
  year: string | null;
  summary: string | null;
  causes: CaseItem[];
  measures: CaseItem[];
  sourceUrl: string | null;
}
