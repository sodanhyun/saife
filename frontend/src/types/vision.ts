import type { ActionView, SuggestedAction } from "@/types/action";
import type { AccidentType, RiskLevel } from "@/types/domain";
import type { Evidence } from "@/types/evidence";

/** 백엔드 VisionAssessmentService와 1:1 */

/** 사진 검증(2026-09-21) 통과 여부. CHECKLIST면 현장 확인이 필요한 발생형태다 */
export type GateStatus = "PHOTO" | "CHECKLIST";

/**
 * 순회점검에서 나온 위험요인 한 건.
 * @property adopted      반영 여부. null이면 아직 판단 전, false면 제외
 * @property alreadyKnown 이 설비에 이미 등록돼 있던 위험요인(재확인)
 * @property acceptable   허용 가능 여부. 사람이 정하지 않았으면 등급 기본값(상, 중은 불가)
 * @property evidence     판독 내용. 사진의 어디를 보고 그렇게 봤는지
 * @property evidenceItems 근거 카드(지침, 조문, 사례)
 * @property suggestedAction 개선대책 초안. 반영 후 등록 폼을 미리 채운다
 * @property action          이 점검에서 등록한 개선대책. 없으면 null
 * @property priorOpenAction 다른 평가에서 걸어 둔 미이행 조치
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
  acceptable: boolean;
  evidenceItems?: Evidence[];
  suggestedAction: SuggestedAction | null;
  action: ActionView | null;
  priorOpenAction: ActionView | null;
  /** 사진에서 이 위험요인이 보이는 위치 [ymin, xmin, ymax, xmax] (0~1000). 없으면 null */
  box: number[] | null;
}

/** assess.progress 단계. 백엔드 VisionAssessmentService.PHASE_* */
export type VisionPhase = "ANALYZING" | "GRADING" | "EVIDENCE";

export interface VisionProgress {
  phase?: VisionPhase;
  message?: string;
}

export interface VisionAnalysisResult {
  assessmentId: number;
  status: string;
  assessedOn: string;
  inspector: string | null;
  participants: string[];
  equipmentId: number | null;
  candidates: VisionCandidate[];
  demoMode: boolean;
}

/** 점검 정보(점검자, 참여 근로자) 갱신 */
export interface InspectionRequest {
  inspector: string | null;
  participants: string[];
}

export interface InspectionView {
  assessmentId: number;
  inspector: string | null;
  participants: string[];
}

export interface AcceptableView {
  hazardId: number;
  acceptable: boolean;
}

/** 최근 순회점검 한 줄. 백엔드 VisionAssessmentService.RecentInspection */
export interface RecentInspection {
  assessmentId: number;
  assessedOn: string;
  inspector: string | null;
  participants: string[];
  equipmentNames: string[];
  hazardCount: number;
  highCount: number;
  complete: boolean;
}

/** 반영 비율(검증 기록용, 화면에 쓰지 않는다) */
export interface AdoptionRate {
  suggested: number;
  adopted: number;
  rate: number | null;
  axes: string[];
  note: string;
}
