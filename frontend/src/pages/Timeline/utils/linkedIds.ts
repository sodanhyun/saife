import type { TimelineEvent } from "@/types/timeline";

/** 포커스된 사건과 연결된 사건 — 선 대신 강조로 표현한다. 양방향. */
export function linkedIds(events: TimelineEvent[], focusId: string | null): Set<string> {
  const out = new Set<string>();
  if (!focusId) return out;
  events.find((e) => e.id === focusId)?.linkedEventIds.forEach((id) => out.add(id));
  events.filter((e) => e.linkedEventIds.includes(focusId)).forEach((e) => out.add(e.id));
  return out;
}
