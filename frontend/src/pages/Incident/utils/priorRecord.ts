// priorRecord.ts — 등록 응답에서 "사고 전 기록"(사고 전 평가, 감소대책, 사고 시점 경과일)과 화면 제목을 뽑는 순수 함수.
// 판단 문장을 만들지 않는다. 응답에 실린 사실만 고른다.
import { INCIDENT_TYPE_LABEL, type IncidentRegisterResponse, type IncidentSummary, type PriorHazard, type ReportDuty, type UnfinishedAction } from "@/types/incident";
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

/** 발생형태 라벨. 응답에 라벨이 실려 오면 그것을 쓴다 */
export function incidentTypeLabel(inc: Pick<IncidentSummary, "incidentType" | "incidentTypeLabel">): string | null {
  if (inc.incidentTypeLabel) return inc.incidentTypeLabel;
  return inc.incidentType ? INCIDENT_TYPE_LABEL[inc.incidentType] : null;
}

/**
 * 결과 화면 제목(사실형): "떨어짐 사고, 이동식 사다리 A, 2026-10-02 10:20".
 * 아차사고는 "부딪힘 아차사고, …"로 쓴다(상해가 없어 "사고"가 아니다).
 */
export function incidentTitle(inc: IncidentSummary): string {
  const label = incidentTypeLabel(inc);
  const kind = inc.severity === "NEAR_MISS" ? "아차사고" : "사고";
  const type = label ? `${label} ${kind}` : kind;
  return [type, inc.equipmentName ?? "설비 미상", formatDateTime(inc.occurredAt)].join(", ");
}

/** 조사표 제출 의무가 있는가(제출 필요, 기한 경과) */
export function reportRequired(duty: ReportDuty): boolean {
  return duty.status === "REQUIRED" || duty.status === "OVERDUE";
}

/** 조사표 카드 값: "제출 완료 2026-10-12" / "기한 2026-11-02" / "판단 보류" / "제출 대상 아님" */
export function reportValue(duty: ReportDuty): string {
  if (duty.status === "SUBMITTED") return duty.submittedOn ? `제출 완료 ${formatDate(duty.submittedOn)}` : "제출 완료";
  if (duty.dueDate) return `기한 ${formatDate(duty.dueDate)}`;
  if (duty.status === "UNDETERMINED") return "판단 보류";
  return "제출 대상 아님";
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
