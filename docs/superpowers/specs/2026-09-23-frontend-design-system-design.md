# SAIFE 프론트엔드 디자인 시스템 이식 및 전면 리팩토링 — 설계

작성일: 2026-09-23 · 상태: 사용자 구두 승인 (설계 대화) → 스펙 검토 대기

## 1. 목적

SAIFE 프론트엔드(`frontend/`)에 **체계**를 세운다. MetalFlow(`C:\Users\taeli\metalflow-platform\frontend`)의
디자인 토큰·공통 컴포넌트·훅·폴더 규칙을 SAIFE 맞춤으로 이식하고, 그 위에 기존 4화면을 다시 쓴다.
"AI가 만든 티"가 나지 않는 절제된 콘솔 외관을 목표로 한다.

성공 기준:
- 4화면(사진 판독·작업계획서·사고 등록·설비 타임라인)이 기존 기능·API 계약·SSE 이벤트를 그대로 유지한 채 동작한다.
- 버튼·입력·배지·표·카드가 전부 `components/ui/` 프리미티브를 통해 그려진다. 페이지 파일에 색상 hex나 반복 클래스 문자열이 없다.
- `npm run build`, `npm run lint`, `npm run test`가 통과한다.
- 프로젝터(1280×720 이상)에서 트레이스 패널·타임라인이 읽힌다.

범위 밖: 백엔드 변경, 로그인 화면, 권한 카탈로그, i18n, 다크 모드, 모바일 최적화(sm 이하는 깨지지만 않으면 됨).

## 2. 원천과 제외

| 원천 | 쓰는 것 |
|---|---|
| MetalFlow `frontend/` | 1차 원천. 토큰 구조, `components/ui`, 훅, 유틸, 규칙 문서, 테스트 |
| taelim `taelim-fe/` | 참고만. `design_handoff_inufleet_refined/source/design-tokens.css`의 의미 토큰 명명 |

이식하지 않는 것: i18n(`t()` → 한국어 문자열), MUI 및 날짜 피커(네이티브 `<input type="datetime-local">` 유지),
FilterBar, Pagination 컴포넌트(타입 `PaginationResponse<T>`만 유지), 권한 프레임워크(`auth/`), 버전 감시,
페이지 프리로드, 콘솔 전용 컴포넌트, 도메인 포매터(`pipeLot` 등), GlobalSidebar의 동기화 카드·언어 전환.

## 3. 디자인 토큰 (`frontend/tailwind.config.js`)

MetalFlow와 같은 방식으로 Tailwind `theme.extend`에 정의한다. CSS 변수는 쓰지 않는다.

### 3.1 중립

| 용도 | 값 |
|---|---|
| 주요 버튼·활성 탭 | `slate-900` #0f172a (hover `slate-800`) |
| 페이지 배경 | `slate-50` #f8fafc (`page`) |
| 패널 배경 | `slate-100` #f1f5f9 (`panel`) |
| 사이드바 | `slate-950` #020617 |
| 테두리 | `slate-200` #e2e8f0 |
| 본문 텍스트 | `slate-900`, 보조 `slate-500`, 메타 `slate-400` |

### 3.2 의미 팔레트 — 4층 구조 `{ DEFAULT, bg, border, text }`

색은 **편차에만** 쓴다. 정상 상태·일반 정보에는 색을 쓰지 않는다.

```js
risk: {
  high:   { DEFAULT: '#a74541', bg: '#fff0ee', border: '#eaaba5', text: '#83312e' }, // 상 · 사고
  medium: { DEFAULT: '#a1762c', bg: '#fdf5e6', border: '#e3c68d', text: '#7a5511' }, // 중
  low:    { DEFAULT: '#3f7a5c', bg: '#eef7f2', border: '#a9d3bd', text: '#2a5c43' }, // 하
},
pending:  { DEFAULT: '#cd9b3c', bg: '#fff6e7', border: '#ebca93', text: '#8e5400' }, // 되묻기 슬롯 · 승인 대기
progress: { DEFAULT: '#356697', bg: '#eef6fe', border: '#a0c0e1', text: '#24527f' }, // 도구 실행 중 · 활성 네비 · 연결 강조
```

`risk.high`·`risk.medium`·`pending`·`progress`는 MetalFlow `verdict.nok`·`verdict.concession`·`warning`·`progress`와 동일 값이다.
`risk.low`만 신규(같은 채도 기준으로 맞춘 녹색). 사고 이벤트는 별도 색 없이 `risk.high`를 공유한다.

### 3.3 형태

- 반경: `sm 6px`, `md 8px`, `lg 10px`, `xl 12px`. `rounded-2xl` 이상 금지.
- 그림자: `card: 0 1px 3px rgba(15,23,42,0.06)`, `modal`, `toast` 3종만. 페이지 요소에 `shadow-lg` 금지.
- 배지: 각진 태그 `rounded px-1.5 py-0.5 text-xs font-bold`. 알약(`rounded-full`) 금지. 상태 점(dot)만 예외.
- 금지: 그라데이션, `backdrop-blur`(Modal 배경 제외), 이모지, 장식용 아이콘, 임의 픽셀 클래스(`text-[10px]`, `-left-[31px]` 등).

### 3.4 타이포 — "무대 밀도"

Pretendard Variable을 `pretendard` npm 패키지에서 **자체 호스팅**한다(`index.css`에서 `@import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css"`). CDN은 무대 오프라인 폴백 시 깨지므로 쓰지 않는다.

MetalFlow 콘솔 밀도(본문 12~13px)는 프로젝터에 작다. SAIFE는 한 단계 큰 밀도를 규약으로 둔다.

| 역할 | 클래스 |
|---|---|
| 페이지 제목 | `text-xl font-bold text-slate-900` |
| 페이지 설명 | `text-sm text-slate-500` |
| 섹션 제목 | `text-base font-semibold text-slate-900` |
| 라벨 | `text-xs font-semibold text-slate-500` |
| 본문 | `text-sm text-slate-900` |
| 메타 | `text-xs text-slate-400` |
| 트레이스 패널·타임라인 본문 | 토큰 `text-stage` (15px, fontSize 확장으로 등록) |

표 셀 `px-3 py-2`, 카드 `p-4`, 페이지 컨테이너 `max-w-[1600px] px-6 pt-6 pb-4`.

## 4. 레이아웃 셸

```
<div class="flex min-h-screen bg-page">
  <GlobalSidebar />        // aside, sticky, bg-slate-950, w-56 / 접힘 w-16
  <main class="flex-1 min-w-0 isolate">
    <RouteErrorBoundary resetKey={pathname}>
      <Suspense fallback={<PageSkeleton/>}>
        <Routes> … </Routes>
      </Suspense>
    </RouteErrorBoundary>
  </main>
  <ToastContainer />
</div>
```

- 메뉴 4개: 작업계획서(`/work-plan`, 기본 랜딩), 사진 판독(`/vision`), 사고 등록(`/incident`), 설비 타임라인(`/timeline`). lucide 아이콘 각 1개(`ClipboardList`, `Camera`, `Siren`, `History`). `UC#` 꼬리표는 제거한다.
- 활성 항목: `bg-slate-800/70` + 좌측 `w-1 h-5 bg-progress` 바 + 아이콘 `text-progress-border`.
- 접힘 상태는 zustand `uiStore`에 두고 localStorage `saife.sidebar-collapsed`에 유지. **작업계획서 화면은 접힘을 기본**으로 하되 사용자가 바꾸면 그 값을 따른다.
- 사이드바 상단: 워드마크 `SAIFE` + 한 줄 태그라인(접힘 시 숨김). 하단: 가상 사업장명(시드값) 표시.
- 모바일 드로어는 MetalFlow 것을 그대로 두되 우선순위는 낮다.
- 페이지: `PageLayout > PageHeader(title, description, actions) > 본문`.

## 5. 이식 목록

### 5.1 `components/ui/`

`cn`(`lib/cn.ts`), Button, Input, Select, Textarea(Input 스타일로 신규), FormField, Badge(+ `RiskBadge`, `StatusBadge`),
Modal, ConfirmModal, ToastContainer(+ `stores/useToastStore`), EmptyState, TabBar, SegmentedControl, PageLayout, PageHeader,
DataTable, KpiCell, Skeleton, PageSkeleton, InfoTooltip, RefreshButton, SseConnectionStatus.

SAIFE 신규 프리미티브(2페이지 이상 공유): `Card`(제목·부제·우측 액션 슬롯), `SectionTitle`, `Callout`(tone: `high|medium|low|pending|progress|neutral` — 사고 소환 배너·슬롯 카드·데모 모드 안내에 공용).

### 5.2 `components/layout/`, `components/common/`

GlobalSidebar(SAIFE 메뉴로 재구성, 권한·동기화·언어 제거), MobileHeader, RouteErrorBoundary, SectionErrorBoundary.

### 5.3 훅·유틸·스토어

- `api/client.ts`: axios 인스턴스(60초 타임아웃, 인터셉터 골격) + `fetchWithAuth` + `ApiError`. 인증 헤더는 지금 없으므로 훅 지점만 남긴다.
- `api/endpoints.ts`, `api/{equipment,workPlan,incident,vision,timeline}Api.ts`(현 `saifeApi.ts` 분리).
- `utils/abortRegistry.ts`, `utils/errorMessage.ts`, `utils/sseStream.ts`, `utils/sseGuards.ts`, `utils/datetime.ts`(dayjs, `Asia/Seoul` 고정, `formatDate`/`formatDateTime`/`dDay`).
- `hooks/useApiData.ts`, `hooks/useSSEStream.ts`, `hooks/useDebounce.ts`, `hooks/useConfirm.ts`, `hooks/useBreakpoint.ts`, `hooks/useFormKeyboardNav.ts`.
- `stores/uiStore.ts`, `stores/useToastStore.ts`.
- `utils/statusColors.ts`: `riskColor(level)`, `workPlanStatusColor(status)`, `eventTypeColor(type)` — 색 매핑은 이 파일 한 곳.

### 5.4 SSE 3계층 전환

규칙 문서 `.claude/rules/sse-streaming.md`의 "소비자가 늘면 분리한다" 조건이 충족됐다(에이전트 + 비전).

| 계층 | 파일 |
|---|---|
| 프레임 파서 (React 무관) | `utils/sseStream.ts` `readSseStream(response, signal)` |
| 봉투 파싱 훅 | `hooks/useSSEStream.ts` (`handlers` 주입, 하트비트 60초, `connectionState`) |
| 도메인 훅 | `pages/WorkPlan/hooks/useAgentStream.ts`, `pages/Vision/hooks/useVisionStream.ts` |

도메인 훅의 공개 시그니처(`send`, `answerSlot`, `reset`, `analyze`, `applyDecision` 등)와 sessionStorage 키 `saife.conversationId`, 전사 복원 동작은 유지한다. seq 역행 중복 제거 규칙도 유지한다. `useSSEStream`의 재연결 백오프는 **끈다**(POST 스트림은 재연결이 곧 재요청이라 부작용이 있음). 규칙 문서의 프론트엔드 절을 3계층으로 갱신한다.

### 5.5 테스트

vitest + jsdom + Testing Library를 devDependency로 추가하고 `npm run test`를 등록한다. MetalFlow의 테스트 중 이식 대상 컴포넌트·훅에 해당하는 것(Button, Badge, Modal, DataTable, Input, useApiData, useSSEStream, sseStream, abortRegistry)을 같이 가져와 SAIFE 문자열로 맞춘다.

## 6. 폴더 규칙 (`frontend/src/`)

```
api/          client.ts endpoints.ts {domain}Api.ts
components/   ui/  layout/  common/
hooks/  lib/  stores/  types/  utils/
pages/{Vision,WorkPlan,Incident,Timeline}/
  {Domain}Page.tsx   index.tsx   {Domain}Skeleton.tsx
  components/        (해당 페이지 전용)
  hooks/             (fetch·SSE·로직. UI 컴포넌트 안에서 fetch 금지)
```

규칙 문서를 `frontend/.claude/rules/`에 둔다: `design-system.md`(토큰·금지 목록·밀도), `ui-styling.md`(타이포·컴포넌트 사용법),
`architecture.md`(폴더·훅 계층·lazy·Skeleton 필수). MetalFlow 문서를 옮기되 SAIFE에 없는 항목(권한·i18n·콘솔)은 뺀다.
루트 `CLAUDE.md`의 프로젝트 구조 절에 이 규칙 파일들을 링크한다.

## 7. 화면별 재작성 요점

공통: 페이지 헤더는 `PageHeader`. 로딩은 `{Domain}Skeleton`. 오류는 토스트 + 인라인 `Callout`. 빈 상태는 `EmptyState`.

**작업계획서 (`/work-plan`)** — 2열 `grid-cols-[minmax(0,1fr)_360px]`. 좌: 대화 스레드(사용자 말풍선은 `bg-slate-100`, 에이전트 말풍선은 `bg-white border`), 슬롯 질문은 `Callout tone="pending"` + `SegmentedControl`(options 있을 때)/`Input`, 입력창은 `Textarea` + `Button`. 아래 `DataTable`(작업명·일자·설비·상태 `StatusBadge`·브리핑·열기). 상세는 `Modal` 안에 슬롯 표·브리핑 섹션 카드·확인/승인 `Button`. 우: `ToolTracePanel`(6행, `text-stage`, 실행 중 점은 `bg-progress` + pulse, 완료 `bg-slate-400`, 실패 `bg-risk-high`).

**사고 등록 (`/incident`)** — 폼은 `Card` 안 3열 `FormField` 격자, 제출은 `Button variant="danger"`. 결과 순서: ① 소환 배너 `Callout tone="high"`(예고된 사고일 때. 아니면 `neutral`) ② `KpiCell` 3개(제출 기한 D-day `threshold`, 휴업일수, 수시평가 번호) ③ 설비 사전 이력 카드·수시평가 카드 2열 ④ 조사표 초안 `Card` + 법정 서식 링크. 마지막 사고 목록 `DataTable`.

**설비 타임라인 (`/timeline`)** — 상단 `Select`(설비) + 요약 `Card`(현재 등급 `RiskBadge`, 헤드라인, `KpiCell` 5개). 이벤트는 좌측 세로선 위에 카드: 날짜 `text-xs text-slate-400`, 타입 태그(`Badge` 무채색), 제목 `text-stage font-semibold`, 강조도만 점 색(`risk.high`/`pending`/`slate-300`). 선택 이벤트는 `ring-2 ring-progress-border`, 연결 이벤트는 `border-progress-border bg-progress-bg`.

**사진 판독 (`/vision`)** — 2열 `lg:grid-cols-[360px_minmax(0,1fr)]`. 좌: `Select`(설비) + 업로드 영역(점선 카드, 미리보기) + `Button`. 우: 진행 `Callout tone="progress"`, 후보 카드 목록(각: `RiskBadge` + 축 태그 + 근거 + 룰 트레이스 `text-xs font-mono bg-panel` — 등급 옆에 항상 표시) + 채택/기각/되돌리기 `Button size="sm"`. 채택률은 `KpiCell` 1개. 데모 모드 안내는 `Callout tone="neutral"`.

`AgentMessage`(마크다운 라이트)는 `components/common/`으로 옮겨 유지한다.

## 8. 오류·상태 처리

- REST 실패: `useApiData`의 `error` → 인라인 `Callout tone="high"` + 토스트. 서버 메시지는 `getServerMessage`.
- SSE 실패: `ai.error`/`assess.failed` payload → `Callout` + 토스트. 하트비트 60초 끊김 → `SseConnectionStatus` 표시.
- 중단: 페이지 이탈 시 `abortAllRequests()`가 아니라 도메인 훅의 `abort()`만 호출(대화 자체는 서버에 보존).
- 화면 크래시: `RouteErrorBoundary`가 경로 변경 시 자동 복구.

## 9. 작업 순서 (계획 문서에서 상세화)

1. 의존성·설정: vitest, pretendard 자체 호스팅, tailwind 토큰, `cn`, 규칙 문서.
2. 프리미티브 이식 + 테스트 통과.
3. 셸(사이드바·라우팅·에러 바운더리·토스트).
4. 인프라 계층(api·SSE 3계층·훅·유틸) + 도메인 훅 재작성.
5. 화면 4개 재작성 (순서: 작업계획서 → 사고 등록 → 타임라인 → 사진 판독. UC3 완성도 우선).
6. 미사용 의존성 정리(recharts 제거 검토), 스크린샷 검증(1280×720), 규칙 문서 갱신.

## 10. 검증

- `npm run lint && npm run check:types && npm run test && npm run build`
- 백엔드 데모 모드로 기동 후 4화면 스모크: UC3 대화 1회 완주(도구 6개 점등·슬롯 되묻기), 사고 등록 1건, 타임라인 설비 1개, 사진 판독 1장.
- 1280×720 스크린샷 4장을 `.gstack/qa-reports/screenshots/redesign-*.png`로 저장해 이전 스크린샷과 비교.

## 11. 구현 중 확정된 변경 (2026-09-23)

- (a) §5.4/§8의 60초 하트비트 감시는 구현하지 않는다 — 스트림이 전부 POST라 재연결이 재요청이고, 백엔드 `AgentService`가 `finally`에서 emitter를 항상 닫는다. `ConnectionState.disconnected`는 향후 확장용으로만 남긴다.
- (b) §7 작업계획서 말풍선: 사용자 `bg-slate-900 text-white`, 에이전트 `bg-slate-50 border`.
- (c) §3.4 카드 밖 섹션 제목(`SectionTitle`)은 `text-sm font-semibold text-slate-600`. `text-base … slate-900`은 `Card` 제목에 적용한다.
- (d) §5.3 `workPlanStatusColor`/`eventTypeColor` 대신 `workPlanStatusTone`/`emphasisTone`/`reportDutyTone`가 톤을 정하고, 타입 태그는 무채색이다.
- (e) §3.3 등급 '하'의 녹색, 사이드바 접힘 기본값 `/work-plan`은 사용자 확정 사항이다.
- (f) §8: REST 실패 시 페이지 상단 `Callout tone="high"` + 토스트(`errorMessage`).
