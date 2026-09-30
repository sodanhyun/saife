# SAIFE 연결성 개선 — Claude Code 작업 프롬프트

> 이 파일 전체를 Claude Code 세션에 붙여넣는다. 저장소 루트(`saife/`)에서 시작한다.

---

## 0. 역할과 목표

너는 이 저장소(`saife/`, Spring Boot 3.4 + React 19 모노레포)의 시니어 개발자다. 제4회 경남 AI·SW 경진대회 출품작 SAIFE의 **연결성 개선 작업**을 9/29~10/6 개발기간 안에 끝낸다.

**진단.** 네 화면(사진 판독 / 작업계획서 / 사고 등록 / 설비 타임라인)은 각각 동작하지만 사용자에게는 서로 다른 네 개의 도구로 보인다. 원인은 화면 구조가 **문서 종류별**(`frontend/src/components/layout/menu.ts`의 4개 메뉴, `App.tsx`의 4개 라우트)로 짜여 있어서다. 이 출품작의 논지 "현장의 안전 문서들은 서로를 기억하지 못한다. SAIFE는 기억한다"에서 기억은 DB(설비 ID)에는 있지만 화면에서는 타임라인 페이지에 가야만 보인다. 타임라인은 보관함이고, 보관함은 신박하지 않다.

**원칙 (모든 결정의 기준).**
1. **기억은 보여주는 것이 아니라 끼어드는 것이다.** 사용자가 행동하려는 순간에 시스템이 아는 것을 먼저 말한다.
2. **모든 행동은 설비에서 시작해 설비로 돌아온다.** 명사(설비) 하나 위의 동사 네 개(점검·작업 신고·사고 신고·이력).
3. **묻지 않은 것을 보이게 한다.** "이미 알고 있어 묻지 않음"은 시스템이 아니라 사용자가 확인해야 신박하다.

**변경 4개.** ① 설비 홈(IA 뒤집기) ② 기억을 말하게 하기(회상 카드 + 사고 연쇄) ③ 오늘 할 일(기억이 만든 인박스) ④ 시드 이야기(V8). 아래 순서대로 구현한다.

---

## 1. 시작 전 반드시 읽을 것

읽고 나서 "읽었다"고 한 줄로 확인한 뒤 시작한다.

- `CLAUDE.md`, `README.md`, `TODOS.md`
- `.claude/rules/*.md` 전부 (특히 `api-contract.md`, `sse-streaming.md`, `ai-tool-calling.md`, `risk-domain.md`, `deployment.md`, `external-library.md`)
- `frontend/.claude/rules/*.md` 전부 (`architecture.md`, `design-system.md`, `ui-styling.md`)
- `backend/src/main/resources/db/migration/V1__core_schema.sql`, `V2__seed_virtual_site.sql`, `V7__seed_rejected_photo_candidate.sql`
- `backend/.../dashboard/service/EquipmentTimelineService.java`, `dashboard/dto/TimelineDtos.java`
- `backend/.../incident/service/EquipmentHistoryRecaller.java`, `incident/dto/IncidentDtos.java`, `incident/service/IncidentService.java`
- `backend/.../ai/tools/LocationEquipmentTools.java`, `ai/agent/AgentService.java`, `ai/agent/AgentController.java`, `ai/agent/DemoConversationScript.java`
- `frontend/src/App.tsx`, `components/layout/menu.ts`, `api/endpoints.ts`, `types/timeline.ts`, `types/incident.ts`, `types/sse.ts`
- `frontend/src/pages/WorkPlan/hooks/useAgentStream.ts`, `pages/Incident/components/IncidentResult.tsx`, `pages/Timeline/*`
- `docs/experiments/README.md`, `docs/experiments/uc2_smoke.py`, `docs/qa-report-20260921.md`

---

## 2. 절대 규칙

- `ai/config/`의 클래스는 건드리지 않는다. Spring AI 자동설정으로 되돌리지 않는다.
- **데모 모드(키 없음)에서도 새 기능이 전부 동작해야 한다.** 회상 카드·연쇄·홈·오늘 할 일은 모델 없이 DB 조회로 나온다. `DemoConversationScript`도 함께 갱신한다.
- API 계약: backend DTO ↔ `frontend/src/types/` 1:1(camelCase). `Page<T>` 직접 반환 금지. 새 DTO는 `TimelineDtos`처럼 `final class` + `record`.
- SSE 새 이벤트는 `.claude/rules/sse-streaming.md`의 봉투 규약을 따른다. `types/sse.ts`에 타입을 추가하고 `useSSEStream` 핸들러 맵에 등록한다.
- 스키마 변경·시드는 Flyway 마이그레이션(`V8__...`)으로만. `ddl-auto: validate`라 엔티티만 고치면 부팅이 실패한다.
- 새 라이브러리 추가 금지(`external-library.md`). 애니메이션은 CSS 전환만 쓰고 `prefers-reduced-motion`을 존중한다.
- 하드코딩 금지. 회상·연쇄·카드·인박스의 모든 문장은 DB 조회 결과에서 만든다. "이 설비는 3개월 전…" 같은 문장을 상수로 박지 않는다.
- 기존 4개 라우트(`/work-plan` `/vision` `/incident` `/timeline`)는 **유지**한다. 지우지 않고 진입 컨텍스트만 받게 한다.
- 행동이 끝난 뒤 **자동 리다이렉트하지 않는다.** 결과 화면에 "설비 타임라인에 기록됨 → 보기" 링크를 둔다(시연 중 화면이 갑자기 바뀌면 안 된다).
- 소스 주석 한국어. 커밋은 Conventional Commits, 본문 한국어. `.env` 커밋 금지.
- 사업장·설비·인물은 전부 가상. 실명·회사명 금지.

---

## 3. 작업 방식

- 아래 Phase 순서대로 진행한다. **각 Phase 끝에** ① 백엔드 `./gradlew test` ② 프론트 `npm run lint && npm run build && npx vitest run` ③ 해당 Phase의 스모크 스크립트를 실제로 실행해 결과를 보여준다. 통과 전에는 다음 Phase로 가지 않는다.
- Phase마다 커밋한다(아래 커밋 메시지 예시 참고). 한 Phase 안에서도 논리 단위로 쪼개 커밋해도 된다.
- 판단이 필요한 지점은 **멈추지 말고** 원칙(0절)에 따라 결정하고, 최종 보고서의 "결정 사항"에 이유와 함께 적는다. 정말 막히는 것(스키마 충돌, 테스트 불가)만 질문한다.
- 기존 테스트가 라우트·메뉴 변경으로 깨지면 의도된 변경이므로 테스트를 새 구조에 맞게 고친다(삭제 금지).
- 마지막에 `docs/connectivity-report-YYYYMMDD.md`를 작성한다(9절).

---

## Phase 0 — 기준선 측정 (구현 전에 먼저)

변경 전 상태를 숫자로 남긴다. 나중에 보고서의 Before 열이 된다.

1. `docker compose -f docker-compose.dev.yml up -d` → `cd backend && ./gradlew bootRun`(키 없이, 데모 모드) → `cd frontend && npm run dev`.
2. `python docs/experiments/reset_demo_data.py` 실행.
3. `docs/experiments/baseline_connectivity.py`를 새로 만들어 다음을 측정·JSON 저장(`docs/experiments/baseline_connectivity_result.json`):
   - **B1 기억 도달 클릭 수**: 앱 진입(`/`)부터 "이동식 사다리 A에 미이행 조치가 있다"는 사실이 화면에 문자열로 나타날 때까지 필요한 최소 화면 이동 수. 현재 구조에서는 `/timeline` 선택 후 드롭다운 변경 = 2 이상, 또는 `/work-plan`에서 대화 1턴 후 모델 문장 안에서만 확인 가능 = 보장 없음. 코드 근거로 산정해 기록.
   - **B2 회상 보장률**: `POST /api/agent/chat`에 "공장동 후면 차양부 천장 페인트 작업" 첫 턴을 데모 모드로 10회 보내고, 첫 assistant 토큰 묶음 안에 `미이행` 또는 `최근 평가` 문자열이 포함된 비율. 그리고 구조화된 회상 이벤트(`ai.recall`)의 발행 수(현재 0/10이어야 정상).
   - **B3 첫 화면 정보량**: `/`(=`/work-plan`) 최초 렌더에서 설비 이름·등급·미이행이 하나라도 보이는지(현재 아니오).
   - **B4 시드 이야기 완결 설비 수**: `GET /api/dashboard/equipment/{1..6}/timeline`에서 `incidentCount>0`인 설비 수(현재 0).
   - **B5 홈/인박스 존재 여부**: `/api/dashboard/today`, `/api/dashboard/equipment/cards` 호출 결과(현재 404).
4. 커밋: `test: 연결성 개선 기준선 측정 스크립트와 결과`

---

## Phase 1 — 설비 홈 (IA 뒤집기)

### 1-1 백엔드: 설비 카드 API

`GET /api/dashboard/equipment/cards` → `TimelineDtos.EquipmentCard[]` (사업장 1곳, `DEMO_SITE_ID=1`).

```java
public record EquipmentCard(Long id, String name, String locationTag, String processName,
                            RiskLevel currentRiskLevel, AccidentType currentRiskAxis,
                            LocalDate lastAssessedOn,
                            int unfinishedActionCount, int overdueActionCount,
                            int upcomingWorkPlanCount,   // 상태 SUBMITTED/APPROVED/CONDITIONAL & work_date >= 오늘
                            int incidentCount,
                            LocalDate lastEventOn,        // 타임라인 마지막 사건 날짜
                            Emphasis emphasis,            // 백엔드가 정한다 (CRITICAL: 기한 초과 조치 있음 또는 미제출 조사표 / WARNING: 미이행 조치 또는 '상' 등급 / NORMAL)
                            String headline) {}           // 카드 한 줄. TimelineSummary.headline과 같은 생성기 재사용
```

- 구현은 `EquipmentTimelineService`에 `cards(siteId)`를 추가하고 설비별로 기존 `timeline(id).summary()`를 재사용한다. 설비 6개라 N+1은 허용하되, 설비 20개 이상이면 쿼리 통합이 필요하다는 주석을 남긴다.
- **불변식:** `cards[i]`의 등급·미이행·사고 수는 같은 설비의 `timeline(i).summary`와 항상 같다. 이걸 단위 테스트로 박는다(`EquipmentTimelineServiceTest` 또는 신규).
- 정렬: `emphasis` CRITICAL → WARNING → NORMAL, 같은 등급이면 `lastEventOn` 내림차순, 그다음 id.

### 1-2 백엔드: 설비 회상 API (Phase 2가 쓰지만 여기서 만든다)

`GET /api/dashboard/equipment/{equipmentId}/recall` → `IncidentDtos.RecallView`와 **같은 구조**의 `TimelineDtos.RecallView`(중복을 피하려면 `EquipmentHistoryRecaller.Recall`을 감싸는 공용 DTO를 `dashboard/dto`에 두고 `IncidentDtos.RecallView`가 그것을 재사용하도록 정리해도 된다 — 프론트 `types/incident.ts`의 `RecallView` 타입이 깨지지 않게 할 것).

- `EquipmentHistoryRecaller.recall(equipmentId, axis=null, occurredAt=now, knownAsOf=now)`를 호출한다. `axis`가 null이면 `sameAxisAsIncident`는 전부 false, `predicted`는 false — 이건 "사고 전 회상"이므로 정상이다. `headline`은 사고 문맥이 아니라 **작업 전 문맥**으로 만든다. 예: `"최근 평가 '상'(떨어짐) · 미이행 조치 1건(기한 32일 경과)"`. `recall()`에 `Purpose` enum(`PRE_WORK` / `POST_INCIDENT`)을 추가해 headline 문구만 분기한다.
- 응답에 `knownSlots: List<String>` 필드를 추가한다. 이 설비에 대해 시스템이 이미 아는 항목의 사람 말: `["장소", "설비", "공정/작업유형", "최근 평가 등급", "미이행 조치"]` 중 실제로 값이 있는 것만.

### 1-3 프론트: 라우트·메뉴

- `App.tsx`: `/` → `EquipmentHomePage`(lazy). `/equipment/:equipmentId` → `EquipmentDetailPage`(lazy). 기존 4개 라우트 유지. `LANDING_PATH = "/"`.
- `menu.ts`: 첫 항목 `{ key: "home", path: "/", label: "설비 현황", icon: LayoutGrid }` 추가. 기존 4개는 그 아래 "문서별 보기" 그룹으로 유지(라벨은 동사로: `작업 신고` `사진 점검` `사고 신고` `설비 타임라인`). `COLLAPSED_BY_DEFAULT`에 `/` 는 넣지 않는다.
- `endpoints.ts`: `EQUIPMENT_CARDS`, `equipmentRecall(id)`, `TODAY`(Phase 3) 추가.
- `types/timeline.ts`: `EquipmentCard`, `RecallView`(또는 재사용), `knownSlots` 반영.

### 1-4 프론트: 홈 화면 `pages/EquipmentHome/`

- `EquipmentHomePage.tsx`, `index.tsx`, `EquipmentHomeSkeleton.tsx`, `hooks/useEquipmentCards.ts`(`useApiData`), `components/EquipmentCard.tsx`, `components/__tests__/EquipmentCard.test.tsx`.
- 카드 내용(위→아래): 설비명 + 위치 태그 / `RiskBadge`(현재 등급·축) / KPI 3개(`KpiCell` 재사용: 미이행 조치(기한 초과 n), 예정 작업, 사고) / headline 한 줄 / 마지막 사건 날짜. 카드 테두리·배지 톤은 `emphasis`로만 정한다(`statusColors.ts`에 매핑 추가).
- 카드 클릭 → `/equipment/:id`. 카드 우하단에 동사 버튼 3개(`Button size="sm"`): **작업 신고** → `/work-plan?equipmentId=`, **사진 점검** → `/vision?equipmentId=`, **사고 신고** → `/incident?equipmentId=`.
- 상단에 Phase 3의 `TodayInbox` 자리(지금은 비워두되 레이아웃은 잡는다).
- `PageHeader` 제목 "설비 현황", 설명 "모든 기록은 설비 위에 쌓입니다. 설비를 고르면 그 설비가 기억하는 것부터 보입니다."

### 1-5 프론트: 설비 상세 `pages/Equipment/`

- `EquipmentDetailPage.tsx`: 상단 `PageHeader`(설비명 · 위치 · 공정), **동사 버튼 3개**(작업 신고 / 사진 점검 / 사고 신고 — 모두 `?equipmentId=` 부착), 그 아래 `TimelineSummary` + `TimelineList`(기존 `pages/Timeline/components`에서 import — 2페이지 이상이 쓰게 되므로 `architecture.md` 규칙에 따라 `components/timeline/`로 승격한다). 기존 `/timeline` 페이지는 그대로 두되 내부에서 같은 승격 컴포넌트를 쓴다.
- `useTimeline`도 `hooks/`로 승격하거나 `equipmentId`를 인자로 받는 형태로 일반화한다.

### 1-6 프론트: 진입 컨텍스트와 "돌아오기"

- `/vision?equipmentId=`: `useVision`의 초기 `selectedId`를 쿼리로 설정(있으면).
- `/incident?equipmentId=`: `useIncident`의 `form.equipmentId` 초기값을 쿼리로 설정.
- `/work-plan?equipmentId=`: Phase 2에서 회상 카드와 함께 처리. 이 Phase에서는 쿼리를 읽어 `PageHeader` 아래에 "설비: 이동식 사다리 A · 공장동 후면 차양부" `Badge`를 띄우는 것까지만.
- 행동 완료 지점 3곳에 **돌아오기 링크**(`LinkButton` → `/equipment/:id`, 문구 "이 설비 타임라인에 기록됨 → 보기")를 둔다: ① UC3 `createWorkPlan` 성공 후(대화 `ai.done` 시점, 마지막 트레이스에 `createWorkPlan` ok가 있으면) ② UC2 `IncidentResult` 최상단 Callout 옆 ③ UC1 후보 채택 후 결과 영역. 자동 이동 금지.
- 기존 사이드바·메뉴 테스트(`GlobalSidebar.test.tsx`, `menu.test.ts`)를 새 구조로 갱신.

### 1-7 검증

- 단위: 카드-타임라인 불변식 테스트, `EquipmentCard` 렌더 테스트(emphasis별 톤), 메뉴 테스트.
- 스모크: `docs/experiments/home_smoke.py` — `/api/dashboard/equipment/cards` 6건, 각 카드의 `unfinishedActionCount`·`incidentCount`가 `/timeline` summary와 일치, 정렬이 emphasis 순인지, 시드 상태에서 이동식 사다리 A가 `CRITICAL`(기한 초과 조치 1건)인지.
- 브라우저: `/` 진입 → 사다리 A 카드에 "미이행 1(기한 초과 1)"이 **0클릭**으로 보이는지 캡처.
- 커밋 예: `feat(dashboard): 설비 카드·회상 API` / `feat(frontend): 설비 홈과 설비 상세 — 모든 행동은 설비에서 시작한다` / `refactor(frontend): 타임라인 컴포넌트를 공용으로 승격`

---

## Phase 2 — 기억을 말하게 하기

### 2-1 회상 카드 (UC3 작업 신고)

**목표:** 작업자가 말하기 전에, 또는 장소를 말한 직후에, 시스템이 아는 것이 **모델 문장과 무관하게** 카드로 뜬다. 데모 모드에서도 100% 뜬다.

- **진입 회상:** `/work-plan?equipmentId=`로 들어오면 `useAgentStream`과 별개로 `equipmentRecall(id)`를 호출해 `RecallCard`를 `ChatThread` 위에 고정 렌더한다(첫 사용자 메시지 전). 카드가 뜨는 것 자체가 "끼어들기"다.
- **대화 중 회상:** `LocationEquipmentTools.findLocationEquipment`가 매칭에 성공하면 `EquipmentHistoryRecaller.recall(...PRE_WORK)`를 호출해 SSE 이벤트 `ai.recall`을 발행한다(봉투 규약 준수, payload = `RecallView`). 도구 레벨에서 발행하므로 모델이 문장에 넣든 말든 카드는 뜬다. `ToolCallTracker.execute` 안에서 성공 분기에 넣는다.
- **프론트:** `types/sse.ts`에 `RecallPayload`, `useAgentStream`에 `"ai.recall"` 핸들러 → `recall` 상태. `pages/WorkPlan/components/RecallCard.tsx`: 제목은 headline, 본문은 위험요인(발생형태 배지 + 빠진 안전조치 + 최근 등급·평가일) / 미이행 조치(내용 + 기한 + 경과일) / 하단 칩 줄 **"이미 알고 있어 묻지 않음: 장소 · 설비 · 최근 평가 등급 · 미이행 조치"**(= `knownSlots`). 카드 톤은 미이행이 있으면 `high`, 없으면 `neutral`. 새 대화(`reset`) 시 카드는 유지하되 `?equipmentId`가 없으면 비운다.
- **시스템 프롬프트 강화(`AgentService.SYSTEM_PROMPT_TEMPLATE`):** [진행 순서] 2번을 다음으로 바꾼다: "설비가 확인되면 **답변의 첫 문장은 반드시** 그 설비의 최근 평가 등급과 미이행 조치를 요약하는 문장이어야 합니다. 그다음에 부족한 항목을 하나만 물어보세요." `ChatRequest`에 `equipmentId`(선택)를 추가하고, 있으면 `toolContext`(`AgentContextKeys.EQUIPMENT_ID`)에 넣고 시스템 프롬프트 끝에 "[시작 설비] id=N 이름 — 이 설비로 findLocationEquipment를 먼저 확인하세요"를 덧붙인다.
- **데모 스크립트:** `DemoConversationScript`의 첫 턴 첫 토큰이 회상 문장으로 시작하도록 갱신하고, 도구 실행 시 `ai.recall`도 발행되게 한다(도구 레벨 발행이라 자동으로 될 것 — 확인).

### 2-2 사고 연쇄 (UC2)

**목표:** "등록 한 번에 세 가지가 동시에 일어난다"가 아니라 **"한 사건이 세 곳을 차례로 바꾼다"**로 보이게 한다.

- **백엔드:** `IncidentDtos.RegisterResponse`에 `List<AffectedWorkPlan> affectedWorkPlans` 추가 — 같은 설비, 상태 `SUBMITTED/APPROVED/CONDITIONAL`, `work_date >= 사고일`인 작업계획서. `record AffectedWorkPlan(Long workPlanId, String workName, LocalDate workDate, String status, String warning)`. `IncidentService.register` 안에서 해당 계획서의 `approval_note`(또는 새 컬럼 `warning_note` — V8에 추가) 에 "이 설비에서 {날짜} {발생형태} 사고 발생 — 작업 재개 전 수시평가 #N 확인" 을 덧붙인다. 조회는 `WorkPlanRepository`에 쿼리 메서드 추가.
- `RegisterResponse`에 `List<CascadeStep> cascade` 추가: 백엔드가 **순서를 정한다**(화면마다 다르게 판단하면 흔들린다). 
  ```java
  public record CascadeStep(int order, String kind, String title, String detail, Emphasis emphasis, Long refId, String refType) {}
  // order 1 RECALL   : "이 설비의 사전 기록 소환" — predicted면 CRITICAL, headline을 detail로
  // order 2 FOLLOW_UP: "수시평가 #N 자동 생성" — 등급 변화 요약(중→상 n건, 유지 m건)
  // order 3 REPORT   : "산업재해조사표 기한" — D-n · 법적 근거
  // order 4 WORK_PLAN: "진행 중 작업계획서 n건에 경고 부착" — affectedWorkPlans 요약 (0건이면 detail "해당 없음", NORMAL)
  ```
- **프론트:** `IncidentResult.tsx` 최상단에 `CascadeList` 컴포넌트(`pages/Incident/components/CascadeList.tsx`)를 두고 4단계를 세로 스텝으로 렌더. 마운트 시 150ms 간격으로 순차 등장(CSS `transition` + `useEffect` 타이머, `prefers-reduced-motion`이면 즉시). 각 스텝은 해당 카드(기존 소환 카드 / 수시평가 카드 / KPI / 새 `AffectedWorkPlans` 표)로 앵커 스크롤. 기존 카드들은 그대로 두고 그 위에 스텝을 얹는다.
- `types/incident.ts`에 `AffectedWorkPlan`, `CascadeStep` 추가.

### 2-3 검증

- `docs/experiments/recall_smoke.py`: 데모 모드에서 "공장동 후면 차양부 천장 페인트 작업" 첫 턴을 10회 → `ai.recall` 이벤트 **10/10**, payload에 `unfinishedActions.length>=1`, `knownSlots`에 "미이행 조치" 포함. 첫 assistant 토큰 묶음이 회상 문장으로 시작하는지 10/10. 라이브 키가 있으면 `--live`로 같은 측정을 5회 추가(프롬프트 준수율 기록).
- `uc2_smoke.py` 확장: 등록 응답에 `cascade` 4단계가 순서대로 있는지, UC3에서 만든 계획서가 `affectedWorkPlans`에 들어가고 `warning`이 붙었는지, 이후 `GET /api/work-plan/{id}`에 경고가 보이는지.
- 단위: `RecallCard`(knownSlots 칩 렌더, 톤), `CascadeList`(4스텝 순서·reduced-motion 즉시 렌더), `IncidentServiceTest`(affectedWorkPlans 필터 — 사고일 이전 계획서는 제외).
- 커밋 예: `feat(agent): 설비 매칭 시 회상 이벤트 발행 — 모델 문장과 무관하게 카드가 뜬다` / `feat(incident): 사고 등록 연쇄 — 진행 중 작업계획서에 경고 부착` / `feat(frontend): 회상 카드·사고 연쇄 스텝`

---

## Phase 3 — 오늘 할 일 (기억이 만든 인박스)

### 3-1 백엔드 `GET /api/dashboard/today`

```java
public record TodayView(LocalDate asOf, List<TodayItem> items, int criticalCount, int warningCount) {}
public record TodayItem(String kind, Emphasis emphasis, String title, String detail,
                        Long equipmentId, String equipmentName,
                        LocalDate dueDate, Long daysRemaining,
                        String linkType, Long refId) {}   // linkType: EQUIPMENT / WORK_PLAN / INCIDENT / ASSESSMENT
```

규칙(`dashboard/service/TodayService.java`, 각 규칙은 private 메서드 하나, 전부 단위 테스트):

| kind | 조건 | emphasis | title 예 |
|---|---|---|---|
| `OVERDUE_ACTION` | action.status=OVERDUE 또는 (due_date<오늘 & status≠DONE) | CRITICAL | "기한 초과 조치 — 차양부 안전대 부착설비 설치 (32일 경과)" |
| `DUE_ACTION` | status=PENDING & 오늘≤due_date≤오늘+14 | WARNING | "조치 기한 D-7 — 고소작업대 안전난간 보수" |
| `RISKY_WORK_PLAN` | work_plan.status∈{SUBMITTED,APPROVED,CONDITIONAL} & 오늘≤work_date≤오늘+7 & (그 설비에 미이행 조치 있음 또는 현재 등급 HIGH) | CRITICAL | "D-1 작업 — 차양부 천장 페인트 (설비 미이행 조치 1건)" |
| `PENDING_APPROVAL` | work_plan.status=SUBMITTED | WARNING | "승인 대기 — 차양부 천장 페인트" |
| `REPORT_DUE` | incident.report_status∈{REQUIRED,DRAFTED} & report_due_date 있음 | ≤3일 CRITICAL, 그 외 WARNING | "조사표 제출 D-25 — 이동식 사다리 A 떨어짐" |
| `PATROL_DUE` | site.regular_track=true & 이번 달 assessed_on인 ROUTINE 평가 없음 | WARNING | "이번 달 순회점검 미실시 (상시평가 요건)" |
| `PERIODIC_DUE` | 가장 최근 INITIAL/REGULAR 평가 + 1년이 오늘+60 이내 | WARNING | "정기평가 D-40" |

정렬: CRITICAL → WARNING → NORMAL, 같은 등급이면 `daysRemaining` 오름차순(null 마지막). 상한 20건. 오늘 날짜는 `LocalDate.now(ZoneId.of("Asia/Seoul"))`.

### 3-2 프론트

- `pages/EquipmentHome/components/TodayInbox.tsx` + 테스트. 홈 최상단. 헤더 "오늘 할 일 · {asOf}" + 배지(긴급 n · 주의 m). 항목 클릭 → `linkType`별 이동(EQUIPMENT→`/equipment/:id`, WORK_PLAN→`/work-plan`에서 상세 모달 열기 가능하면 그것, 아니면 `/equipment/:id`, INCIDENT→`/incident`, ASSESSMENT→서식 링크 `formUrl.assessment`). 항목 0건이면 `EmptyState` "오늘 처리할 항목이 없습니다".
- `hooks/useToday.ts`(`useApiData`). 폴링 금지, `RefreshButton` 제공.

### 3-3 검증

- 단위: 규칙 7개 각각 경계값 테스트(기한 당일, 14일째, 사고일 이전 계획서 제외 등).
- 시드 스냅샷 테스트: `reset_demo_data.py` 직후 `/api/dashboard/today`에 **최소** `OVERDUE_ACTION`(사다리 A, action id=1) 1건과 `DUE_ACTION`(고소작업대 action id=5) 1건이 있고 `REPORT_DUE`는 0건(V8 이야기는 제출 완료 상태라야 함 — Phase 4)인지. `PATROL_DUE`는 `site.regular_track` 값에 따라 기대값을 정해 명시.
- `docs/experiments/today_smoke.py`: 시드 상태 → UC3 계획서 등록 → `PENDING_APPROVAL` 추가 확인 → 승인 → `RISKY_WORK_PLAN` 추가 확인(사다리 A는 미이행 있음) → 사고 등록 → `REPORT_DUE` 추가 확인. 각 단계 항목 수 변화를 출력.
- 커밋 예: `feat(dashboard): 오늘 할 일 — 기억이 만든 인박스` / `feat(frontend): 홈 상단 오늘 할 일`

---

## Phase 4 — 시드 이야기 (V8)

**목표:** 첫 실행부터 타임라인 하나가 논지를 스스로 말한다. **이동식 사다리 A(id=1)는 라이브 시연 무대이므로 비워 둔다.** 이야기는 **고소작업대(id=6, 공장동 2층 조립구역)** 위에 쓴다.

`V8__seed_story_aerial_platform.sql` (V2처럼 `CURRENT_DATE - INTERVAL` 상대 날짜, id 충돌 없게 `setval` 갱신):

| 상대일 | 사건 | 테이블·핵심 값 |
|---|---|---|
| -75일 | 상시평가(순회점검) | assessment(kind ROUTINE, trigger PATROL, CONFIRMED) · assessment_hazard(hazard 7 = 고소작업대 발판 안전난간, FALL, risk HIGH, rule_trace) |
| -75일 | 감소대책 | 이 평가에 연결된 조치 1건 추가: "작업 전 안전대 착용 지도" DONE(-70일) |
| -50일 | 작업계획서 | work_plan(work_name "2층 조립구역 조명 교체", work_date -50일, status CONDITIONAL, approval_note "안전난간 보수 완료 확인 후 작업", briefing 텍스트 = 위험요인 기반 3줄, briefing_ack_at -50일 08:30, approved_by "관리부 박OO") |
| -45일 | 사고 | incident(equipment 6, work_plan 위 계획서, occurred_at -45일 10:20, victim "이OO", severity, leave_days 5, accident_type FALL, description "고소작업대 발판에서 조명 교체 중 난간 미보수 구간으로 추락, 발목 골절", report_due_date -45일+1개월, **report_status SUBMITTED**, follow_up_assessment_id ↓) |
| -45일 | 수시평가(자동 생성분을 시드로 재현) | assessment(kind OCCASIONAL, trigger INCIDENT, trigger_ref_id = 위 incident, CONFIRMED) · assessment_hazard(hazard 7, HIGH 유지, rule_trace에 "사고 발생으로 빈도 상향") |
| -30일 | (V2 기존) 상시평가 2 | 이미 hazard 7을 HIGH로 채점하고 있다. 난간이 아직 미보수라 정합함 — 건드리지 않는다 |
| -20일 | 조치 완료 | action 신규: "고소작업대 발판 안전난간 보수 완료" DONE, completed_at -20일, guide_ref |
| -20일 | (V2 기존) action id=5 정리 | 내용이 "안전난간 보수"라 위 완료 조치와 모순된다. `UPDATE action SET content='고소작업대 안전난간 보수 후 월 1회 점검(재발 방지)' WHERE id=5` — PENDING · due +7일은 유지 |
| -10일 | 재평가(상시) | assessment(ROUTINE, PATROL) · assessment_hazard(hazard 7, **MEDIUM**, rule_trace "난간 보수 완료로 강도 하향") |

- `EquipmentHistoryRecaller`의 `knownAsOf` 순환 방지 로직이 시드에도 맞는지 확인: 사고 시각 이후에 만들어진 수시평가가 "사고 전 기록"으로 소환되지 않아야 한다(`created_at`을 사건 순서대로 명시적으로 넣는다 — 기본값 `now()`로 두면 전부 같은 시각이 돼 순서가 깨진다).
- 시드 후 기대 상태: 고소작업대 카드 = 사고 1 · 현재 등급 '중' · 미이행 조치 1건(id=5, D-7) → 이 카드의 headline이 "사고 후 재평가로 '중', 점검 조치 D-7" 같은 완결된 이야기를 말해야 한다. 오늘 할 일에는 `REPORT_DUE`가 **뜨지 않아야** 한다(SUBMITTED).
- Phase 3 시드 스냅샷 기대값(V8 반영 후, site.regular_track=true 확인됨): `OVERDUE_ACTION` 1(사다리 A, id=1) · `DUE_ACTION` 2(id=4 D-14, id=5 D-7) · `PATROL_DUE` 1(V2 평가 2는 지난달 날짜) · `PERIODIC_DUE` 1(V2 평가 3 INITIAL이 -10개월 → 만료 약 D-60) · `REPORT_DUE` 0 · `PENDING_APPROVAL` 0 · `RISKY_WORK_PLAN` 0. 이 기대값을 테스트에 고정한다.
- `reset_demo_data.py`가 V8 데이터까지 복원하는지 확인·수정.
- 검증: `home_smoke.py`에 고소작업대 `incidentCount==1`, 타임라인 사건 순서(평가→조치→계획서→사고→수시평가→조치→재평가) 단언 추가. UC2 recall을 고소작업대에 사고를 하나 더 등록해 돌렸을 때 `priorIncidents`에 -45일 사고가 1건 보이는지.
- 커밋 예: `feat(seed): 고소작업대 이야기 시드 — 첫 실행부터 타임라인이 논지를 말한다`

---

## Phase 5 — 시연 동선 점검과 마무리

1. `python docs/experiments/reset_demo_data.py` 후 키 없이 `docker compose up -d --build`로 **깨끗한 기동** 확인(README의 22초 기준 재측정).
2. 시연 동선을 브라우저로 한 번 완주하고 각 단계 캡처를 `docs/demo-run-YYYYMMDD/`에 저장:
   `/` 홈(오늘 할 일에 사다리 A 기한 초과) → 고소작업대 카드 → 상세 타임라인(완결 이야기) → 홈 → 사다리 A 카드 "작업 신고" → 회상 카드가 먼저 뜸 → 대화 1턴("내일 사다리 놓고 차양부 천장 페인트, 황OO 과장과 둘이서 8시간") → `ai.recall` + 계획서 생성 + "타임라인에 기록됨" 링크 → 상세 모달에서 조건부 승인 → 홈: 오늘 할 일에 `RISKY_WORK_PLAN` 등장 → 사다리 A "사고 신고" → 연쇄 4스텝 순차 등장 → 홈: `REPORT_DUE` 등장 → 사다리 A 타임라인에 전부 한 줄.
3. `README.md`의 "화면" 표를 새 구조로 갱신(홈·설비 상세 추가, 동사 라벨). `CLAUDE.md`의 도메인 모듈 표에 `dashboard`의 cards/recall/today 추가. `TODOS.md` 갱신.
4. `docs/qa-report-20260921.md` 형식으로 `docs/qa-report-YYYYMMDD.md`(이번 변경 범위의 발견·수정 목록) 작성.
5. 커밋: `docs: 연결성 개선 후 시연 동선·QA 리포트·README 갱신`

---

## 9. 최종 보고서 `docs/connectivity-report-YYYYMMDD.md`

반드시 아래 표를 채운다. Before는 Phase 0 결과, After는 구현 후 같은 스크립트 재실행 결과다.

| 지표 | 정의 | Before | After | 목표 |
|---|---|---|---|---|
| M1 기억 도달 클릭 수 | 진입부터 사다리 A 미이행 사실이 보일 때까지 | (Phase 0) | | 0 |
| M2 회상 보장률 | 첫 턴 `ai.recall` 발행 / 10회 (데모) | 0/10 | | 10/10 |
| M2' 회상 문장 준수율 | 첫 assistant 문장이 회상으로 시작 / 10회 (데모) · /5회 (라이브) | | | 10/10 · ≥4/5 |
| M3 연쇄 가시화 | 사고 응답의 cascade 4단계 + affectedWorkPlans 경고 부착 | 없음 | | 4/4 |
| M4 카드-타임라인 일치 | 6설비 summary 일치 | N/A | | 6/6 |
| M5 오늘 할 일 규칙 | 시드 스냅샷 기대 항목 일치 | N/A | | 100% |
| M6 시드 이야기 | incidentCount>0 설비 수 | 0 | | 1 (고소작업대) |
| M7 기동 | 키 없이 clean build → healthy 시간 | 22s | | ≤30s |
| M8 테스트 | gradle / vitest / smoke 통과 수 | 15 / n / 4 | | 전부 통과 |

추가로 "결정 사항"(판단이 필요했던 지점과 이유), "변경하지 않은 것과 이유", "남은 리스크"를 각 5줄 이내로 적는다. 별지2용으로 **"신규개발분 요약"** 절에 이번 작업의 파일·엔드포인트·컴포넌트 목록을 정리한다.

---

## 10. 시작

1절의 파일을 읽고 "읽었다"고 확인한 뒤, Phase 0부터 시작한다. 각 Phase가 끝날 때마다 실행 결과(테스트 요약·스모크 출력·커밋 해시)를 보고하고 다음 Phase로 넘어간다.
