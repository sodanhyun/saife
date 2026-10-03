// endpoints.ts — 경로 상수. 백엔드 @RequestMapping과 1:1.
export const AGENT_CHAT = "/api/agent/chat";
export const agentTranscript = (conversationId: string) => `/api/agent/${conversationId}/transcript`;
export const VISION_ANALYZE = "/api/vision/analyze";
export const EQUIPMENT = "/api/equipment";
export const WORK_PLAN = "/api/work-plan";
export const INCIDENT = "/api/incident";
export const ACTION = "/api/action";
export const equipmentTimeline = (equipmentId: number) => `/api/dashboard/equipment/${equipmentId}/timeline`;
export const EQUIPMENT_CARDS = "/api/dashboard/equipment/cards";
export const equipmentRecall = (equipmentId: number) => `/api/dashboard/equipment/${equipmentId}/recall`;
// Phase 3(오늘 할 일)이 쓴다 — 라우트·메뉴 정리 단계에서 상수만 먼저 둔다.
export const TODAY = "/api/dashboard/today";
// 프론트 전역 상태 줄(데모 모드·근거 수·회로 상태)용. 타입은 B2 계획이 붙인다.
export const SYSTEM_STATUS = "/api/system/status";
