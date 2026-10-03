// storyModel.ts — 서버가 준 사건 목록을 한 설비의 이력으로 읽히게 다듬는다.
// 연결 관계는 서버가 계산한 linkedEventIds만 쓴다. 화면이 추가로 만드는 건 두 가지뿐이다:
//  1) 등급 변화(직전 평가 등급과 비교) 2) 사고 이전 가장 최근 평가(같은 발생형태).
// 둘 다 같은 응답 안의 사실을 비교만 한다. 없는 연결을 만들지 않는다.
import { plainText, monthDay } from "@/utils/plainText";
import { ACCIDENT_LABEL, RISK_LABEL, type RiskLevel } from "@/types/domain";
import type { TimelineEvent } from "@/types/timeline";

export interface StoryRelation {
  text: string;
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

/**
 * 등급 근거 문장을 짧은 평문으로. 옛 시드의 "A + B → '상'" 꼴에서 결론 화살표와 따옴표 등급을 걷어낸다
 * (등급은 배지가 말한다).
 */
export function cleanTrace(text: string | null | undefined): string {
  return plainText(text)
    .replace(/\s*(→|->)\s*'?[상중하]'?\s*$/u, "")
    .replace(/\s*'[상중하]'\s*유지\s*$/u, " 등급 유지")
    .replace(/\s*'[상중하]'\s*$/u, "")
    .replace(/\s+\+\s+/g, ", ")
    .replace(/\s*→\s*/g, ", ")
    .replace(/[,\s]+$/u, "")
    .trim();
}

/** "상시 위험성평가" 같은 옛 제목도 "상시평가"로 읽는다 */
export function shortTitle(ev: TimelineEvent): string {
  const t = plainText(ev.title);
  return ev.type === "ASSESSMENT" ? t.replace(/\s*위험성평가$/, "평가") : t;
}

/** ruleTrace가 별도 필드로 오지 않는 옛 응답("위험요인 1건 평가 · 근거")도 같은 모양으로 읽는다 */
function splitTrace(ev: TimelineEvent): { detail: string; trace: string | null } {
  if (ev.ruleTrace !== undefined && ev.ruleTrace !== null) {
    return { detail: plainText(ev.detail), trace: cleanTrace(ev.ruleTrace) };
  }
  if (ev.type === "ASSESSMENT") {
    const idx = ev.detail.indexOf(" · ");
    if (idx > 0) {
      return { detail: plainText(ev.detail.slice(0, idx)), trace: cleanTrace(ev.detail.slice(idx + 3)) };
    }
  }
  return { detail: plainText(ev.detail), trace: null };
}

function relationOf(ev: TimelineEvent, target: TimelineEvent): StoryRelation {
  const when = monthDay(target.at);
  const name = shortTitle(target);
  const base = { targetId: target.id };
  if (ev.type === "ACTION" && target.type === "ASSESSMENT") return { ...base, text: `출처: ${when} ${name}` };
  if (ev.type === "INCIDENT" && target.type === "ACTION") return { ...base, text: `사고 시점 미이행: ${name}` };
  if (ev.type === "INCIDENT" && target.type === "WORK_PLAN") return { ...base, text: `작업 전 점검: ${name}` };
  if (ev.type === "INCIDENT" && target.type === "ASSESSMENT") return { ...base, text: `${name} ${when}` };
  if (ev.type === "ASSESSMENT" && target.type === "INCIDENT") {
    const axis = target.accidentType ? `${ACCIDENT_LABEL[target.accidentType]} ` : "";
    return { ...base, text: `${when} ${axis}사고 후속` };
  }
  if (ev.type === "WORK_PLAN" && target.type === "ACTION") return { ...base, text: `승인 당시 미이행 조치: ${name}` };
  return { ...base, text: `${when} ${name}` };
}

/** 평가가 사고 전에 기록됐는가. 사고일 이전이거나, 같은 날이면 사고 시각보다 먼저 만들어진 것 */
function before(assessment: TimelineEvent, incident: TimelineEvent): boolean {
  if (assessment.at !== incident.at) return assessment.at < incident.at;
  if (!assessment.occurredAt || !incident.occurredAt) return false;
  return new Date(assessment.occurredAt).getTime() < new Date(incident.occurredAt).getTime();
}

/**
 * 사고 이전 가장 최근 평가(같은 발생형태). 사고 화면의 설비 이력 소환과 같은 규칙이다:
 * 그 사고가 만든 수시평가는 같은 날이어도 제외하고, 사고 시점 이전 기록만 본다.
 */
export function priorAssessment(events: TimelineEvent[], incident: TimelineEvent): TimelineEvent | null {
  if (incident.type !== "INCIDENT") return null;
  const candidates = events.filter((e) => e.type === "ASSESSMENT" && e.riskLevel
    && (!incident.accidentType || e.accidentType === incident.accidentType)
    && !e.linkedEventIds.includes(incident.id)
    && !incident.linkedEventIds.includes(e.id)
    && before(e, incident));
  return candidates.at(-1) ?? null;
}

function priorWarning(events: TimelineEvent[], incident: TimelineEvent): StoryRelation | null {
  const prior = priorAssessment(events, incident);
  if (!prior?.riskLevel) return null;
  const axis = prior.accidentType ? `${ACCIDENT_LABEL[prior.accidentType]} ` : "";
  return { targetId: prior.id, text: `사고 전 평가: ${axis}${RISK_LABEL[prior.riskLevel]} (${monthDay(prior.at)})` };
}

export function buildStory(events: TimelineEvent[]): StoryEvent[] {
  const byId = new Map(events.map((e) => [e.id, e]));
  let prevLevel: RiskLevel | null = null;
  return events.map((ev) => {
    let gradeChange: GradeChange | null = null;
    if (ev.type === "ASSESSMENT" && ev.riskLevel) {
      if (prevLevel && prevLevel !== ev.riskLevel) {
        gradeChange = { from: prevLevel, to: ev.riskLevel, improved: RANK[ev.riskLevel] < RANK[prevLevel] };
      }
      prevLevel = ev.riskLevel;
    }
    const relations: StoryRelation[] = [];
    const warning = priorWarning(events, ev);
    if (warning) relations.push(warning);
    ev.linkedEventIds.forEach((id) => {
      const target = byId.get(id);
      if (target) relations.push(relationOf(ev, target));
    });
    return { ev, ...splitTrace(ev), gradeChange, relations };
  });
}

/** 등급 이력. 같은 날 평가가 여럿이면 그날의 마지막(인과 순서상 최종) 등급 하나만 남긴다 */
export function gradeHistory(story: StoryEvent[]): StoryEvent[] {
  const graded = story.filter((s) => s.ev.type === "ASSESSMENT" && s.ev.riskLevel);
  return graded.filter((s, i) => graded[i + 1]?.ev.at !== s.ev.at);
}

/** 포커스된 사건과 이어진 사건(서버 연결 + 사고 전 평가). 양방향 */
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

/** 등급 이력 칩 앞의 연도 표기. 첫 칩이 올해가 아니거나, 앞 칩과 연도가 바뀌면 연도를 붙인다 */
export function yearMarks(dates: string[], thisYear: string): (string | null)[] {
  return dates.map((d, i) => {
    const year = d.slice(0, 4);
    if (i === 0) return year !== thisYear ? year : null;
    return dates[i - 1].slice(0, 4) !== year ? year : null;
  });
}
