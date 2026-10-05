# 대표 Test Case와 재현 위치

제출 문서(개발완료보고서 4.2, 기술설명서)의 TC 번호와 저장소의 자동 테스트, 스모크 스크립트 대응표.
자동 테스트: `cd backend && ./gradlew test`, `cd frontend && npm test`. 스모크: `docs/experiments/` (실행 중 스택 대상).

| TC | 유형 | 자동 테스트 | 스모크, 재현 |
|---|---|---|---|
| TC1 | 정상 입력 (도구 6종 순차 호출, 점검표 등록) | `AgentServiceTest`, `WorkPlanToolsTest`, `DemoConversationScriptTest` | `uc3_smoke.py` |
| TC2 | 정보 부족 (INCOMPLETE, 빠진 값만 질문) | `AgentServiceTest`, `WorkPlanToolsTest` | `slot_filling_eval2.py` (변형별 9건) |
| TC3 | 잘못된 답 (칸 불일치 미저장, 재질문) | `WorkPlanToolsTest`, `AgentServiceTranscriptTest` | `slot_filling_eval.py` |
| TC4 | 기준 판정 (제42조제4항, 3.5m 초과, 최상부 디딤대) | `RiskRuleEngineTest`, `PlanRulesTest`, `HazardAnalysisToolsTest` | `uc3_smoke.py` |
| TC5 | 승인 조건 (상 등급 미이행 시 잠정조치 필수) | `WorkPlanServiceApprovalTest` | 화면: 검토 및 승인 |
| TC6 | API 키 없음 (데모 모드 기동) | `DemoConversationScriptTest`, `SystemStatusControllerTest` | `GEMINI_API_KEY=` 로 `docker compose up` 후 `/api/system/status` |
| TC7 | 잘못된 요청 (없는 ID, 외부 URL, 잘못된 본문) | `GlobalExceptionHandlerTest`, `MediaControllerTest` | `edge_cases.py` (23건) |
| TC8 | 사고 연쇄 (수시평가, 작업 보류, 확정 후 재승인) | `IncidentServiceTest`, `FollowUpConfirmServiceTest`, `EquipmentHistoryRecallerTest` | `uc2_smoke.py` |
| TC9 | 순회점검 (반영과 제외, 개선대책, 평가표) | `VisionAssessmentServiceDedupTest`, `ActionServiceTest`, `AssessmentFormResultTest` | `uc1_smoke.py` |
| TC10 | 이행 확인 (증빙 없음, 확인자 없음, 개선 후 상 거부, 사진 대조, 같은 위험요인 대책 종료) | `ActionServiceTest`, `ActionEvidenceCheckerTest`, `ActionListServiceTest`, `EquipmentTimelineServiceTest` | 화면: 개선대책, 이행 확인 |
| TC11 | 사고 후 승인 (수시평가 확정 전 같은 설비 새 점검표 승인 거부) | `WorkPlanServiceApprovalTest` | 화면: 작업 전 점검 검토 및 승인 |

유사 재해사례의 원인과 대책 발췌: `CaseDigestTest` (원문 서식 3종, 가운뎃점 변환, 원인 절 없음).
