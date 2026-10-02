// storyModel.ts — 서버가 준 사건 목록을 "한 설비의 이야기"로 읽히게 다듬는다.
// 연결 관계는 서버가 계산한 linkedEventIds만 쓴다. 화면이 추가로 만드는 건 두 가지뿐이다:
//  1) 등급 변화(직전 평가 등급과 비교) 2) 사고 전 같은 유형 위험을 평가한 기록(사전 경고).
// 둘 다 같은 응답 안의 사실을 비교만 한다. 없는 연결을 만들지 않는다.
import { plainText, monthDay } from "@/utils/plainText";
import { ACCIDENT_LABEL, RISK_LABEL, type RiskLevel } from "@/types/domain";
import type { TimelineEvent } from "@/types/timeline";

export type RelationTone = "high" | "neutral" | "progress";

export interface StoryRelation {
  text: string;
  tone: RelationTone;
  /** 강조 연결 대상(포커스 시 함께 밝힌다) */
  targetId: string;
}

export interface GradeChange {
  from: RiskLevel;
  to: RiskLevel;
  /** 등급이 내려갔으면 true */
  improved: boolean;
}

export interface StoryEvent {
  ev: TimelineEvent;
  detail: string;
  trace: string | null;
  gradeChange: GradeChange | null;
  relations: StoryRelation[];
}

const RANK: Record<RiskLevel, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 };

/** ruleTrace가 별도 필드로 오지 않는 옛 응답("위험요인 1건 평가 · 근거")도 같은 모양으로 읽는다 */
function splitTrace(ev: TimelineEvent): { detail: string; trace: string | null } {
  if (ev.ruleTrace !== undefined && ev.ruleTrace !== null) {
    return { detail: plainText(ev.detail), trace: plainText(ev.ruleTrace) };
  }
  if (ev.type === "ASSESSMENT") {
    const idx = ev.detail.indexOf(" · ");
    if (idx > 0) {
      return { detail: plainText(ev.detail.slice(0, idx)), trace: plainText(ev.detail.slice(idx + 3)) };
    }
  }
  return { detail: plainText(ev.detail), trace: null };
}

function relationOf(ev: TimelineEvent, target: TimelineEvent): StoryRelation {
  const when = monthDay(target.at);
  const name = plainText(target.title);
  const base = { targetId: target.id };
  if (ev.type === "ACTION" && target.type === "ASSESSMENT") {
    return { ...base, tone: "neutral", text: `${when} ${name}에서 나온 감소대책` };
  }
  if (ev.type === "INCIDENT" && target.type === "ACTION") {
    return { ...base, tone: "high", text: `사고 당시 기한이 지난 미이행 조치: ${name}` };
  }
  if (ev.type === "INCIDENT" && target.type === "WORK_PLAN") {
    return { ...base, tone: "neutral", text: `연결된 작업계획서: ${when} ${name}` };
  }
  if (ev.type === "INCIDENT" && target.type === "ASSESSMENT") {
    return { ...base, tone: "progress", text: `이 사고로 ${when} 수시 위험성평가가 생성됨` };
  }
  if (ev.type === "ASSESSMENT" && target.type === "INCIDENT") {
    const axis = target.accidentType ? `${ACCIDENT_LABEL[target.accidentType]} ` : "";
    return { ...base, tone: "progress", text: `${when} ${axis}사고가 촉발한 수시평가` };
  }
  if (ev.type === "WORK_PLAN" && target.type === "ACTION") {
    return { ...base, tone: "high", text: `브리핑에서 경고한 미이행 조치: ${name}` };
  }
  return { ...base, tone: "neutral", text: `연결: ${when} ${name}` };
}

/** 사고 전, 같은 재해 유형을 '중' 이상으로 평가한 가장 최근 기록. 이 사고가 예고되어 있었다는 근거 */
function priorWarning(events: TimelineEvent[], index: number): StoryRelation | null {
  const incident = events[index];
  if (incident.type !== "INCIDENT" || !incident.accidentType) return null;
  for (let i = index - 1; i >= 0; i -= 1) {
    const e = events[i];
    if (e.type !== "ASSESSMENT" || e.accidentType !== incident.accidentType || !e.riskLevel) continue;
    if (e.linkedEventIds.includes(incident.id)) continue; // 이 사고가 만든 평가는 사전 경고가 아니다
    if (RANK[e.riskLevel] < RANK.MEDIUM) continue;
    return {
      targetId: e.id,
      tone: "high",
      text: `사고 전 ${monthDay(e.at)} ${plainText(e.title)}에서 ${ACCIDENT_LABEL[incident.accidentType]} 위험을 '${RISK_LABEL[e.riskLevel]}'${e.riskLevel === "LOW" ? "로" : "으로"} 이미 평가`,
    };
  }
  return null;
}

export function buildStory(events: TimelineEvent[]): StoryEvent[] {
  const byId = new Map(events.map((e) => [e.id, e]));
  let prevLevel: RiskLevel | null = null;
  return events.map((ev, index) => {
    let gradeChange: GradeChange | null = null;
    if (ev.type === "ASSESSMENT" && ev.riskLevel) {
      if (prevLevel && prevLevel !== ev.riskLevel) {
        gradeChange = { from: prevLevel, to: ev.riskLevel, improved: RANK[ev.riskLevel] < RANK[prevLevel] };
      }
      prevLevel = ev.riskLevel;
    }
    const relations: StoryRelation[] = [];
    const warning = priorWarning(events, index);
    if (warning) relations.push(warning);
    ev.linkedEventIds.forEach((id) => {
      const target = byId.get(id);
      if (target) relations.push(relationOf(ev, target));
    });
    return { ev, ...splitTrace(ev), gradeChange, relations };
  });
}

/** 포커스된 사건과 이어진 사건(서버 연결 + 사전 경고). 양방향 */
export function storyLinkedIds(story: StoryEvent[], focusId: string | null): Set<string> {
  const out = new Set<string>();
  if (!focusId) return out;
  story.forEach((s) => {
    const targets = s.relations.map((r) => r.targetId);
    if (s.ev.id === focusId) targets.forEach((id) => out.add(id));
    else if (targets.includes(focusId)) out.add(s.ev.id);
  });
  return out;
}
