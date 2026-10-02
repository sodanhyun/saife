// approval.ts — 승인 흐름의 순수 규칙. 화면이 백엔드(WorkPlanService)와 같은 판단을 미리 보여준다.

/** 잠정조치가 이 말을 담으면 그 작업을 하지 않는다는 뜻이다. 승인이 아니라 작업 보류다 */
const STOP_WORK = /작업\s*(금지|중지|중단)/;

export function isStopWork(interim: string): boolean {
  return STOP_WORK.test(interim);
}

/** 작업 보류를 푸는 수시평가 화면. 평가 ID를 모르면 사고 보고 화면으로 간다 */
export function assessmentHref(assessmentId: number | null | undefined): string {
  return assessmentId != null ? `/assessment/${assessmentId}` : "/incident";
}
