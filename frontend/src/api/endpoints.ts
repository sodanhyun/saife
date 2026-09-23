// endpoints.ts — 경로 상수. 백엔드 @RequestMapping과 1:1.
export const AGENT_CHAT = "/api/agent/chat";
export const agentTranscript = (conversationId: string) => `/api/agent/${conversationId}/transcript`;
export const VISION_ANALYZE = "/api/vision/analyze";
export const EQUIPMENT = "/api/equipment";
export const WORK_PLAN = "/api/work-plan";
export const INCIDENT = "/api/incident";
export const equipmentTimeline = (equipmentId: number) => `/api/dashboard/equipment/${equipmentId}/timeline`;
