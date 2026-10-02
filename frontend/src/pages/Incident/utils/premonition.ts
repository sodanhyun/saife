// premonition.ts — 등록 응답에서 "이 사고는 예고되어 있었다"의 증거를 뽑는 순수 함수.
// 문구는 백엔드 headline을 다시 쓰지 않고, 응답에 실린 사실(등급·평가일·미이행 조치·경과일)로만 조립한다.
import { ACCIDENT_LABEL, RISK_LABEL, type RiskLevel } from "@/types/domain";
import type { IncidentRegisterResponse, PriorHazard, UnfinishedAction } from "@/types/incident";
import { dDayLabel, formatDate } from "@/utils/datetime";

/** 화면에 내보내는 문자열에서 가운뎃점과 대시 구분자를 쉼표로 바꾼다(구두점 규칙). */
export function plain(text: string | null | undefined): string {
  if (!text) return "";
  return text.replace(/\s*[·—–]\s*/g, ", ").replace(/ - /g, ", ");
}

export interface Premonition {
  predicted: boolean;
  /** 사고와 같은 발생형태의 사전 위험요인(등급이 있는 것 우선) */
  hazard: PriorHazard | null;
  /** 사고 시점에 기한이 지나 있던 미이행 조치(가장 오래 지난 것) */
  overdue: UnfinishedAction | null;
  /** 한 문장 증거 */
  proof: string;
}

export function buildPremonition(r: IncidentRegisterResponse): Premonition {
  const { recall } = r;
  const sameAxis = recall.priorHazards.filter((h) => h.sameAxisAsIncident);
  const hazard = sameAxis.find((h) => h.lastRiskLevel) ?? sameAxis[0] ?? null;
  const overdue =
    recall.unfinishedActions.find((a) => a.overdueDays !== null && a.overdueDays > 0) ?? null;

  const axis = r.incident.accidentType ? ACCIDENT_LABEL[r.incident.accidentType] : "같은 형태의";
  let proof: string;
  if (hazard?.lastRiskLevel && hazard.lastAssessedOn) {
    const graded = `${formatDate(hazard.lastAssessedOn)} 위험성평가에서 이 설비의 ${axis} 위험을 ${gradeTo(hazard.lastRiskLevel)} 판정했고`;
    proof = overdue
      ? `${graded}, 감소대책 '${overdue.content}'의 이행 기한은 사고 당시 이미 ${overdue.overdueDays}일 지나 있었습니다.`
      : `${graded}, 같은 형태의 사고가 났습니다.`;
  } else if (hazard) {
    proof = overdue
      ? `${axis} 위험요인이 사고 전부터 등록돼 있었고, 감소대책 '${overdue.content}'의 이행 기한은 이미 ${overdue.overdueDays}일 지나 있었습니다.`
      : `${axis} 위험요인이 사고 전부터 이 설비에 등록돼 있었습니다.`;
  } else {
    proof = plain(recall.headline);
  }
  return { predicted: recall.predicted, hazard, overdue, proof };
}

export interface CascadeCard {
  kind: "RECALL" | "FOLLOW_UP" | "REPORT" | "WORK_PLAN";
  order: number;
  label: string;
  value: string;
  detail: string;
}

/** 연쇄 4단계 카드 — 순서·종류는 백엔드 cascade를 따르고, 큰 값과 한 줄 설명은 응답 사실로 만든다. */
export function buildCascadeCards(r: IncidentRegisterResponse): CascadeCard[] {
  const p = buildPremonition(r);
  const hazards = r.recall.priorHazards.length;
  const actions = r.recall.unfinishedActions.length;
  const plans = r.affectedWorkPlans ?? [];
  const followId = r.followUp.assessmentId;
  const axisRegrade = r.followUp.regraded.find((g) => g.accidentType === r.incident.accidentType) ?? null;

  const followDetail = axisRegrade
    ? axisRegrade.before && axisRegrade.changed
      ? `${axisLabel(axisRegrade.accidentType)} '${RISK_LABEL[axisRegrade.before]}'에서 ${gradeTo(axisRegrade.after)} 재판정, 위험요인 ${r.followUp.regraded.length}건`
      : axisRegrade.before
        ? `${axisLabel(axisRegrade.accidentType)} '${RISK_LABEL[axisRegrade.after]}' 유지 재판정, 위험요인 ${r.followUp.regraded.length}건`
        : `${axisLabel(axisRegrade.accidentType)} 위험요인 신규 등록, '${RISK_LABEL[axisRegrade.after]}'`
    : `위험요인 ${r.followUp.regraded.length}건 재평가`;

  const duty = r.reportDuty;
  const leave = r.incident.leaveDays;

  const cards: Record<CascadeCard["kind"], Omit<CascadeCard, "order" | "kind">> = {
    RECALL: {
      label: "설비 이력 소환",
      value: p.overdue ? `${p.overdue.overdueDays}일 경과` : `${hazards}건`,
      detail: p.overdue
        ? `위험요인 ${hazards}건, 기한 지난 미이행 조치 ${actions}건`
        : `위험요인 ${hazards}건, 미이행 조치 ${actions}건`,
    },
    FOLLOW_UP: {
      label: "수시평가 자동 생성",
      value: followId ? `#${followId}` : "생성 안 됨",
      detail: followDetail,
    },
    REPORT: {
      label: "산업재해조사표 기한",
      value: duty.dueDate ? dDayLabel(duty.daysRemaining) : duty.statusLabel,
      detail: duty.dueDate
        ? `${formatDate(duty.dueDate)}까지 제출, 휴업 ${leave ?? "-"}일`
        : leave === null
          ? "휴업일수 미입력, 판단 보류"
          : `휴업 ${leave}일, 3일 미만`,
    },
    WORK_PLAN: {
      label: "작업계획서 경고 부착",
      value: `${plans.length}건`,
      detail:
        plans.length === 0
          ? "진행 중인 작업계획서 없음"
          : plans.length === 1
            ? `${plans[0].workName} (${formatDate(plans[0].workDate)})`
            : `${plans[0].workName} 외 ${plans.length - 1}건`,
    },
  };

  const order: CascadeCard["kind"][] =
    r.cascade && r.cascade.length > 0
      ? [...r.cascade].sort((a, b) => a.order - b.order).map((s) => s.kind)
      : ["RECALL", "FOLLOW_UP", "REPORT", "WORK_PLAN"];
  return order.map((kind, i) => ({ kind, order: i + 1, ...cards[kind] }));
}

/** 등급 + 조사: '상'으로, '중'으로, '하'로 */
export function gradeTo(level: RiskLevel): string {
  return `'${RISK_LABEL[level]}'${level === "LOW" ? "로" : "으로"}`;
}

function axisLabel(t: PriorHazard["accidentType"]): string {
  return t ? ACCIDENT_LABEL[t] : "발생형태 미상";
}
