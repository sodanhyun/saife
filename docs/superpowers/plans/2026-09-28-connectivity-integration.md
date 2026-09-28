# C — 연결성 개선 실행 순서와 근거 계층 통합 지점

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 연결성 개선(설비 홈·회상 카드·사고 연쇄·오늘 할 일·시드 V8)을 `docs/SAIFE_연결성_개선_프롬프트.md`의 Phase 0~5대로 실행하되, 근거 계층 계획(A1~A4, B1~B2)과 충돌 없이 한 저장소에서 순서대로 진행한다. 이 문서는 **연결성 프롬프트를 대체하지 않는다.** 그 문서의 각 Phase 지시가 구현 명세이고, 여기에는 두 작업이 만나는 지점의 결정과 실행 순서만 적는다.

**Architecture:** 두 작업은 같은 SSE 규약·같은 카드 원칙("백엔드가 만들고, 모델 문장과 무관하게 뜬다")을 쓴다. 연결성은 설비 축, 근거는 출처 축이다. 화면에서 회상 카드와 근거 카드는 분리된 컴포넌트다.

**Spec:** `docs/superpowers/specs/2026-09-28-evidence-rag-and-connectivity-design.md` §9, §10, §11 · `docs/SAIFE_연결성_개선_설계서.docx`

## Global Constraints

- 연결성 프롬프트 §2 "절대 규칙" 전부(데모 모드에서 전부 동작, 기존 4 라우트 유지, 자동 리다이렉트 금지, 새 라이브러리 금지 — 근거 계층의 PDFBox 1개는 스펙 승인 예외).
- 마이그레이션 번호: **V8 = 연결성**(`work_plan.warning_note` + 고소작업대 이야기), **V9 = 근거**(A1). 두 계획을 병렬로 만들 때 번호가 겹치지 않도록 이 순서를 지킨다. V8을 아직 안 만든 상태에서 V9가 먼저 적용되면 Flyway `outOfOrder`가 필요해지므로 **A1 Task 1 전에 V8 파일을 먼저 커밋한다**(내용은 연결성 Phase 2·4에서 채우되 빈 파일이 아니라 `warning_note` 컬럼 추가만 먼저 넣는다).
- 각 Phase 끝: `./gradlew test` · `npm run lint && npm run build && npx vitest run` · 해당 스모크 · 커밋.

## Review Focus

1. `ai.recall`과 `ai.evidence`가 한 턴에 같이 오면 순서는 `ai.tool.start` → `ai.recall`(도구 안) → `ai.tool.done` → … → `ai.evidence` → `ai.token` → `ai.done`이어야 하고 프론트 seq 검사에 걸리지 않아야 한다. → Task 3 `recall_smoke.py`에 이벤트 순서 단언 추가.
2. `/work-plan?equipmentId=`로 들어온 진입 회상 카드와 대화 중 `ai.recall` 카드가 둘 다 뜰 때 같은 설비면 하나로 합쳐져야 한다(중복 카드 금지). → Task 3 `RecallCard.test.같은_설비_중복_없음`.
3. 사고 연쇄 1단계 RECALL 카드에 유사 사례 그리드가 들어갔을 때 4스텝 순차 등장 애니메이션이 그리드 높이 변화로 깨지지 않아야 한다(스텝은 앵커 스크롤만). → Task 4 수동 확인 항목.
4. 시드 V8의 고소작업대 이야기가 근거 원장과 무관해야 한다(work_plan_evidence는 비어 있어도 상세 모달이 정상). → Task 5 `WorkPlanDetailModal.test.근거_없음`.
5. 데모 모드(키 없음)에서 홈·회상·연쇄·오늘 할 일·근거 카드가 전부 뜬다. → Task 6 클린 기동 체크리스트.

---

### Task 0: Phase 0 기준선 — 연결성 B1~B5 + 근거 B9~B11 한 스크립트

**Files:**
- Create: `docs/experiments/baseline_connectivity.py`(연결성 프롬프트 Phase 0 §3의 B1~B5 그대로) — 여기에 아래 세 측정을 추가
- Create: `docs/experiments/baseline_connectivity_result.json`

- [ ] **Step 1:** 연결성 프롬프트 Phase 0의 스크립트를 작성한다(B1~B5).
- [ ] **Step 2:** 근거 측정 3개를 같은 스크립트에 추가:
```python
# B9 근거 첨부 턴 수: 데모 모드 첫 턴 10회 중 ai.evidence 이벤트 수 (현재 0/10)
# B10 응답 내 URL 유효율: ai.token 텍스트에서 http(s) URL을 뽑아 HEAD 200 비율 (현재 URL 자체가 없어 측정 불가 → "n/a")
# B11 유사 사례 사진 비율: searchCases 결과 텍스트에 "[사진]" 표기 비율 (현재 0)
```
- [ ] **Step 3:** 실행 결과를 JSON으로 저장하고 커밋: `test: 연결성·근거 기준선 측정 스크립트와 결과`

---

### Task 1: V8 선점 + Phase 1 (설비 홈·상세)

**Files:** 연결성 프롬프트 Phase 1의 파일 전부 + `backend/src/main/resources/db/migration/V8__connectivity.sql`

- [ ] **Step 1: V8 파일을 먼저 만든다** (A1이 V9를 쓰기 전에)
```sql
-- 연결성 개선(설계서 §5.2, §7). 이야기 시드는 Phase 4에서 이 파일에 추가하지 않고 V8은 컬럼만, 시드는 V10으로 간다(아래 참고)
ALTER TABLE work_plan ADD COLUMN warning_note TEXT;
COMMENT ON COLUMN work_plan.warning_note IS '사고 연쇄가 붙인 경고. "이 설비에서 {날짜} {발생형태} 사고 — 재개 전 수시평가 #N 확인"';
```
**결정:** 연결성 프롬프트는 V8에 시드까지 넣으라고 했지만, V8을 먼저 커밋해야 V9(근거)와 충돌이 없다. 시드 SQL은 나중에 만들어지므로 **고소작업대 이야기 시드는 V10**으로 번호를 옮긴다(프롬프트 Phase 4의 파일명을 `V10__seed_story_aerial_platform.sql`로 읽는다). 이유를 `.claude/rules/deployment.md` 마이그레이션 표에 한 줄 적는다.
- [ ] **Step 2:** 연결성 프롬프트 Phase 1-1 ~ 1-7을 그대로 실행한다(cards/recall API, 라우트·메뉴, 홈·상세, 승격, 진입 컨텍스트, 돌아오기 링크, `home_smoke.py`).
- [ ] **Step 3:** 커밋 3건(프롬프트 예시 메시지대로).

---

### Task 2: Phase 2-1 회상 카드 — `ai.recall`을 근거 이벤트와 같은 자리에서

**Files:** 연결성 프롬프트 Phase 2-1의 파일 전부

- [ ] **Step 1:** 백엔드 `ai.recall` 발행은 프롬프트대로 `LocationEquipmentTools.findLocationEquipment` 매칭 성공 분기에서 `sseService.send(sessionId, SseEvent.of("ai.recall", conversationId, conversationId, recallView))`. B1의 `TurnFinisher`와 무관하다(도구 레벨).
- [ ] **Step 2:** `.claude/rules/sse-streaming.md` 이벤트 표에 `ai.recall`을 B1이 추가한 `ai.evidence` 줄 바로 위에 넣는다:
```
| `ai.recall` | findLocationEquipment 매칭 성공 시 도구 레벨 | `RecallView`(+knownSlots). 모델 문장과 무관하게 회상 카드가 뜬다 |
```
- [ ] **Step 3:** 프론트 `useAgentStream`에 `"ai.recall": RecallPayload` 핸들러 → `recall` 상태. **진입 회상(`?equipmentId=`)과 대화 중 회상이 같은 `equipmentId`면 나중 것으로 교체**(중복 카드 금지):
```ts
"ai.recall": (p, env) => { if (!accept(env)) return; setRecall((prev) => (prev && prev.equipmentId === p.equipmentId ? p : p)); },
```
(같은 설비면 최신으로 교체, 다른 설비면 교체 — 결국 항상 마지막 회상 하나만 보관한다. 카드는 한 장이다.)
- [ ] **Step 4:** `RecallCard.test`에 "같은 설비 중복 없음"(진입 회상 후 `ai.recall` 수신 → 카드 1장) 케이스 추가. `recall_smoke.py`에 이벤트 순서 단언(`ai.recall`이 `ai.tool.done`보다 먼저, `ai.evidence`가 `ai.token`보다 먼저) 추가.
- [ ] **Step 5:** 시스템 프롬프트: B1 Task 2가 넣은 `[근거 인용]` 절과 연결성 2-1의 "첫 문장은 회상" 규칙이 **같은 템플릿**에 공존한다. 순서: `[원칙]` → `[진행 순서]`(2번 회상 규칙 반영) → `[근거 인용]` → `[오늘 날짜]`.
- [ ] **Step 6:** `DemoConversationScript` 첫 토큰이 회상 문장으로 시작하고(연결성), 유사 사례 문장 뒤에 `[#n]`(B1 Self-Review 마지막 항목)이 붙는지 확인. 커밋.

---

### Task 3: Phase 2-2 사고 연쇄 — RECALL 스텝 안에 유사 사례

**Files:** 연결성 프롬프트 Phase 2-2의 파일 전부 + B1 Task 5(`similarCases`), B2 Task 4(`IncidentResult`)

- [ ] **Step 1:** `IncidentDtos.RegisterResponse`의 최종 필드 순서를 확정한다(두 계획이 같은 레코드를 늘린다):
```java
public record RegisterResponse(IncidentSummary incident, ReportDuty reportDuty, RecallView recall, FollowUpView followUp, DraftView draft,
                               List<CascadeStep> cascade, List<AffectedWorkPlan> affectedWorkPlans, List<Evidence> evidence) {}
```
`RecallView`의 마지막 필드는 `List<Evidence> similarCases`. `types/incident.ts`도 같은 순서.
- [ ] **Step 2:** `CascadeStep` 1단계 RECALL의 `detail`에 유사 사례 수를 덧붙인다: `"… · 동종 유사 사고 3건(사진 2)"`. 계산은 `IncidentService`가 `collected.similarCases()`로.
- [ ] **Step 3:** `CascadeList`의 스텝 앵커: 1단계는 기존 소환 카드(`id="cascade-recall"`), 그 카드 안에 B2가 넣은 `EvidenceGrid`가 있다. 스텝 등장 애니메이션은 스텝 목록에만 적용하고 카드 높이에는 관여하지 않는다(수동 확인: 4스텝 순차 등장 중 레이아웃 점프 없음).
- [ ] **Step 4:** `uc2_smoke.py` 확장에 `recall.similarCases ≥ 1`, `evidence 번호 유일`, `cascade[0].detail`에 "유사 사고" 포함 단언 추가. 커밋.

---

### Task 4: Phase 3 오늘 할 일 + Phase 4 시드 이야기(V10)

**Files:** 연결성 프롬프트 Phase 3·4의 파일 전부(마이그레이션 파일명만 `V10__seed_story_aerial_platform.sql`)

- [ ] **Step 1:** Phase 3(today API·TodayInbox·규칙 7종 테스트·`today_smoke.py`)를 프롬프트대로.
- [ ] **Step 2:** Phase 4 시드를 **V10**으로 작성. `created_at`을 사건 순서대로 명시(프롬프트 경고 그대로). `reset_demo_data.py`가 V10 데이터도 복원하도록 `work_plan`·`incident`·`assessment` 삭제 조건을 "id > 시드 최대 id"로 바꾼다.
- [ ] **Step 3:** 고소작업대 이야기의 작업계획서(id 시드값)는 `work_plan_evidence`가 없다. `WorkPlanDetailModal.test`에 `evidence: []`일 때 "참고 자료" 섹션이 렌더되지 않는 케이스를 추가(B2의 `EvidenceGrid`는 `items.length === 0`이면 null).
- [ ] **Step 4:** 시드 스냅샷 테스트(오늘 할 일 기대값)를 고정. 커밋 2건.

---

### Task 5: 실행 순서 게이트 (두 작업의 인터리브)

아래 순서를 지킨다. 각 줄은 앞 줄이 커밋된 뒤 시작한다. 같은 줄의 두 항목은 병렬 가능(백엔드/프론트 또는 서로 다른 패키지).

| 순서 | 항목 | 병렬 |
|---|---|---|
| 1 | C Task 0(기준선) | — |
| 2 | C Task 1 Step 1(V8) → A1 전체 | 연결성 Phase 1 프론트(1-3~1-6) |
| 3 | A2 → A3 | 연결성 Phase 1 백엔드(1-1, 1-2) |
| 4 | A4(Task 1~5) → A4 Task 6 시드 생성(키 필요, 20~40분 대기) | C Task 2(회상) |
| 5 | B1 Task 1~4 | B2 Task 1~2 |
| 6 | B1 Task 5 + C Task 3(연쇄) | B2 Task 3 |
| 7 | C Task 4(오늘 할 일·V10) | B2 Task 4 |
| 8 | B1 Task 6(UC1) — 절단선 ① 대상 | B2 Task 5(스모크) |
| 9 | C Task 6(클린 기동·시연 동선·문서) | — |

**절단 판단 시점:** 10/3 저녁에 순서 7이 끝나지 않았으면 순서 8의 UC1 근거를 뺀다(스펙 §10 절단선 ①). 순서 4의 GUIDE 인덱스가 10/1 안에 안 끝나면 절단선 ⑤(enrichment 생략) → ③(표지 청크만).

---

### Task 6: Phase 5 마무리 — 클린 기동·시연 동선·문서

**Files:** `README.md`, `CLAUDE.md`, `TODOS.md`, `.claude/rules/{ai-tool-calling,sse-streaming,public-api-integration,deployment}.md`, `docs/qa-report-2026100X.md`, `docs/connectivity-report-2026100X.md`

- [ ] **Step 1: 키 없이 클린 기동.** `docker compose -f docker-compose.dev.yml down -v` → `.env` 없이 `docker compose up -d --build` → `/actuator/health` UP, `/api/system/status`에 `demoMode=true`, `evidenceChunkCount>15000`. 기동 시간 기록(M7 ≤ 30s 목표 — 시드 적재가 늘어 22s를 넘을 수 있다. 넘으면 시드 적재를 `@Async`로 돌리고 상태 줄에 "근거 적재 중 n/N"을 띄운다).
- [ ] **Step 2: 시연 동선 완주 3회**(연결성 설계서 §8의 8단계) + 각 단계 캡처 `docs/demo-run-YYYYMMDD/`. 3단계 회상 카드, 4단계 트레이스 6줄 + 근거 카드(사진 1장 이상), 6단계 연쇄 4스텝 + 유사 사례 사진, 8단계 타임라인.
- [ ] **Step 3: 규칙 문서 갱신.**
  - `ai-tool-calling.md`: 검색 파라미터 표(`SearchPolicy` 상수명과 값), "parent는 임베딩하지 않는다", 리랭크 옵션 필수 항목, `[#n]` 인용 규칙.
  - `sse-streaming.md`: 재개 엔드포인트·10분 타임아웃 절 삭제(폐기된 설계), `ai.recall`·`ai.evidence` 표 확인.
  - `public-api-integration.md`: 1070 폐기 확정, 1040 `image_url` 보존, MSDS 2단 호출 구현 위치, 법제처 MST 크롤.
  - `deployment.md`: V8/V9/V10 번호 사유, 시드 5개 파일과 크기, 프리페치 절차, `SAIFE_MEDIA_DIR`.
- [ ] **Step 4: `README.md` 화면 표**(홈·설비 상세·동사 라벨·근거 카드 한 줄), `CLAUDE.md` 도메인 모듈 표에 `evidence/` 패키지와 `dashboard` cards/recall/today 추가, `TODOS.md`를 실제 상태로 갱신.
- [ ] **Step 5: 보고서.** `connectivity-report`에 M1~M8 + M9~M12 Before/After(Task 0 결과와 `baseline_connectivity.py` 재실행), "결정 사항"(V10 번호 이동, parent 미임베딩, 재해사례 첨부 포기, 라이브 우선 정책), "변경하지 않은 것", "남은 리스크", 별지2용 "신규개발분 요약"(파일·엔드포인트·컴포넌트 목록 — A1~B2 커밋 로그에서 추출).
- [ ] **Step 6: 코드 프리즈 커밋 + 1차 영상.**

---

## Self-Review

- 스펙 §9 통합 규칙 7개 전부 대응(SSE 2종 — Task 2, 화면 순서 — Task 2·3, cascade 안 유사 사례 — Task 3, V8/V9/V10 — Task 1·4, 진입 컨텍스트 — 연결성 Phase 1, 데모 모드 — Task 6, 돌아오기 링크 — 연결성 Phase 1).
- 스펙 §10 Phase 표는 Task 5의 인터리브 표로 구체화. 절단선 판단 시점 명시.
- 스펙 §11 지표 M9~M12는 Task 0(Before)과 Task 6(After)에서 같은 스크립트로 측정.
- 연결성 프롬프트와 다른 결정은 하나(시드 V8→V10)이며 이유와 반영 위치를 적었다.
