/** 백엔드 io.saife.core.action.ActionDtos와 1:1 */
import type { AccidentType } from "@/types/domain";

export type ActionStatus = "PENDING" | "DONE" | "OVERDUE";

/** 감소대책 우선순위(고시 제12조). 순서가 곧 우선순위다. 백엔드 ControlPriority */
export type ControlPriority = "ELIMINATION" | "ENGINEERING" | "ADMINISTRATIVE" | "PPE";

export const CONTROL_PRIORITY_LABEL: Record<ControlPriority, string> = {
  ELIMINATION: "제거",
  ENGINEERING: "공학적",
  ADMINISTRATIVE: "관리적",
  PPE: "보호구",
};

export const CONTROL_PRIORITIES: ControlPriority[] = ["ELIMINATION", "ENGINEERING", "ADMINISTRATIVE", "PPE"];

/** 개선대책 등록 요청. assessmentId를 비우면 그 위험요인이 등장한 가장 최근 평가에 묶인다 */
export interface CreateActionRequest {
  assessmentId: number | null;
  content: string;
  owner: string | null;
  dueDate: string | null;
  guideRef: string | null;
  priority: ControlPriority | null;
}

/**
 * @property equipmentId 이 조치가 걸린 설비. "설비 이력" 링크에 쓴다
 * @property completedAt 이행 완료 시각. DONE일 때만 값이 있다
 * @property priority    감소대책 우선순위. 미기재면 null
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
  priority: ControlPriority | null;
}

/**
 * 개선대책 초안. 발생형태와 빠진 조치로 고정 표에서 고른다(모델 호출 없음).
 * @property lawRef   근거 조문 (예: "산업안전보건기준에 관한 규칙 제42조제4항")
 * @property lawTitle 조문 제목
 * @property guideRef 근거 카드에 붙은 KOSHA GUIDE 번호
 * @property priority 이 문안의 감소대책 우선순위
 */
export interface SuggestedAction {
  content: string;
  lawRef: string;
  lawTitle: string | null;
  guideRef: string | null;
  priority: ControlPriority | null;
}

/** 개선대책 목록 필터. 백엔드 ActionListFilter. OPEN은 기한 경과를 포함한다 */
export type ActionListFilter = "OPEN" | "OVERDUE" | "DONE" | "ALL";

/**
 * 개선대책 목록 한 줄. 백엔드 ActionDtos.ActionListItem
 * @property status         판정 상태. 기한이 지난 미완료는 OVERDUE
 * @property overdueDays    기한 경과 일수(KST 오늘 기준). 경과가 아니면 null
 * @property accidentType   위험요인의 발생형태
 * @property missingControl 위험요인의 빠진 안전조치
 */
export interface ActionListItem {
  id: number;
  content: string;
  owner: string | null;
  dueDate: string | null;
  status: ActionStatus;
  overdueDays: number | null;
  completedAt: string | null;
  priority: ControlPriority | null;
  guideRef: string | null;
  hazardId: number | null;
  accidentType: AccidentType | null;
  missingControl: string | null;
  equipmentId: number | null;
  equipmentName: string | null;
  assessmentId: number | null;
}

/** 목록 탭 건수. 백엔드 ActionDtos.ActionCounts. open은 기한 경과를 포함한다 */
export interface ActionCounts {
  open: number;
  overdue: number;
  done: number;
}

export interface ActionSearchParams {
  status: ActionListFilter;
  keyword?: string;
  page?: number;
  size?: number;
}
