// resolveTodayLink.ts — "오늘 할 일" 항목 클릭 시 이동 대상을 linkType별로 정한다(자동 이동 없음, 클릭만).
import { formUrl } from "@/api/formUrl";
import type { TodayItem } from "@/types/timeline";

export interface ResolvedTodayLink {
  href: string;
  /** true면 법정 서식처럼 새 탭에서 연다(formUrl 관례). false면 react-router Link로 앱 안에서 이동한다. */
  external: boolean;
}

/**
 * linkType별 이동 표.
 * - EQUIPMENT      → 설비 상세
 * - WORK_PLAN      → 상세 모달 딥링크가 아직 없어 설비 상세로 보낸다(딥링크가 생기면 그쪽으로 교체)
 * - INCIDENT       → 사고 신고 화면
 * - ASSESSMENT     → 법정 서식(`formUrl.assessment`)이 있으면 그것, 없으면 설비 상세
 * 설비 ID조차 없으면 이동시키지 않는다(클릭 불가능한 정적 행).
 */
export function resolveTodayLink(item: TodayItem): ResolvedTodayLink | null {
  if (item.linkType === "INCIDENT") return { href: "/incident", external: false };
  if (item.linkType === "ASSESSMENT" && item.refId !== null) {
    return { href: formUrl.assessment(item.refId), external: true };
  }
  if (item.equipmentId !== null) return { href: `/equipment/${item.equipmentId}`, external: false };
  return null;
}
