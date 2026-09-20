import type { AccidentType, RiskLevel } from "@/types/domain";

/** 백엔드 VisionAssessmentService와 1:1 */

/**
 * @property adopted      null이면 아직 사람이 판단하지 않았다
 * @property alreadyKnown 이 설비에 이미 등록돼 있던 위험요인. 신규 발견이 아니라 재확인이다
 */
export interface VisionCandidate {
  hazardId: number;
  accidentType: AccidentType;
  accidentLabel: string;
  missingControl: string;
  evidence: string | null;
  confidence: number | null;
  riskLevel: RiskLevel;
  ruleTrace: string;
  adopted: boolean | null;
  alreadyKnown: boolean;
}

export interface VisionAnalysisResult {
  assessmentId: number;
  status: string;
  candidates: VisionCandidate[];
  demoMode: boolean;
}

/** 정확도가 아니라 사람이 채택한 비율이다 */
export interface AdoptionRate {
  suggested: number;
  adopted: number;
  rate: number | null;
  note: string;
}
