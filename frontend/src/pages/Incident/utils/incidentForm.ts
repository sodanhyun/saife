import type { AccidentType, IncidentSeverity } from "@/types/domain";
import type { RegisterIncidentRequest } from "@/types/incident";
import { nowLocalInput, toOffsetIso } from "@/utils/datetime";

export interface IncidentFormState {
  /** null=아직 사용자가 고르지 않음(기본 설비로 채워짐), ""=명시적으로 "(설비 미상)" 선택, 그 외=선택한 설비 id */
  equipmentId: string | null;
  workPlanId: string;
  occurredAt: string;
  victimName: string;
  severity: IncidentSeverity;
  leaveDays: string;
  accidentType: AccidentType;
  description: string;
}

export function defaultIncidentForm(): IncidentFormState {
  return {
    equipmentId: null,
    workPlanId: "",
    occurredAt: nowLocalInput(),
    victimName: "",
    severity: "LOST_TIME",
    leaveDays: "14",
    accidentType: "FALL",
    description: "",
  };
}

/** 폼 → 등록 요청. 빈 값은 null이다 — 서버가 판단 보류로 처리한다. */
export function toRegisterRequest(f: IncidentFormState): RegisterIncidentRequest {
  return {
    equipmentId: f.equipmentId ? Number(f.equipmentId) : null,
    workPlanId: f.workPlanId ? Number(f.workPlanId) : null,
    occurredAt: toOffsetIso(f.occurredAt),
    victimName: f.victimName || null,
    severity: f.severity,
    leaveDays: f.leaveDays === "" ? null : Number(f.leaveDays),
    accidentType: f.accidentType,
    description: f.description || null,
  };
}
