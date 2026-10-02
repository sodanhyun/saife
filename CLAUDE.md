# CLAUDE.md — SAIFE (세이프)

소규모 제조 사업장 산재 예방을 위한 **위험성평가 AI Agent**. 제4회 경남 AI·SW 경진대회 출품작.

**한 줄 논지**: 현장의 안전 문서들은 서로를 기억하지 못한다. SAIFE는 기억한다.
위험성평가·위험작업 작업계획서·산업재해조사표를 **하나의 설비 ID** 위에서 잇는다.

기획서(승인본): `~/.gstack/projects/SAIFE/taeli-unknown-design-20260920-163000.md`

## 프로젝트 구조 (모노레포)

```
saife/
├── backend/    Spring Boot 3.4 + Java 21 + Spring AI 1.1.5 (Gemini) + PostgreSQL 16/pgvector
├── frontend/   React 19 + Vite + TypeScript
│   └── .claude/rules/   프론트 규칙 (design-system, ui-styling, architecture)
├── docs/       설계·계약 SSOT
└── .claude/
    ├── rules/      공통 규칙 (api-contract, sse-streaming, ai-tool-calling, public-api, risk-domain)
    └── commands/   슬래시 커맨드
```

루트 패키지: **`io.saife`**

> 데이터셋(AI Hub 샘플)과 공공 API 키는 **리포 밖** 상위 폴더(`../`)에 둔다. 커밋 금지.

## 빌드 명령어

```bash
# 인프라 (PostgreSQL 16 + pgvector)
docker compose -f docker-compose.dev.yml up -d

# 백엔드 (backend/)
./gradlew bootRun          # localhost:8080
./gradlew test             # 전체 테스트
./gradlew build            # 빌드

# 프론트엔드 (frontend/)
npm run dev                # localhost:5173
npm run build
npm run lint

# 전체 스택
docker compose up -d --build
```

## 포트

| 서비스 | 포트 |
|--------|------|
| frontend (Vite) | 5173 |
| backend | 8080 |
| postgres + pgvector | 5432 |

## 통신 구조

- **REST**: frontend `/api/*` → (dev: Vite 프록시 / prod: nginx) → backend `:8080`
- **SSE**: 에이전트 도구 호출 트레이스 + 토큰 스트리밍 (`SseEmitter` ↔ fetch ReadableStream)
- **인증**: JWT (HS256), 역할 2종 `WORKER` / `MANAGER`. 테넌시 없음(가상 사업장 1곳)

## 도메인 모듈

| 패키지 | 역할 |
|--------|------|
| `core/site` `core/process` `core/equipment` | **데이터 코어** — 사업장·공정/장소·설비. 모든 것이 설비 ID로 묶인다 |
| `core/hazard` `core/assessment` `core/action` | 위험요인·평가·감소대책. 평가 종류(최초/수시/정기/상시) |
| `workplan` | **UC3** 위험작업 작업계획서. 대화형 등록 → 브리핑 → 승인 → 완료 |
| `incident` | **UC2** 산재 사후 등록. 설비 이력 자동 소환 → 수시평가 자동 생성 → 법정 기한 |
| `dashboard` | **UC4** 설비 1개 타임라인 뷰 (평가→작업계획→사고→재평가). `dashboard.service`의
  `EquipmentTimelineService`(카드 summary), `RecallService`(진입 회상 — `ai.recall`과 같은 payload를
  카드 클릭 없이도 재사용), `TodayService`(홈 "오늘 할 일" 인박스 — OVERDUE_ACTION/DUE_ACTION/
  RISKY_WORK_PLAN/PENDING_APPROVAL/REPORT_DUE/PATROL_DUE/PERIODIC_DUE 8종 규칙(WORK_HOLD 포함)) |
| `ai/agent` | 에이전트 오케스트레이션 (도구 호출 루프, 슬롯 되묻기) |
| `ai/tools` | Spring AI `@Tool` 6종 + `ToolRegistry` |
| `ai/vision` | 사진 → **빠진 안전조치 탐지** (Gemini 멀티모달) |
| `ai/config` | Gemini 안정화 설정 — **아래 필독** |
| `evidence` | 근거 계층(RAG). `evidence.chunk`(청킹·오버랩) · `evidence.search`(하이브리드 RRF·리랭크·
  키워드 폴백) · `evidence.index`(시드 빌드·임베딩 배치·체크포인트 재개) · `evidence.media`(공단
  사진·PDF 온디맨드 캐시 프록시) · `evidence.ledger`(대화별 `#n` 인용 번호·환각 인용 후처리) ·
  `evidence.live`(법제처·MSDS 라이브 클라이언트, `LiveOrCache` 회로) |

> **도구 파라미터 메모**: `POST /api/agent/chat`의 `ChatRequest.equipmentId`는 **첫 턴에만**
> 실린다 — 설비 카드 클릭으로 진입하면 프론트가 이미 아는 설비 ID를 자유 텍스트 매칭 없이
> 바로 넘긴다. 이게 있으면 `findLocationEquipment`가 모호한 문구(같은 위치에 설비 2건)에도
> 곧바로 확정 매칭하고 `ai.recall`이 뜬다. 텍스트만으로 시작하는 대화(홈이 아니라 주소창
> 직접 진입 등)는 이 필드 없이 기존 유사도 매칭 경로를 그대로 탄다.

## ⚠️ Gemini 연동 — 이미 겪은 문제들 (Inufleet에서 이식)

`ai/config/`의 클래스들은 **장식이 아니다.** 각각 실제로 터진 문제의 대응이다. 지우거나
Spring AI 자동설정으로 되돌리지 말 것.

| 클래스 | 막고 있는 문제 |
|--------|---------------|
| `SafeCandidateGoogleGenAiChatModel` | Spring AI 1.1.5 라이브러리 NSEE 버그 2건. 도구 다중 라운드에서 빈발 |
| `FuzzyToolCallingManager` | Gemini가 도구명을 환각 (camelCase 등록 → snake_case 호출) |
| `ToolCallingConfig` | 도구 예외도 **JSON으로 감싸야** 함 (`parseJsonToMap()`이 JSON을 강제) |
| `GeminiClientConfig` | 타임아웃 180초. **SDK 재시도 비활성** — 재시도는 앱 계층이 전담 |
| `GeminiSafetySettings` | 안전 필터 차단 비활성화 — 아래 |

**`SAFETY_SETTINGS_OFF`는 유지한다** — 단, 근거를 정확히 적어둔다.

SAIFE가 다루는 정상 입력은 산재 사고 서술이다("스크류에 끼임", "7m 아래로 추락",
"하적단이 무너지며 깔림"). 이런 문장은 `HARM_CATEGORY_DANGEROUS_CONTENT`의 사정거리 안에 있다.

**2026-09-20 실측**: `gemini-3.8-flash`에서 사망사고 서술 + 재발방지 대책 요청을
**필터 기본값으로 호출해도 차단되지 않았다**(finishReason=STOP, 정상 답변).
Inufleet이 2.5 세대에서 겪은 차단이 3.x 세대에서는 완화된 것으로 보인다.

그래도 끄고 간다. 이유는 ① 표본 1건으로 "절대 안 걸린다"를 결론낼 수 없고
② 더 graphic한 사고 사진·텍스트에서는 다를 수 있으며 ③ 끄는 비용이 0인데,
켜둔 상태에서 걸리면 **예외가 아니라 조용한 빈 결과**로 나타나 원인 파악이 매우 어렵다.

## ⚠️ 공공데이터 API — B552468 게이트웨이 함정

공단 API 4종이 `apis.data.go.kr/B552468/*` 아래 같이 산다. **실제 데이터셋을 고르는 건
경로가 아니라 `callApiId` 파라미터**다. 잘못된 값은 **에러가 아니라 다른 데이터셋을
조용히 반환**한다 (KOSHA GUIDE 경로 + `callApiId=1040` → 사고사망 데이터가 나옴).

→ **캐싱 크롤러에 데이터셋별 응답 필드 검증을 반드시 넣는다.** 상세: `.claude/rules/public-api-integration.md`

## 공통 규칙

- **소스 주석: 한국어**
- **커밋: Conventional Commits** (`feat:`, `fix:`, `refactor:`) — 본문 한국어
- **`.env` 커밋 금지.** `.env.example`만 추적
- **API 계약**: backend DTO ↔ frontend `src/types/` 인터페이스 **1:1 매핑**
- **페이징**: backend `PageResponse.from(page)` ↔ frontend `PaginationResponse<T>` 필수.
  `Page<T>` 직접 반환 금지
- **필드명**: backend camelCase (Jackson) = frontend 인터페이스 필드명 동일
- **Lombok 전역 사용**: `@Getter` `@Builder` `@RequiredArgsConstructor` `@Slf4j`.
  수동 생성자·getter·setter 작성 금지. `@Qualifier`가 필요할 때만 수동 생성자.
  순환 의존성은 `@Lazy` 대신 `ObjectProvider<T>`
- **DDL**: `ddl-auto: validate` + **Flyway**. 엔티티만 고치고 마이그레이션을 안 쓰면 부팅 실패

## 도메인 규칙 (위험성평가)

- **AI는 후보만 제안한다. 확정은 사람이 한다.** 등급 판정은 LLM이 아니라 **백엔드 룰 엔진**이다
- **사진 판독 대상은 "사진에 물리적으로 있거나 없는 것"으로 한정.** 추론해야 아는 것은 축에서 뺀다
  (인터록·유도자 부재·환기 적정성은 그래서 제외됨)
- 기록 3요소(유해위험요인 / 위험성 결정 내용 / 조치 내용)는 **시행규칙 제37조** 상 3년 보존
- 상세: `.claude/rules/risk-domain.md`

## Skill routing

요청이 스킬과 일치하면 반드시 Skill 도구를 **첫 번째** 액션으로 호출.

| 요청 유형 | 스킬 |
|----------|------|
| 제품 아이디어, 브레인스토밍, "이게 가치 있나?" | `office-hours` |
| 버그, 에러, "왜 안 되나?", 500 에러 | `investigate` |
| 배포, PR 생성, push | `ship` |
| QA, 사이트 테스트, 버그 찾기 | `qa` |
| 코드 리뷰, diff 확인 | `review` |
| 아키텍처 리뷰, 설계 검토 | `plan-eng-review` |
| 진행 저장, 체크포인트, 재개 | `checkpoint` |
| 시각 점검, 디자인 폴리시 | `design-review` |

## 대회 제약 (잊지 말 것)

- **9/29(화) 12:00 접수 마감** — 신청서·재직확인·동의서. 놓치면 참가 무산
- **10/4 코드 프리즈**, 10/5 제출, 10/6 버퍼, **10/12(월) 라이브 발표심사**
- 사업장·설비·서식·인물은 **전부 가상**. 회사 자산 사용 금지
- 무대에서 **외부 API 라이브 호출 0** — 전량 로컬 캐시. 모델은 라이브(폴백으로 픽스처)
- 별지2 출처·AI 활용 신고서에 **쓴 모델·데이터·오픈소스를 전부 정직하게** 기재
