// formUrl.ts — 법정 서식은 새 탭에서 연다. 인쇄(Ctrl+P)로 PDF가 된다
export const formUrl = {
  assessment: (id: number) => `/form/assessment/${id}`,
  incident: (id: number) => `/form/incident/${id}`,
  workPlan: (id: number) => `/form/work-plan/${id}`,
};
