/** 백엔드 io.saife.core.action.ActionDtos와 1:1 */

export type ActionStatus = "PENDING" | "DONE" | "OVERDUE";

/** 감소대책 등록 요청. assessmentId를 비우면 그 위험요인이 등장한 가장 최근 평가에 묶인다 */
export interface CreateActionRequest {
  assessmentId: number | null;
  content: string;
  owner: string | null;
  dueDate: string | null;
  guideRef: string | null;
}

/**
 * @property equipmentId 이 조치가 걸린 설비. "이 설비 타임라인에 기록됨" 링크에 쓴다
 * @property completedAt 이행 완료 시각. DONE일 때만 값이 있다
 */
export interface ActionView {
  id: number;
  hazardId: number;
  assessmentId: number | null;
  equipmentId: number | null;
  content: string;
  owner: string | null;
  dueDate: string | null;
  status: ActionStatus;
  guideRef: string | null;
  completedAt: string | null;
  createdAt: string;
}

/**
 * 감소대책 초안. 축과 빠진 조치로 고정 표에서 고른다(모델 호출 없음).
 * @property lawRef   근거 조문 (예: "산업안전보건기준에 관한 규칙 제44조")
 * @property lawTitle 조문 제목 요약
 * @property guideRef 후보 근거 카드에 붙은 KOSHA GUIDE 번호
 */
export interface SuggestedAction {
  content: string;
  lawRef: string;
  lawTitle: string | null;
  guideRef: string | null;
}
