import type { AccidentType, RiskLevel } from "@/types/domain";

/** 백엔드 VisionAssessmentService와 1:1 */

/** 사진 판독 검증(2026-09-21) 통과 여부 */
export type GateStatus = "PHOTO" | "CHECKLIST";

/**
 * @property adopted      null이면 아직 사람이 판단하지 않았다
 * @property alreadyKnown 이 설비에 이미 등록돼 있던 위험요인. 신규 발견이 아니라 재확인이다
 * @property gateStatus   CHECKLIST면 그 축은 게이트 미통과. 참고용이고 채택률에 안 들어간다
 * @property gateNote     미통과 축에 붙는 안내 문장. 백엔드가 만든다
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
  gateStatus: GateStatus;
  gateNote: string | null;
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
  /** 지표에 집계된 축 (게이트 통과 축만) */
  axes: string[];
  note: string;
}
