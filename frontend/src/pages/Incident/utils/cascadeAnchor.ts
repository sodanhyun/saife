// cascadeAnchor.ts — 사고 연쇄 스텝(kind)과 화면상 카드 앵커(DOM id)의 매핑.
// CascadeList(스크롤 대상 결정)와 IncidentResult(카드에 id 부여)가 함께 참조한다.
import type { CascadeKind } from "@/types/incident";

export const CASCADE_ANCHOR_BY_KIND: Record<CascadeKind, string> = {
  RECALL: "cascade-recall",
  FOLLOW_UP: "cascade-followup",
  REPORT: "cascade-report",
  WORK_PLAN: "cascade-workplans",
};
