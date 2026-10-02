// 상태색 매핑의 단일 소스(SSOT). 색 리터럴을 쓰지 않고 tailwind 토큰(risk/pending/progress)
// 유틸리티 클래스만 반환한다. 컴포넌트에서 상태→클래스를 직접 분기하지 않는다.
//
// 색 정책: 색은 편차에만 쓴다. 정상·일반 정보는 무채색(neutral).
import type { ReportStatus, RiskLevel, WorkPlanStatus } from "@/types/domain";
import type { Emphasis } from "@/types/timeline";

export type Tone = "high" | "medium" | "low" | "pending" | "progress" | "neutral";

export interface ToneClasses {
  /** 연톤 칩(배경+글자+테두리) — 배지·태그 */
  chip: string;
  /** 글자만 */
  text: string;
  /** 연톤 배경만 — 카드·행에 색조만 입힐 때 */
  bg: string;
  /** 테두리만 */
  border: string;
  /** 솔리드 배경 — 상태 점·버튼 */
  solid: string;
  /** 포커스·선택 링 */
  ring: string;
}

const TONES: Record<Tone, ToneClasses> = {
  high: {
    chip: "bg-risk-high-bg text-risk-high-text border-risk-high-border",
    text: "text-risk-high-text",
    bg: "bg-risk-high-bg",
    border: "border-risk-high-border",
    solid: "bg-risk-high",
    ring: "ring-risk-high-border",
  },
  medium: {
    chip: "bg-risk-medium-bg text-risk-medium-text border-risk-medium-border",
    text: "text-risk-medium-text",
    bg: "bg-risk-medium-bg",
    border: "border-risk-medium-border",
    solid: "bg-risk-medium",
    ring: "ring-risk-medium-border",
  },
  low: {
    chip: "bg-risk-low-bg text-risk-low-text border-risk-low-border",
    text: "text-risk-low-text",
    bg: "bg-risk-low-bg",
    border: "border-risk-low-border",
    solid: "bg-risk-low",
    ring: "ring-risk-low-border",
  },
  pending: {
    chip: "bg-pending-bg text-pending-text border-pending-border",
    text: "text-pending-text",
    bg: "bg-pending-bg",
    border: "border-pending-border",
    solid: "bg-pending",
    ring: "ring-pending-border",
  },
  progress: {
    chip: "bg-progress-bg text-progress-text border-progress-border",
    text: "text-progress-text",
    bg: "bg-progress-bg",
    border: "border-progress-border",
    solid: "bg-progress",
    ring: "ring-progress-border",
  },
  neutral: {
    chip: "bg-slate-100 text-slate-700 border-slate-200",
    text: "text-slate-600",
    bg: "bg-slate-50",
    border: "border-slate-200",
    solid: "bg-slate-400",
    ring: "ring-slate-400",
  },
};

export function toneColor(tone: Tone): ToneClasses {
  return TONES[tone];
}

const RISK_TONE: Record<RiskLevel, Tone> = { HIGH: "high", MEDIUM: "medium", LOW: "low" };

export function riskTone(level: RiskLevel): Tone {
  return RISK_TONE[level];
}

export function riskColor(level: RiskLevel): ToneClasses {
  return TONES[RISK_TONE[level]];
}

/** 작업계획서 상태 — 승인 대기만 눈에 걸리고, 반려만 이탈이다. 나머지는 무채색. */
export function workPlanStatusTone(status: WorkPlanStatus): Tone {
  if (status === "SUBMITTED") return "pending";
  if (status === "REJECTED" || status === "HOLD") return "high";
  return "neutral";
}

/** 타임라인 강조도 — 백엔드가 계산한 emphasis를 그대로 색으로 옮긴다. */
export function emphasisTone(emphasis: Emphasis): Tone {
  if (emphasis === "CRITICAL") return "high";
  if (emphasis === "WARNING") return "pending";
  return "neutral";
}

/** 조사표 제출 의무 — 기한 경과는 이탈, 제출 필요는 주시. */
export function reportDutyTone(status: ReportStatus): Tone {
  if (status === "OVERDUE") return "high";
  if (status === "REQUIRED") return "pending";
  return "neutral";
}
