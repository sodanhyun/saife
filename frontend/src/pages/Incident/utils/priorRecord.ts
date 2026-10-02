// priorRecord.ts — 등록 응답에서 "사고 전 기록"(사고 전 평가, 감소대책, 사고 시점 경과일)과 화면 제목을 뽑는 순수 함수.
// 판단 문장을 만들지 않는다. 응답에 실린 사실만 고른다.
import { ACCIDENT_LABEL } from "@/types/domain";
import type { IncidentRegisterResponse, IncidentSummary, PriorHazard, UnfinishedAction } from "@/types/incident";
import { formatDate, formatDateTime } from "@/utils/datetime";

/**
 * 화면에 내보내는 문자열을 정리한다(구두점 규칙): 가운뎃점과 대시 구분자는 쉼표로,
 * 화살표는 쉼표로, 따옴표로 감싼 등급('상')은 따옴표를 뗀다. 날짜와 D-day의 하이픈은 건드리지 않는다.
 */
export function plain(text: string | null | undefined): string {
  if (!text) return "";
  return text
    .replace(/\s*[·—–]\s*/g, ", ")
    .replace(/ - /g, ", ")
    .replace(/\s*→\s*/g, ", ")
    .replace(/'([상중하])'/g, "$1")
    .replace(/,\s*,/g, ",");
}

/** "2026-10-02" 또는 ISO → "10-02" (촘촘한 목록용) */
export function shortDate(value: string | null | undefined): string {
  const d = formatDate(value);
  return d === "-" ? d : d.slice(5);
}

/** ISO → "10-02 10:20" */
export function shortDateTime(iso: string | null | undefined): string {
  const d = formatDateTime(iso);
  return d === "-" ? d : d.slice(5);
}

/** 결과 화면 제목(사실형): "떨어짐 사고, 이동식 사다리 A, 10-02 10:20" */
export function incidentTitle(inc: IncidentSummary): string {
  const type = inc.accidentType ? `${ACCIDENT_LABEL[inc.accidentType]} 사고` : "사고";
  return [type, inc.equipmentName ?? "설비 미상", shortDateTime(inc.occurredAt)].join(", ");
}

export interface PriorRecord {
  /** 사고와 같은 발생형태의 사전 위험요인(등급이 있는 것 우선) */
  hazard: PriorHazard | null;
  /** 사고 시점 미이행 감소대책(기한이 가장 오래 지난 것 우선) */
  action: UnfinishedAction | null;
}

export function buildPriorRecord(r: IncidentRegisterResponse): PriorRecord {
  const sameAxis = r.recall.priorHazards.filter((h) => h.sameAxisAsIncident);
  const hazard = sameAxis.find((h) => h.lastRiskLevel) ?? sameAxis[0] ?? null;
  const actions = r.recall.unfinishedActions;
  const action = actions.find((a) => a.overdueDays !== null && a.overdueDays > 0) ?? actions[0] ?? null;
  return { hazard, action };
}

/** 사고 시점 기준 감소대책 상태: "42일 경과" / "기한 전" / "" */
export function elapsedLabel(a: UnfinishedAction): string {
  if (a.overdueDays === null) return "";
  return a.overdueDays > 0 ? `${a.overdueDays}일 경과` : "기한 전";
}
