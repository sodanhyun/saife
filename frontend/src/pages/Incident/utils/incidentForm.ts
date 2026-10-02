import type { IncidentSeverity } from "@/types/domain";
import type { IncidentType, RegisterIncidentRequest } from "@/types/incident";
import { nowLocalInput, toOffsetIso } from "@/utils/datetime";

/**
 * 사고 보고 폼 상태. 설비, 발생형태, 재해 정도는 기본값이 없다(""=아직 고르지 않음).
 * 기본값이 법정 판단을 조용히 정해 버리지 않게 사람이 고르게 한다.
 */
export interface IncidentFormState {
  /** ""=아직 고르지 않음, 그 외=선택한 설비 id */
  equipmentId: string;
  workPlanId: string;
  occurredAt: string;
  victimName: string;
  severity: IncidentSeverity | "";
  leaveDays: string;
  incidentType: IncidentType | "";
  description: string;
  injuryType: string;
  injuryPart: string;
}

export type IncidentFormField = "equipmentId" | "occurredAt" | "incidentType" | "severity" | "leaveDays";
export type IncidentFormErrors = Partial<Record<IncidentFormField, string>>;

export function defaultIncidentForm(): IncidentFormState {
  return {
    equipmentId: "",
    workPlanId: "",
    occurredAt: nowLocalInput(),
    victimName: "",
    severity: "",
    leaveDays: "",
    incidentType: "",
    description: "",
    injuryType: "",
    injuryPart: "",
  };
}

/**
 * 제출 전 검사. 필수: 설비, 발생 일시(지금 이전), 발생형태, 재해 정도. 휴업이면 휴업예상일수(1 이상).
 * @param now datetime-local 형식의 지금(테스트에서 고정한다)
 */
export function validateIncidentForm(f: IncidentFormState, now: string = nowLocalInput()): IncidentFormErrors {
  const errors: IncidentFormErrors = {};
  if (!f.equipmentId) errors.equipmentId = "설비를 선택하십시오";
  if (!f.occurredAt) errors.occurredAt = "발생 일시를 입력하십시오";
  else if (f.occurredAt > now) errors.occurredAt = "지금보다 늦을 수 없습니다";
  if (!f.incidentType) errors.incidentType = "발생형태를 선택하십시오";
  if (!f.severity) errors.severity = "재해 정도를 선택하십시오";
  if (f.severity === "LOST_TIME") {
    const n = f.leaveDays === "" ? NaN : Number(f.leaveDays);
    if (!Number.isInteger(n) || n < 1) errors.leaveDays = "휴업예상일수를 입력하십시오";
  }
  return errors;
}

/** 폼 → 등록 요청. 빈 값은 null이다. 아차사고는 휴업일수와 상해 칸을 보내지 않는다 */
export function toRegisterRequest(f: IncidentFormState): RegisterIncidentRequest {
  const nearMiss = f.severity === "NEAR_MISS";
  return {
    equipmentId: f.equipmentId ? Number(f.equipmentId) : null,
    workPlanId: f.workPlanId ? Number(f.workPlanId) : null,
    occurredAt: toOffsetIso(f.occurredAt),
    victimName: f.victimName || null,
    severity: f.severity || null,
    leaveDays: nearMiss || f.leaveDays === "" ? null : Number(f.leaveDays),
    incidentType: f.incidentType as IncidentType,
    description: f.description.trim() || null,
    injuryType: nearMiss ? null : f.injuryType.trim() || null,
    injuryPart: nearMiss ? null : f.injuryPart.trim() || null,
  };
}
