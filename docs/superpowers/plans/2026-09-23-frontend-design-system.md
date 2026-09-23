# SAIFE 프론트엔드 디자인 시스템 이식 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MetalFlow의 디자인 토큰·`components/ui`·훅·유틸을 SAIFE 맞춤으로 이식하고, 그 위에 4화면(작업계획서·사고 등록·설비 타임라인·사진 판독)을 재작성한다.

**Architecture:** `frontend/src/`를 MetalFlow의 feature-sliced 구조(`api/ components/{ui,layout,common} hooks/ lib/ stores/ types/ utils/ pages/{Domain}/`)로 재편한다. 색은 `tailwind.config.js`의 `risk/pending/progress` 4층 토큰만 쓰고, 상태→클래스 매핑은 `utils/statusColors.ts` 한 곳에 둔다. SSE는 파서(`utils/sseStream`) → 봉투 훅(`hooks/useSSEStream`) → 도메인 훅(`pages/*/hooks`) 3계층으로 나눈다.

**Tech Stack:** React 19, TypeScript 5.9, Vite 7, Tailwind 3.4, axios, zustand 5, dayjs, lucide-react, vitest 4 + Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-23-frontend-design-system-design.md`

**원천 경로(읽기 전용):** `C:\Users\taeli\metalflow-platform\frontend\src` — 아래에서 `MF/`로 줄여 쓴다. 작업 경로는 전부 `C:\Users\taeli\SAIFE\saife\frontend` 기준이다. 커밋은 `C:\Users\taeli\SAIFE\saife`(git 루트)에서 한다.

## Global Constraints

- 백엔드는 건드리지 않는다. API 경로·DTO·SSE 이벤트(`ai.token` `ai.done` `ai.error` `ai.tool.start` `ai.tool.done` `ai.slot.request` `assess.progress` `assess.done` `assess.failed` `system.heartbeat`)는 그대로다.
- 색 hex는 `tailwind.config.js`에만 존재한다. 페이지·컴포넌트 `className`에 hex·`red-*`·`amber-*`·`emerald-*`·`gray-*`를 쓰지 않는다(중립은 `slate-*`).
- 반경은 `rounded`·`rounded-md`·`rounded-lg`·`rounded-xl`까지. `rounded-2xl` 이상·그라데이션·이모지·`shadow-lg`(모달·토스트 제외)·`backdrop-blur`(Modal 배경 제외) 금지.
- 폰트는 `pretendard` npm 패키지에서 자체 호스팅. CDN `<link>` 금지.
- i18n·MUI·권한 프레임워크·FilterBar·Pagination 컴포넌트·페이지 프리로드·버전 감시는 이식하지 않는다. MetalFlow 코드의 `useTranslation`/`t()`는 한국어 문자열로 치환한다.
- 소스 주석 한국어. 커밋은 Conventional Commits + 한국어 본문 + `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- 모든 태스크 끝에 `npm run lint && npm run check:types && npm run test` 통과. 마지막 태스크에서 `npm run build`까지.
- `import`는 `@/` 별칭. 컴포넌트는 default export(Badge 계열만 named).
- 트레이스 패널은 6행 기준·`text-stage`(15px). 등급 배지 옆에는 항상 룰 트레이스가 같이 보인다.

## Review Focus

1. **SSE 응답이 `text/event-stream`이 아닌 HTTP 4xx/5xx로 오는 경우** — 스트림 파싱이 아니라 `fetchWithAuth`가 던진 오류가 `error` 상태·토스트로 보여야 한다(무한 "처리 중" 금지). → Task 6 `useSSEStream` 테스트 "non-ok 응답이면 error 상태".
2. **되묻기(`ai.slot.request`) 후 스트림이 서버에서 닫힐 때** — `streaming`이 false로 내려가고 `pendingSlot`은 유지돼야 한다. → Task 9 `useAgentStream` 테스트.
3. **새로고침 후 대화 복원 중 사용자가 보내기** — `restoring` 동안 입력이 막히지 않아도 되지만 복원 결과가 새 턴을 덮어쓰면 안 된다. → Task 9 테스트 "복원 응답이 send 이후 도착해도 turns를 덮지 않는다".
4. **사고 등록에서 휴업일수 빈 문자열** — `null`로 보내야 한다(0 금지). → Task 11 `toRegisterRequest` 테스트.
5. **datetime-local 값의 오프셋** — 브라우저 오프셋을 붙여 보내야 법정 기한이 밀리지 않는다. → Task 7 `toOffsetIso` 테스트.

---

## 파일 구조 (최종)

```
frontend/
  package.json vite.config.ts tailwind.config.js index.html
  .claude/rules/{design-system,ui-styling,architecture}.md   (신규)
  src/
    main.tsx  App.tsx  styles/index.css  test/setup.ts
    lib/cn.ts
    api/client.ts endpoints.ts equipmentApi.ts workPlanApi.ts incidentApi.ts visionApi.ts timelineApi.ts formUrl.ts
    types/common.ts domain.ts sse.ts equipment.ts incident.ts timeline.ts vision.ts workPlan.ts   (domain.ts에서 RISK_CLASS 제거, sse.ts에 ConnectionState 추가)
    utils/abortRegistry.ts errorMessage.ts sseStream.ts sseGuards.ts datetime.ts statusColors.ts
    hooks/useApiData.ts useSSEStream.ts useDebounce.ts useConfirm.ts useBreakpoint.ts useFormKeyboardNav.ts
    stores/uiStore.ts useToastStore.ts
    components/ui/{Button,Input,Select,Textarea,FormField,Badge,Modal,ConfirmModal,ToastContainer,EmptyState,TabBar,SegmentedControl,PageLayout,PageHeader,DataTable,KpiCell,Skeleton,PageSkeleton,InfoTooltip,RefreshButton,SseConnectionStatus,Card,SectionTitle,Callout}.tsx
    components/layout/{GlobalSidebar,MobileHeader}.tsx  components/layout/menu.ts
    components/common/{RouteErrorBoundary,SectionErrorBoundary,AgentMessage}.tsx
    pages/WorkPlan/{WorkPlanPage,WorkPlanSkeleton,index}.tsx  hooks/{useAgentStream,useWorkPlans}.ts  components/{ChatThread,SlotPrompt,Composer,WorkPlanTable,WorkPlanDetailModal,ToolTracePanel}.tsx
    pages/Incident/{IncidentPage,IncidentSkeleton,index}.tsx  hooks/useIncident.ts  utils/incidentForm.ts  components/{IncidentForm,IncidentResult,IncidentTable}.tsx
    pages/Timeline/{TimelinePage,TimelineSkeleton,index}.tsx  hooks/useTimeline.ts  components/{TimelineSummary,TimelineList}.tsx
    pages/Vision/{VisionPage,VisionSkeleton,index}.tsx  hooks/{useVisionStream,useVision}.ts  components/{UploadPanel,CandidateCard}.tsx
```

삭제: `src/api/saifeApi.ts`, `src/hooks/useAgentStream.ts`, `src/hooks/useVisionStream.ts`, `src/components/AgentMessage.tsx`, `src/components/ToolTracePanel.tsx`, `src/pages/*.tsx`(4개 평면 파일).

---

### Task 1: 테스트 인프라 · 폰트 · 디자인 토큰 · `cn`

**Files:**
- Modify: `package.json`, `vite.config.ts`, `tailwind.config.js`, `src/styles/index.css`, `index.html`
- Create: `src/test/setup.ts`, `src/lib/cn.ts`, `src/lib/__tests__/cn.test.ts`

**Interfaces:**
- Produces: `cn(...inputs: ClassValue[]): string` (default + named export). Tailwind 토큰 `risk-{high,medium,low}[-bg|-border|-text]`, `pending[-bg|-border|-text]`, `progress[-bg|-border|-text]`, `bg-page`, `bg-panel`, `text-stage`, `shadow-card|modal|toast`, `rounded-sm|md|lg|xl`, `animate-cursor-blink`.

- [ ] **Step 1: 의존성 추가·정리**

```bash
cd C:/Users/taeli/SAIFE/saife/frontend
npm i -D vitest@^4.1.8 jsdom@^29.1.1 @testing-library/react@^16.3.2 @testing-library/jest-dom@^6.9.1
npm uninstall recharts
```

`package.json` `scripts`에 추가:
```json
"test": "vitest run",
"test:watch": "vitest"
```

- [ ] **Step 2: `vite.config.ts`를 vitest 설정으로 교체**

```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      // SSE를 쓰므로 프록시가 버퍼링하지 않도록 주의한다
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      // 법정 서식은 백엔드가 그린 HTML을 새 탭에서 연다
      "/form": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: false,
    exclude: ["**/node_modules/**", "**/dist/**"],
  },
});
```

- [ ] **Step 3: `src/test/setup.ts` 작성**

```ts
// Vitest 전역 셋업 — jest-dom matcher 등록 + 각 테스트 후 DOM 정리.
import "@testing-library/jest-dom/vitest";

import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

// jsdom은 matchMedia를 구현하지 않는다 — useBreakpoint가 마운트 시 호출하므로 스텁 제공.
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }) as unknown as MediaQueryList;
}

afterEach(() => {
  cleanup();
});
```

- [ ] **Step 4: 실패하는 `cn` 테스트 작성** — `src/lib/__tests__/cn.test.ts`

```ts
import { describe, expect, it } from "vitest";

import cn from "@/lib/cn";

describe("cn", () => {
  it("뒤에 온 같은 계열 클래스가 앞을 덮는다(tailwind-merge)", () => {
    expect(cn("px-3 py-2", "px-5")).toBe("py-2 px-5");
  });

  it("falsy 값은 버린다(clsx)", () => {
    expect(cn("a", false && "b", undefined, "c")).toBe("a c");
  });
});
```

- [ ] **Step 5: 실행해 실패 확인**

Run: `npm run test -- src/lib`
Expected: FAIL — `Cannot find module '@/lib/cn'`

- [ ] **Step 6: `src/lib/cn.ts` 작성**

```ts
// clsx + tailwind-merge 래퍼. className 병합은 이 함수로만 한다(외부 className은 마지막 인자).
import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export default cn;
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `npm run test -- src/lib`
Expected: PASS (2 tests)

- [ ] **Step 8: `tailwind.config.js` 토큰 정의**

```js
/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // 중립 표면
        page: "#f8fafc",
        panel: "#f1f5f9",
        // ── 의미 팔레트(4층: DEFAULT/bg/border/text) ─────────────────────
        // 색은 편차에만 쓴다. 정상 상태·일반 정보는 무채색(slate).
        // high/medium/pending/progress 값은 MetalFlow OKLCH 팔레트(채도 2/3)와 동일하다.
        risk: {
          high:   { DEFAULT: "#a74541", bg: "#fff0ee", border: "#eaaba5", text: "#83312e" }, // 등급 상 · 사고
          medium: { DEFAULT: "#a1762c", bg: "#fdf5e6", border: "#e3c68d", text: "#7a5511" }, // 등급 중
          low:    { DEFAULT: "#3f7a5c", bg: "#eef7f2", border: "#a9d3bd", text: "#2a5c43" }, // 등급 하
        },
        // 되묻기 슬롯 · 승인 대기 — 합불 축이 아니다. 눈에 걸려야 하는 것.
        pending:  { DEFAULT: "#cd9b3c", bg: "#fff6e7", border: "#ebca93", text: "#8e5400" },
        // 도구 실행 중 · 활성 네비 · 연결 강조 — "지금 여기".
        progress: { DEFAULT: "#356697", bg: "#eef6fe", border: "#a0c0e1", text: "#24527f" },
      },
      fontFamily: {
        // 앞의 가변 폰트가 번들 자체 호스팅분(src/styles/index.css @import). 뒤는 OS 폴백.
        sans: ['"Pretendard Variable"', "Pretendard", "system-ui", '"Apple SD Gothic Neo"', '"Noto Sans KR"', "sans-serif"],
        mono: ["ui-monospace", "Menlo", "monospace"],
      },
      fontSize: {
        // 무대 밀도 — 프로젝터에서 읽혀야 하는 트레이스 패널·타임라인 본문 전용.
        stage: ["15px", { lineHeight: "1.5" }],
      },
      borderRadius: { sm: "6px", md: "8px", lg: "10px", xl: "12px" },
      boxShadow: {
        card: "0 1px 3px rgba(15,23,42,0.06)",
        modal: "0 20px 50px rgba(0,0,0,0.25)",
        toast: "0 8px 20px rgba(0,0,0,0.2)",
      },
      keyframes: {
        "cursor-blink": { "0%, 100%": { opacity: "1" }, "50%": { opacity: "0" } },
      },
      animation: {
        "cursor-blink": "cursor-blink 0.8s step-end infinite",
      },
    },
  },
  plugins: [],
};
```

- [ ] **Step 9: `src/styles/index.css` 교체**

```css
/* 웹폰트 — 자체 호스팅(외부 CDN 요청 0). 무대에서 네트워크가 끊겨도 글꼴이 바뀌지 않는다.
   unicode-range 청크 분할이라 화면에 뜬 글자 범위만 내려받는다. font-display: swap 내장. */
@import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";

@tailwind base;
@tailwind components;
@tailwind utilities;

:root {
  /* tailwind.config.js의 sans 체인과 동일하게 유지할 것 */
  font-family: "Pretendard Variable", Pretendard, system-ui, "Apple SD Gothic Neo", "Noto Sans KR", sans-serif;
  line-height: 1.5;
  font-weight: 400;
  color-scheme: light;
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
}
```

- [ ] **Step 10: `index.html` 배경 지정** — `<body>`를 `<body class="bg-page text-slate-900">`로 바꾼다(부트 전 흰 화면 깜빡임 방지).

- [ ] **Step 11: 빌드·린트 확인 후 커밋**

Run: `npm run lint && npm run check:types && npm run test`
Expected: 모두 통과(기존 페이지는 아직 그대로라 타입 통과).

```bash
cd C:/Users/taeli/SAIFE/saife
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts frontend/tailwind.config.js frontend/src/styles/index.css frontend/index.html frontend/src/test frontend/src/lib
git commit -m "chore(frontend): vitest · Pretendard 자체 호스팅 · 디자인 토큰 · cn 유틸

MetalFlow 토큰 구조를 SAIFE 의미(risk/pending/progress)로 이식했다. recharts는 미사용이라 제거.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: 상태색 SSOT · 타입 정리

**Files:**
- Create: `src/utils/statusColors.ts`, `src/utils/__tests__/statusColors.test.ts`
- Modify: `src/types/domain.ts` (RISK_CLASS 제거), `src/types/sse.ts` (ConnectionState 추가)

**Interfaces:**
- Produces:
  ```ts
  export type Tone = "high" | "medium" | "low" | "pending" | "progress" | "neutral";
  export interface ToneClasses { chip: string; text: string; bg: string; border: string; solid: string; ring: string }
  export function toneColor(tone: Tone): ToneClasses;
  export function riskTone(level: RiskLevel): Tone;            // HIGH→high MEDIUM→medium LOW→low
  export function riskColor(level: RiskLevel): ToneClasses;
  export function workPlanStatusTone(status: WorkPlanStatus): Tone; // SUBMITTED→pending, REJECTED→high, 나머지 neutral
  export function emphasisTone(e: Emphasis): Tone;              // CRITICAL→high WARNING→pending NORMAL→neutral
  export function reportDutyTone(status: ReportStatus): Tone;   // OVERDUE→high REQUIRED→pending 나머지 neutral
  ```
- `types/sse.ts`에 `export type ConnectionState = "idle" | "connecting" | "connected" | "disconnected" | "error";`

- [ ] **Step 1: 실패 테스트** — `src/utils/__tests__/statusColors.test.ts`

```ts
import { describe, expect, it } from "vitest";

import { emphasisTone, reportDutyTone, riskColor, toneColor, workPlanStatusTone } from "@/utils/statusColors";

describe("statusColors — 색 매핑 SSOT", () => {
  it("등급 상은 risk-high 연톤 칩이다", () => {
    expect(riskColor("HIGH").chip).toBe("bg-risk-high-bg text-risk-high-text border-risk-high-border");
  });

  it("neutral은 slate 무채색이다(정상 상태는 색을 갖지 않는다)", () => {
    expect(toneColor("neutral").chip).toBe("bg-slate-100 text-slate-700 border-slate-200");
  });

  it("승인 대기는 pending, 반려는 high, 승인은 neutral", () => {
    expect(workPlanStatusTone("SUBMITTED")).toBe("pending");
    expect(workPlanStatusTone("REJECTED")).toBe("high");
    expect(workPlanStatusTone("APPROVED")).toBe("neutral");
  });

  it("타임라인 강조도 CRITICAL→high, WARNING→pending", () => {
    expect(emphasisTone("CRITICAL")).toBe("high");
    expect(emphasisTone("WARNING")).toBe("pending");
    expect(emphasisTone("NORMAL")).toBe("neutral");
  });

  it("제출 기한 OVERDUE→high, REQUIRED→pending", () => {
    expect(reportDutyTone("OVERDUE")).toBe("high");
    expect(reportDutyTone("REQUIRED")).toBe("pending");
    expect(reportDutyTone("SUBMITTED")).toBe("neutral");
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- statusColors` → `Cannot find module`

- [ ] **Step 3: `src/utils/statusColors.ts` 작성**

```ts
// 상태색 매핑의 단일 소스(SSOT). 색 리터럴을 쓰지 않고 tailwind 토큰(risk/pending/progress)
// 유틸리티 클래스만 반환한다. 컴포넌트에서 상태→클래스를 직접 분기하지 않는다.
//
// 색 정책: 색은 편차에만 쓴다. 정상·일반 정보는 무채색(neutral).
import type { ReportStatus, RiskLevel, WorkPlanStatus } from "@/types/domain";
import type { Emphasis } from "@/types/timeline";

export type Tone = "high" | "medium" | "low" | "pending" | "progress" | "neutral";

export interface ToneClasses {
  /** 연톤 칩(배경+글자+테두리) — 배지·태그 */
  chip: string;
  /** 글자만 */
  text: string;
  /** 연톤 배경만 — 카드·행에 색조만 입힐 때 */
  bg: string;
  /** 테두리만 */
  border: string;
  /** 솔리드 배경 — 상태 점·버튼 */
  solid: string;
  /** 포커스·선택 링 */
  ring: string;
}

const TONES: Record<Tone, ToneClasses> = {
  high: {
    chip: "bg-risk-high-bg text-risk-high-text border-risk-high-border",
    text: "text-risk-high-text",
    bg: "bg-risk-high-bg",
    border: "border-risk-high-border",
    solid: "bg-risk-high",
    ring: "ring-risk-high-border",
  },
  medium: {
    chip: "bg-risk-medium-bg text-risk-medium-text border-risk-medium-border",
    text: "text-risk-medium-text",
    bg: "bg-risk-medium-bg",
    border: "border-risk-medium-border",
    solid: "bg-risk-medium",
    ring: "ring-risk-medium-border",
  },
  low: {
    chip: "bg-risk-low-bg text-risk-low-text border-risk-low-border",
    text: "text-risk-low-text",
    bg: "bg-risk-low-bg",
    border: "border-risk-low-border",
    solid: "bg-risk-low",
    ring: "ring-risk-low-border",
  },
  pending: {
    chip: "bg-pending-bg text-pending-text border-pending-border",
    text: "text-pending-text",
    bg: "bg-pending-bg",
    border: "border-pending-border",
    solid: "bg-pending",
    ring: "ring-pending-border",
  },
  progress: {
    chip: "bg-progress-bg text-progress-text border-progress-border",
    text: "text-progress-text",
    bg: "bg-progress-bg",
    border: "border-progress-border",
    solid: "bg-progress",
    ring: "ring-progress-border",
  },
  neutral: {
    chip: "bg-slate-100 text-slate-700 border-slate-200",
    text: "text-slate-600",
    bg: "bg-slate-50",
    border: "border-slate-200",
    solid: "bg-slate-400",
    ring: "ring-slate-400",
  },
};

export function toneColor(tone: Tone): ToneClasses {
  return TONES[tone];
}

const RISK_TONE: Record<RiskLevel, Tone> = { HIGH: "high", MEDIUM: "medium", LOW: "low" };

export function riskTone(level: RiskLevel): Tone {
  return RISK_TONE[level];
}

export function riskColor(level: RiskLevel): ToneClasses {
  return TONES[RISK_TONE[level]];
}

/** 작업계획서 상태 — 승인 대기만 눈에 걸리고, 반려만 이탈이다. 나머지는 무채색. */
export function workPlanStatusTone(status: WorkPlanStatus): Tone {
  if (status === "SUBMITTED") return "pending";
  if (status === "REJECTED") return "high";
  return "neutral";
}

/** 타임라인 강조도 — 백엔드가 계산한 emphasis를 그대로 색으로 옮긴다. */
export function emphasisTone(emphasis: Emphasis): Tone {
  if (emphasis === "CRITICAL") return "high";
  if (emphasis === "WARNING") return "pending";
  return "neutral";
}

/** 조사표 제출 의무 — 기한 경과는 이탈, 제출 필요는 주시. */
export function reportDutyTone(status: ReportStatus): Tone {
  if (status === "OVERDUE") return "high";
  if (status === "REQUIRED") return "pending";
  return "neutral";
}
```

- [ ] **Step 4: `src/types/domain.ts`에서 `RISK_CLASS` 블록 삭제** (`export const RISK_CLASS ... };` 7줄). 기존 페이지가 참조하므로 타입 검사는 Task 13까지 실패한다 — 이 태스크에서는 `npm run test`만 통과시킨다.

- [ ] **Step 5: `src/types/sse.ts` 끝에 추가**

```ts
/** 스트림 연결 상태 — useSSEStream이 관리한다. 재연결은 없다(POST 스트림은 재연결이 곧 재요청). */
export type ConnectionState = "idle" | "connecting" | "connected" | "disconnected" | "error";

/** 에러 이벤트 표준 payload(ai.error · assess.failed 공통, sse-streaming.md) */
export interface SseErrorPayload {
  errorType?: string;
  message: string;
  detail?: string;
}
```

- [ ] **Step 6: 테스트 통과 · 커밋**

Run: `npm run test -- statusColors` → PASS

```bash
git add frontend/src/utils frontend/src/types
git commit -m "feat(frontend): 상태색 SSOT statusColors · ConnectionState 타입

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: UI 프리미티브 1 — Button · Input · Select · Textarea · FormField · Badge

**Files:**
- Create: `src/components/ui/Button.tsx`, `Input.tsx`, `Select.tsx`, `Textarea.tsx`, `FormField.tsx`, `Badge.tsx`, `__tests__/Button.test.tsx`, `__tests__/Badge.test.tsx`
- 원천: `MF/components/ui/{Button,Input,Select,FormField}.tsx`

**Interfaces:**
- `Button` props: `variant?: "primary"|"secondary"|"danger"|"ghost"|"subtle"|"link"`, `size?: "sm"|"md"|"lg"`, `loading?: boolean` + 네이티브 button 속성. forwardRef.
- `Input` props: 네이티브 + `error?: boolean`, `dense?: boolean`. `Select` 동일. `Textarea`: 네이티브 textarea + `error?`.
- `FormField` props: `label?, required?, error?: string, hint?: string, children, className?`.
- `Badge` (named): `variant?: "neutral"|Tone`, `children`, `className?`. `RiskBadge({ level: RiskLevel, className? })` → "상/중/하". `StatusBadge({ tone: Tone, children })`.

- [ ] **Step 1: 실패 테스트** — `src/components/ui/__tests__/Button.test.tsx`

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import Button from "@/components/ui/Button";

describe("Button", () => {
  it("loading이면 비활성화되고 라벨이 '처리 중…'으로 바뀐다", () => {
    render(<Button loading>보내기</Button>);
    const btn = screen.getByRole("button");
    expect(btn).toBeDisabled();
    expect(btn).toHaveTextContent("처리 중…");
  });

  it("danger 변형은 risk-high 솔리드다", () => {
    render(<Button variant="danger">사고 등록</Button>);
    expect(screen.getByRole("button").className).toContain("bg-risk-high");
  });
});
```

`src/components/ui/__tests__/Badge.test.tsx`

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { RiskBadge, StatusBadge } from "@/components/ui/Badge";

describe("Badge", () => {
  it("RiskBadge는 등급 라벨과 risk 토큰 칩을 그린다", () => {
    render(<RiskBadge level="HIGH" />);
    const el = screen.getByText("상");
    expect(el.className).toContain("bg-risk-high-bg");
    expect(el.className).not.toContain("rounded-full");
  });

  it("StatusBadge는 tone 칩을 그린다", () => {
    render(<StatusBadge tone="pending">승인 대기</StatusBadge>);
    expect(screen.getByText("승인 대기").className).toContain("bg-pending-bg");
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- components/ui` → 모듈 없음

- [ ] **Step 3: `Button.tsx`** — `MF/components/ui/Button.tsx`를 복사한 뒤 다음을 바꾼다.
  - `import { useTranslation } from "react-i18next";`와 `const { t } = useTranslation("common");` 삭제.
  - `{loading ? t("action.processing") : children}` → `{loading ? "처리 중…" : children}`.
  - `variantStyles.danger`: `"bg-risk-high hover:bg-risk-high-text text-white rounded-lg"` (shadow-sm 제거).
  - `secondary`: `"bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 rounded-lg"`.
  - `ghost`: `"text-slate-500 hover:text-slate-700 hover:bg-slate-100 rounded-lg"`.
  - base 문자열의 `disabled:bg-gray-300` → `disabled:bg-slate-300`.
  - `export default Button;` 유지.

- [ ] **Step 4: `Input.tsx` / `Select.tsx`** — 복사 후 치환: `border-verdict-nok-border focus:border-verdict-nok focus:ring-1 focus:ring-verdict-nok` → `border-risk-high-border focus:border-risk-high focus:ring-1 focus:ring-risk-high`; `border-gray-200` → `border-slate-300`; `disabled:bg-gray-50 disabled:text-gray-400` → `disabled:bg-slate-50 disabled:text-slate-400`. Input의 `noWhitespace` 기능은 그대로 둔다(테스트 없이 이식, 사용 안 함).

- [ ] **Step 5: `Textarea.tsx` 신규**

```tsx
import { forwardRef } from "react";

import cn from "@/lib/cn";

interface TextareaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  error?: boolean;
}

/** Input과 같은 테두리·포커스 규격의 여러 줄 입력. 대화 입력창·재해 경위에 쓴다. */
const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ error = false, className, ...props }, ref) => (
    <textarea
      ref={ref}
      aria-invalid={error || undefined}
      className={cn(
        "w-full border rounded-lg text-sm px-3 py-2 outline-none transition-colors resize-y",
        error
          ? "border-risk-high-border focus:border-risk-high focus:ring-1 focus:ring-risk-high"
          : "border-slate-300 focus:border-slate-500 focus:ring-1 focus:ring-slate-500",
        "disabled:bg-slate-50 disabled:text-slate-400",
        className,
      )}
      {...props}
    />
  ),
);

Textarea.displayName = "Textarea";

export default Textarea;
```

- [ ] **Step 6: `FormField.tsx`** — 복사 후 `text-gray-500` → `text-slate-500`, `text-verdict-nok` → `text-risk-high-text`(2곳). `hint?: string` prop 추가: label 뒤에 `{hint && <span className="ml-1 font-normal text-slate-400">{hint}</span>}`.

- [ ] **Step 7: `Badge.tsx` 신규(전면 재작성)**

```tsx
import cn from "@/lib/cn";
import type { RiskLevel } from "@/types/domain";
import { RISK_LABEL } from "@/types/domain";
import { riskColor, toneColor, type Tone } from "@/utils/statusColors";

// 알약이 아니라 각진 태그 — 연한 배경+테두리+알약 반경은 AI 생성 대시보드의 기본값이라
// 전 화면에 그 인상이 번진다. 형태(반경·타이포)로 dense 콘솔 톤을 유지한다.
const BADGE_BASE =
  "inline-flex items-center whitespace-nowrap text-xs font-bold tracking-wide px-1.5 py-0.5 rounded border";

interface BadgeProps extends Omit<React.ComponentPropsWithoutRef<"span">, "className" | "children"> {
  variant?: Tone;
  children: React.ReactNode;
  className?: string;
}

/** 범용 배지 — 기본은 무채색. 의미가 있는 상태에만 tone을 준다. */
export function Badge({ variant = "neutral", children, className, ...rest }: BadgeProps) {
  return (
    <span {...rest} className={cn(BADGE_BASE, toneColor(variant).chip, className)}>
      {children}
    </span>
  );
}

/** 위험성 등급 배지 — 등급은 룰 엔진이 낸다. 호출부는 옆에 룰 트레이스를 같이 띄운다. */
export function RiskBadge({ level, className }: { level: RiskLevel; className?: string }) {
  return (
    <span className={cn(BADGE_BASE, riskColor(level).chip, className)}>{RISK_LABEL[level]}</span>
  );
}

/** 상태 배지 — tone은 statusColors의 *Tone 함수로만 정한다. */
export function StatusBadge({ tone, children, className }: { tone: Tone; children: React.ReactNode; className?: string }) {
  return <span className={cn(BADGE_BASE, toneColor(tone).chip, className)}>{children}</span>;
}
```

- [ ] **Step 8: 테스트 통과 · 커밋**

Run: `npm run test -- components/ui` → PASS

```bash
git add frontend/src/components/ui
git commit -m "feat(frontend): UI 프리미티브 1 — Button·Input·Select·Textarea·FormField·Badge

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: UI 프리미티브 2 — Modal · ConfirmModal · Toast · useFormKeyboardNav · useConfirm

**Files:**
- Create: `src/hooks/useFormKeyboardNav.ts`, `src/hooks/useConfirm.ts`, `src/stores/useToastStore.ts`, `src/components/ui/Modal.tsx`, `ConfirmModal.tsx`, `ToastContainer.tsx`, `__tests__/Modal.test.tsx`
- 원천: `MF/hooks/useFormKeyboardNav.ts`, `MF/hooks/useConfirm.ts`, `MF/stores/useToastStore.ts`, `MF/components/ui/{Modal,ConfirmModal,ToastContainer}.tsx`, `MF/components/ui/__tests__/Modal.test.tsx`

**Interfaces:**
- `Modal({ isOpen, onClose, onConfirm?, title?, children, footer?, maxWidth?: "sm"|"md"|"lg"|"xl"|"2xl"|"full", closeOnOutsideClick?, className? })`
- `ConfirmModal({ isOpen, onClose, onConfirm, title, message, confirmText?="확인", cancelText?="취소", variant?: "destructive"|"primary" })`
- `useToastStore.getState().{success,error,info,warning}(message, duration?)`
- `useConfirm()` → `{ confirmState, requestConfirm(message, onConfirm, title="확인", isDestructive=false), closeConfirm }`

- [ ] **Step 1: 테스트 복사** — `MF/components/ui/__tests__/Modal.test.tsx`를 그대로 `src/components/ui/__tests__/Modal.test.tsx`에 복사(수정 없음).

- [ ] **Step 2: 실패 확인** — `npm run test -- Modal` → 모듈 없음

- [ ] **Step 3: `useFormKeyboardNav.ts`, `useConfirm.ts`, `useToastStore.ts`** — 원천을 그대로 복사한다(변경 없음. useToastStore는 zustand devtools 미들웨어 포함).

- [ ] **Step 4: `Modal.tsx`** — 복사 후:
  - `"rounded-2xl sm:rounded-2xl"` → `"rounded-xl"`.
  - `"animate-in fade-in zoom-in-95 duration-200",` 줄 삭제(플러그인이 없어 죽은 클래스).
  - 헤더 `h3` 클래스 → `"text-base font-bold text-slate-900 truncate"`.

- [ ] **Step 5: `ConfirmModal.tsx`** — 아래로 재작성(i18n 제거, Button 사용, success 변형 제거).

```tsx
import Button from "@/components/ui/Button";
import Modal from "@/components/ui/Modal";

type ConfirmVariant = "destructive" | "primary";

interface ConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  /** @deprecated variant 사용 */
  isDestructive?: boolean;
  variant?: ConfirmVariant;
}

/** 확인 모달 — useConfirm 훅과 짝으로 쓴다. Enter=확인, ESC=취소는 Modal이 처리한다. */
export default function ConfirmModal({
  isOpen, onClose, onConfirm, title, message,
  confirmText = "확인", cancelText = "취소", isDestructive = true, variant,
}: ConfirmModalProps) {
  const resolved = variant ?? (isDestructive ? "destructive" : "primary");
  const handleConfirm = () => {
    onConfirm();
    onClose();
  };
  const footer = (
    <>
      <Button variant="secondary" onClick={onClose}>{cancelText}</Button>
      <Button variant={resolved === "destructive" ? "danger" : "primary"} onClick={handleConfirm}>
        {confirmText}
      </Button>
    </>
  );
  return (
    <Modal isOpen={isOpen} onClose={onClose} onConfirm={handleConfirm} title={title} footer={footer} maxWidth="sm">
      <div className="py-2 text-sm leading-relaxed text-slate-600">{message}</div>
    </Modal>
  );
}
```

- [ ] **Step 6: `ToastContainer.tsx`** — 복사 후:
  - `useTranslation` import·`const { t } = ...` 삭제.
  - `STYLE_MAP`: success `bg-risk-low`, error `bg-risk-high`, warning `bg-pending`, info `bg-slate-700`.
  - 토스트 본체 클래스 `rounded-xl shadow-lg` → `rounded-lg shadow-toast`.
  - `{t("toast.moreCount", { count: hiddenCount })}` → `{`+${hiddenCount}개 더`}`.
  - `text-gray-400` → `text-slate-400`.

- [ ] **Step 7: 테스트 통과 · 커밋**

Run: `npm run test -- Modal` → PASS (5 tests)

```bash
git add frontend/src/hooks frontend/src/stores frontend/src/components/ui
git commit -m "feat(frontend): Modal·ConfirmModal·Toast·폼 키보드 훅 이식

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: UI 프리미티브 3 — 레이아웃·표·카드 계열

**Files:**
- Create: `src/components/ui/{EmptyState,TabBar,SegmentedControl,PageLayout,PageHeader,DataTable,KpiCell,Skeleton,PageSkeleton,InfoTooltip,RefreshButton,SseConnectionStatus,Card,SectionTitle,Callout}.tsx`, `__tests__/DataTable.test.tsx`, `__tests__/KpiCell.test.tsx`, `__tests__/Callout.test.tsx`
- 원천: `MF/components/ui/` 동명 파일, `MF/components/ui/__tests__/{DataTable,KpiCell}.test.tsx`

**Interfaces:**
- `DataTable<T>({ columns: Column<T>[], data, rowKey, onRowClick?, emptyMessage?, className?, rowClassName?, ... })` — MetalFlow 시그니처 유지. `Column<T> = { key, header, width?, align?, noTruncate?, wrap?, render(row, i) }`.
- `KpiCell({ label, value, subText?, tone?: Tone, onClick?, title?, className? })`
- `Card({ title?, description?, actions?, children, className?, bodyClassName? })`
- `SectionTitle({ children, className? })`
- `Callout({ tone: Tone, title?, children, className? })`
- `PageHeader({ title, description?, actions?, className? })`, `PageLayout({ children, className? })`
- `EmptyState({ message, description?, action?, className? })`, `Skeleton({ className })`, `PageSkeleton()`
- `SseConnectionStatus({ state: ConnectionState })` — disconnected/error일 때만 배지.

- [ ] **Step 1: 테스트** — `MF/.../__tests__/DataTable.test.tsx`를 그대로 복사. `KpiCell.test.tsx`는 복사 후 threshold 관련 3개 테스트를 아래로 교체하고 나머지는 유지:

```tsx
  it("tone=high면 risk-high 연톤 글자색을 적용한다", () => {
    render(<KpiCell label="기한 경과" value={3} tone="high" subText="즉시 조치" />);
    expect(screen.getByText("3").className).toMatch(/text-risk-high-text/);
  });

  it("tone=pending이면 pending 연톤을 적용한다", () => {
    render(<KpiCell label="제출 기한" value="D-30" tone="pending" />);
    expect(screen.getByText("D-30").className).toMatch(/text-pending-text/);
  });

  it("tone 없으면 상태색 클래스가 붙지 않는다", () => {
    render(<KpiCell label="위험성평가" value={12} />);
    expect(screen.getByText("12").className).not.toMatch(/risk-|pending-/);
  });
```

`__tests__/Callout.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import Callout from "@/components/ui/Callout";

describe("Callout", () => {
  it("tone 연톤 배경·테두리를 입히고 제목을 굵게 그린다", () => {
    render(<Callout tone="high" title="이 사고는 예고되어 있었습니다">본문</Callout>);
    const title = screen.getByText("이 사고는 예고되어 있었습니다");
    expect(title.className).toContain("font-semibold");
    expect(title.closest("[role=note]")!.className).toContain("bg-risk-high-bg");
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- components/ui`

- [ ] **Step 3: 단순 복사 + 치환 파일들**
  - `EmptyState.tsx`: 복사. `rounded-xl` → `rounded-lg`, `gray-` → `slate-`. `icon` 기본 SVG는 제거하고 `icon` prop이 있을 때만 그린다.
  - `TabBar.tsx`: 복사. `dark` 변형 분기와 `isDark` 관련 코드·`report-accent` 전부 삭제(`variant?: "default" | "secondary"`). `gray-` → `slate-`.
  - `SegmentedControl.tsx`: 복사(변경 없음).
  - `PageLayout.tsx`: 복사. 내부 클래스 `"w-full max-w-[1600px] flex flex-col px-6 pt-6 pb-4 min-w-0 overflow-x-clip"`; 외곽 `bg-slate-50` → `bg-page`.
  - `PageHeader.tsx`: 복사. `useTranslation`·`backTo`·`backLabel`·`titleSuffix`·`Link`·`ArrowLeft` 전부 삭제. `h1` → `text-xl font-bold text-slate-900`, 설명 `p` → `text-sm text-slate-500 mt-0.5`.
  - `Skeleton.tsx`: 복사, `bg-gray-200` → `bg-slate-200`.
  - `PageSkeleton.tsx`: 복사, 외곽 `"max-w-[1600px] mx-auto px-6 py-6"`.
  - `InfoTooltip.tsx`: 복사(변경 없음).
  - `RefreshButton.tsx`: 복사. i18n 제거, `title={title ?? "새로고침"}`, `gray-` → `slate-`.
  - `SseConnectionStatus.tsx`: 재작성:

```tsx
import { Badge } from "@/components/ui/Badge";
import type { ConnectionState } from "@/types/sse";

/** 문제 구간(disconnected·error)만 알린다. 잘 되고 있을 때는 화면을 어지럽히지 않는다. */
export default function SseConnectionStatus({ state, className }: { state: ConnectionState; className?: string }) {
  if (state !== "disconnected" && state !== "error") return null;
  return (
    <Badge role="status" aria-live="polite" variant="high" className={className}>
      {state === "error" ? "스트림 오류" : "연결 끊김"}
    </Badge>
  );
}
```

- [ ] **Step 4: `DataTable.tsx`** — 복사 후:
  - `useTranslation` 제거. `t("state.emptyData")` 2곳 → `"데이터가 없습니다"`.
  - `gray-` → `slate-` 전역 치환(`hover:bg-gray-50` 포함 — 테스트가 `hover:bg-slate-50`로 바뀌므로 복사한 DataTable 테스트의 `/hover:bg-gray-50/`를 `/hover:bg-slate-50/`로 고친다).
  - 셀 패딩 `"px-3 py-1.5"` → `"px-3 py-2"`(td·th 모두). 래퍼의 `shadow-sm` → `shadow-card`.
  - `progressPercent`·`headerGroups`·`columnDividers`·`fill`·`expandedRowKey`·`renderExpanded`·`onRowMouseEnter`·`rowIdPrefix`·`renderBeforeRows`·`renderAfterRows`·`minWidthClass`·`adaptiveWidth`·`sortKey`·`sortOrder`·`onSort`는 **유지**(테스트가 시그니처를 참조하지 않으므로 지워도 되지만, 지우다 깨뜨릴 위험이 더 크다. 그대로 둔다).

- [ ] **Step 5: `KpiCell.tsx`** — 재작성:

```tsx
import cn from "@/lib/cn";
import { toneColor, type Tone } from "@/utils/statusColors";

interface KpiCellProps {
  label: string;
  value: React.ReactNode;
  subText?: string;
  /** 상태 강조 — 지정 시 값·서브텍스트에 연톤. 색만으로 강조하지 말고 subText를 같이 준다. */
  tone?: Tone;
  onClick?: () => void;
  title?: string;
  className?: string;
  id?: string;
}

/** 요약 수치 셀. onClick이 있으면 button, 없으면 정적 div. */
export default function KpiCell({ label, value, subText, tone, onClick, title, className, id }: KpiCellProps) {
  const t = tone && tone !== "neutral" ? toneColor(tone) : null;
  const cellClassName = cn(
    "bg-white border rounded-md p-3 text-left w-full",
    t ? cn(t.bg, t.border) : "border-slate-200",
    onClick && "cursor-pointer transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-slate-500 focus:ring-offset-1",
    className,
  );
  const content = (
    <>
      <div className="text-xs font-semibold text-slate-500">{label}</div>
      <div className={cn("mt-1 text-2xl font-bold tabular-nums", t ? t.text : "text-slate-900")}>{value}</div>
      {subText && <div className={cn("mt-0.5 text-xs", t ? t.text : "text-slate-400")}>{subText}</div>}
    </>
  );
  if (onClick) {
    return (
      <button type="button" id={id} title={title} onClick={onClick} className={cellClassName}>
        {content}
      </button>
    );
  }
  return (
    <div id={id} title={title} className={cellClassName}>
      {content}
    </div>
  );
}
```

- [ ] **Step 6: `Card.tsx`, `SectionTitle.tsx`, `Callout.tsx` 신규**

```tsx
// Card.tsx
import cn from "@/lib/cn";

interface CardProps {
  title?: React.ReactNode;
  description?: string;
  /** 제목 줄 우측 슬롯 — 버튼·링크 */
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  bodyClassName?: string;
}

/** 흰 배경 + slate 테두리 카드. 그림자는 card 하나만, 반경은 lg. */
export default function Card({ title, description, actions, children, className, bodyClassName }: CardProps) {
  const hasHeader = title || description || actions;
  return (
    <section className={cn("bg-white border border-slate-200 rounded-lg shadow-card", className)}>
      {hasHeader && (
        <div className="flex items-start justify-between gap-3 px-4 pt-4">
          <div className="min-w-0">
            {title && <h2 className="text-base font-semibold text-slate-900">{title}</h2>}
            {description && <p className="mt-0.5 text-xs text-slate-500">{description}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </div>
      )}
      <div className={cn("p-4", bodyClassName)}>{children}</div>
    </section>
  );
}
```

```tsx
// SectionTitle.tsx
import cn from "@/lib/cn";

/** 카드 밖 섹션 제목 — 목록 위 등. 페이지 제목보다 한 단계 아래. */
export default function SectionTitle({ children, className }: { children: React.ReactNode; className?: string }) {
  return <h2 className={cn("text-sm font-semibold text-slate-600", className)}>{children}</h2>;
}
```

```tsx
// Callout.tsx
import cn from "@/lib/cn";
import { toneColor, type Tone } from "@/utils/statusColors";

interface CalloutProps {
  tone: Tone;
  title?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
}

/**
 * 한 톤으로 물든 안내 상자. 사고 소환 배너(high)·되묻기 슬롯(pending)·판독 진행(progress)·
 * 데모 모드 안내(neutral)가 전부 이걸 쓴다. 아이콘 없이 색과 제목 굵기로만 말한다.
 */
export default function Callout({ tone, title, children, className }: CalloutProps) {
  const c = toneColor(tone);
  return (
    <div role="note" className={cn("rounded-lg border p-4", c.bg, c.border, className)}>
      {title && <p className={cn("text-stage font-semibold", c.text)}>{title}</p>}
      {children && <div className={cn("text-sm text-slate-700", title && "mt-1")}>{children}</div>}
    </div>
  );
}
```

- [ ] **Step 7: 테스트 통과 · 커밋**

Run: `npm run test -- components/ui` → PASS

```bash
git add frontend/src/components/ui
git commit -m "feat(frontend): UI 프리미티브 3 — DataTable·KpiCell·Card·Callout 등 이식

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: 인프라 계층 — api 클라이언트 · abort 레지스트리 · SSE 파서 · useSSEStream · useApiData

**Files:**
- Create: `src/api/client.ts`(교체), `src/api/endpoints.ts`, `src/api/{equipmentApi,workPlanApi,incidentApi,visionApi,timelineApi,formUrl}.ts`, `src/utils/{abortRegistry,errorMessage,sseStream,sseGuards}.ts`, `src/hooks/{useApiData,useSSEStream,useDebounce,useBreakpoint}.ts`, `src/utils/__tests__/{sseStream,abortRegistry}.test.ts`, `src/hooks/__tests__/{useApiData,useSSEStream}.test.ts`
- Delete: `src/api/saifeApi.ts` (Task 13에서 페이지 교체 후 삭제. 이 태스크에서는 남겨둔다)
- 원천: `MF/api/client.ts`, `MF/utils/{abortRegistry,errorMessage,sseStream,sseGuards}.ts`, `MF/hooks/{useApiData,useSSEStream,useDebounce,useBreakpoint}.ts`, 테스트 4종

**Interfaces:**
- `api` (axios 인스턴스, `timeout 60_000`), `fetchWithAuth(input, init): Promise<Response>` — non-ok 응답이면 `ApiError(status, message)`를 던진다. `ApiError` class 유지.
- `getServerMessage(err: unknown): string | undefined`
- `isAbortError(err: unknown): boolean`, `registerAbortController`, `unregisterAbortController`, `abortAllRequests`
- `readSseStream(response, signal?)`, `safeJson<T>(raw)`
- `useApiData<T>({ fetchFn, deps, errorMessage?, skipFirstSkeleton?, enabled? }) → { data, loading, error, initialLoaded, refetch }`
- `useSSEStream<TEventMap>({ url, method?, body?, headers?, handlers, onConnectionChange? }) → { connectionState, start(body?): Promise<StreamOutcome>, abort() }` — `StreamOutcome = { reason: "ended" | "aborted" | "error"; error?: Error }`. `body`가 `FormData`면 그대로 보내고 Content-Type을 설정하지 않는다. 재연결·`enabled` 선언 모드·하트비트 타임아웃은 없다(POST 스트림 재연결 = 재요청).
- 도메인 API: `equipmentApi.list(signal?)`, `timelineApi.byEquipment(id, signal?)`, `incidentApi.{register(body), detail(id), list(page, size, signal?)}`, `workPlanApi.{list(page,size,signal?), detail(id), acknowledge(id), approve(id, approver?, condition?)}`, `visionApi.{result(id), adopt(hazardId), reject(hazardId), adoptionRate(signal?)}`, `formUrl.{assessment,incident,workPlan}(id)`. 타입은 현 `saifeApi.ts`와 동일.
- `endpoints.ts`: `AGENT_CHAT = "/api/agent/chat"`, `agentTranscript(id)`, `VISION_ANALYZE = "/api/vision/analyze"` 등 경로 상수.

- [ ] **Step 1: 테스트 이식** — `MF/utils/__tests__/sseStream.test.ts` 그대로 복사. `MF/utils/__tests__/abortRegistry.test.ts`는 아래로 대체(인증 없음):

```ts
import { describe, expect, it } from "vitest";
import axios from "axios";

import { isAbortError } from "@/utils/abortRegistry";

describe("isAbortError — 취소를 실패로 오인하지 않는 가드", () => {
  it("fetch 취소(AbortError)를 취소로 본다", () => {
    expect(isAbortError(new DOMException("aborted", "AbortError"))).toBe(true);
  });
  it("axios 취소(CanceledError)를 취소로 본다", () => {
    expect(isAbortError(new axios.CanceledError("canceled"))).toBe(true);
  });
  it("진짜 실패는 취소가 아니다", () => {
    expect(isAbortError(new Error("Network error"))).toBe(false);
    expect(isAbortError(new axios.AxiosError("500", "ERR_BAD_RESPONSE"))).toBe(false);
  });
  it("null·undefined에도 터지지 않는다", () => {
    expect(isAbortError(null)).toBe(false);
    expect(isAbortError(undefined)).toBe(false);
  });
});
```

`MF/hooks/__tests__/useApiData.test.ts` 복사 후 `vi.mock("@/utils/abortRegistry", ...)` 블록의 `isAbortOrLogout` → `isAbortError`.

`src/hooks/__tests__/useSSEStream.test.ts` 신규(명령형 모드 전용):

```ts
import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/client", () => ({
  fetchWithAuth: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, message: string) { super(message); }
  },
}));

import { fetchWithAuth } from "@/api/client";
import { useSSEStream } from "@/hooks/useSSEStream";

const mockFetch = vi.mocked(fetchWithAuth);

/** 문자열 청크들을 SSE 본문으로 흘려주는 Response 목 */
function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  let i = 0;
  const reader = {
    read: async () =>
      i < chunks.length ? { done: false, value: encoder.encode(chunks[i++]) } : { done: true, value: undefined },
    cancel: async () => {},
    releaseLock: () => {},
  };
  return { ok: true, status: 200, body: { getReader: () => reader } } as unknown as Response;
}

beforeEach(() => mockFetch.mockReset());

describe("useSSEStream (명령형)", () => {
  it("봉투 type별 handler에 payload를 전달하고, 끝나면 ended로 돌아온다", async () => {
    const env = { type: "ai.token", correlationId: "c1", targetId: null, seq: 1, ts: "t", payload: "안녕" };
    mockFetch.mockResolvedValue(sseResponse([`event: ai.token\ndata: ${JSON.stringify(env)}\n\n`]));
    const onToken = vi.fn();
    const { result } = renderHook(() =>
      useSSEStream<{ "ai.token": string }>({ url: "/api/agent/chat", method: "POST", handlers: { "ai.token": onToken } }),
    );
    let outcome;
    await act(async () => { outcome = await result.current.start({ message: "hi" }); });
    expect(onToken).toHaveBeenCalledWith("안녕", expect.objectContaining({ seq: 1 }));
    expect(outcome).toEqual({ reason: "ended" });
    expect(result.current.connectionState).toBe("idle");
  });

  it("FormData body는 JSON 직렬화하지 않고 그대로 보낸다", async () => {
    mockFetch.mockResolvedValue(sseResponse([]));
    const { result } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/api/vision/analyze", method: "POST", handlers: {} }));
    const form = new FormData();
    await act(async () => { await result.current.start(form); });
    const init = mockFetch.mock.calls[0][1]!;
    expect(init.body).toBe(form);
    expect(new Headers(init.headers).get("Content-Type")).toBeNull();
  });

  it("non-ok 응답(fetchWithAuth가 던짐)이면 error 상태와 error outcome을 돌려준다", async () => {
    mockFetch.mockRejectedValue(new Error("업로드 실패 (500)"));
    const { result } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/x", method: "POST", handlers: {} }));
    let outcome: { reason: string; error?: Error } | undefined;
    await act(async () => { outcome = await result.current.start({}); });
    expect(outcome?.reason).toBe("error");
    expect(outcome?.error?.message).toBe("업로드 실패 (500)");
    expect(result.current.connectionState).toBe("error");
  });

  it("abort()로 중단하면 aborted outcome이고 error가 아니다", async () => {
    mockFetch.mockImplementation((_i, init) => new Promise((_, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    }));
    const { result } = renderHook(() => useSSEStream<Record<string, never>>({ url: "/x", method: "POST", handlers: {} }));
    let p: Promise<{ reason: string }> | undefined;
    act(() => { p = result.current.start({}); });
    act(() => result.current.abort());
    const outcome = await p!;
    expect(outcome.reason).toBe("aborted");
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- utils hooks` → 모듈 없음

- [ ] **Step 3: `src/utils/abortRegistry.ts`**

```ts
// 글로벌 AbortController 레지스트리 — in-flight 요청을 한 번에 중단할 때 쓴다.
import axios from "axios";

const registry = new Set<AbortController>();

export function registerAbortController(c: AbortController) { registry.add(c); }
export function unregisterAbortController(c: AbortController) { registry.delete(c); }
export function abortAllRequests() { registry.forEach((c) => c.abort()); registry.clear(); }

/**
 * catch 블록 공통 가드 — 요청이 취소된 것이면 true. true면 에러 UI를 띄우지 않는다.
 * fetch는 `AbortError`, axios는 `CanceledError`를 던진다. 한쪽만 보면 다른 쪽 취소가 전부 토스트가 된다.
 */
export function isAbortError(err: unknown): boolean {
  if ((err as { name?: string })?.name === "AbortError") return true;
  return axios.isCancel(err);
}
```

- [ ] **Step 4: `src/utils/errorMessage.ts`** — 원천 복사(변경 없음; 백엔드 `GlobalExceptionHandler`가 `message` 필드를 내려준다).

- [ ] **Step 5: `src/utils/sseStream.ts`, `src/utils/sseGuards.ts`** — 원천 복사(변경 없음).

- [ ] **Step 6: `src/api/client.ts` 교체**

```ts
// axios 인스턴스(REST) + fetch 래퍼(SSE). 인증은 아직 없다 — 붙일 자리만 남긴다.
import axios from "axios";

import { registerAbortController, unregisterAbortController } from "@/utils/abortRegistry";

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

/** 회선이 죽어도 요청이 영원히 매달리지 않게 상한을 둔다. */
export const DEFAULT_TIMEOUT_MS = 60_000;

export const api = axios.create({ timeout: DEFAULT_TIMEOUT_MS });

/**
 * SSE·멀티파트용 fetch 래퍼. non-ok면 본문 message를 담은 ApiError를 던진다 —
 * 스트림 파서가 HTML 에러 페이지를 읽으려 들지 않게 여기서 끊는다.
 */
export async function fetchWithAuth(input: RequestInfo, init?: RequestInit): Promise<Response> {
  let internal: AbortController | null = null;
  let signal = init?.signal;
  if (!signal) {
    internal = new AbortController();
    signal = internal.signal;
    registerAbortController(internal);
  }
  try {
    const res = await fetch(input, { ...init, signal });
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      let message = text || `${res.status} ${res.statusText}`;
      try {
        const body = JSON.parse(text) as { message?: string };
        if (body?.message) message = body.message;
      } catch { /* JSON이 아니면 원문 유지 */ }
      throw new ApiError(res.status, message);
    }
    return res;
  } finally {
    if (internal) unregisterAbortController(internal);
  }
}
```

- [ ] **Step 7: `src/api/endpoints.ts`와 도메인 API 파일**

```ts
// endpoints.ts — 경로 상수. 백엔드 @RequestMapping과 1:1.
export const AGENT_CHAT = "/api/agent/chat";
export const agentTranscript = (conversationId: string) => `/api/agent/${conversationId}/transcript`;
export const VISION_ANALYZE = "/api/vision/analyze";
export const EQUIPMENT = "/api/equipment";
export const WORK_PLAN = "/api/work-plan";
export const INCIDENT = "/api/incident";
export const equipmentTimeline = (equipmentId: number) => `/api/dashboard/equipment/${equipmentId}/timeline`;
```

```ts
// equipmentApi.ts
import { api } from "@/api/client";
import { EQUIPMENT } from "@/api/endpoints";
import type { EquipmentItem } from "@/types/equipment";

export const equipmentApi = {
  list: (signal?: AbortSignal) => api.get<EquipmentItem[]>(EQUIPMENT, { signal }).then((r) => r.data),
};
```

```ts
// timelineApi.ts
import { api } from "@/api/client";
import { equipmentTimeline } from "@/api/endpoints";
import type { EquipmentTimeline } from "@/types/timeline";

export const timelineApi = {
  byEquipment: (equipmentId: number, signal?: AbortSignal) =>
    api.get<EquipmentTimeline>(equipmentTimeline(equipmentId), { signal }).then((r) => r.data),
};
```

```ts
// incidentApi.ts
import { api } from "@/api/client";
import { INCIDENT } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type { IncidentListItem, IncidentRegisterResponse, RegisterIncidentRequest } from "@/types/incident";

export const incidentApi = {
  register: (body: RegisterIncidentRequest) => api.post<IncidentRegisterResponse>(INCIDENT, body).then((r) => r.data),
  detail: (id: number) => api.get<IncidentRegisterResponse>(`${INCIDENT}/${id}`).then((r) => r.data),
  list: (page = 0, size = 20, signal?: AbortSignal) =>
    api.get<PaginationResponse<IncidentListItem>>(INCIDENT, { params: { page, size }, signal }).then((r) => r.data),
};
```

```ts
// workPlanApi.ts
import { api } from "@/api/client";
import { WORK_PLAN } from "@/api/endpoints";
import type { PaginationResponse } from "@/types/common";
import type { WorkPlanDetail, WorkPlanListItem } from "@/types/workPlan";

export const workPlanApi = {
  list: (page = 0, size = 20, signal?: AbortSignal) =>
    api.get<PaginationResponse<WorkPlanListItem>>(WORK_PLAN, { params: { page, size }, signal }).then((r) => r.data),
  detail: (id: number) => api.get<WorkPlanDetail>(`${WORK_PLAN}/${id}`).then((r) => r.data),
  acknowledge: (id: number) => api.post<WorkPlanDetail>(`${WORK_PLAN}/${id}/ack`).then((r) => r.data),
  approve: (id: number, approver?: string, condition?: string) =>
    api.post<WorkPlanDetail>(`${WORK_PLAN}/${id}/approve`, { approver, condition }).then((r) => r.data),
};
```

```ts
// visionApi.ts
import { api } from "@/api/client";
import type { AdoptionRate, VisionAnalysisResult, VisionCandidate } from "@/types/vision";

export const visionApi = {
  result: (assessmentId: number) => api.get<VisionAnalysisResult>(`/api/vision/result/${assessmentId}`).then((r) => r.data),
  adopt: (hazardId: number) => api.post<VisionCandidate>(`/api/vision/hazard/${hazardId}/adopt`).then((r) => r.data),
  reject: (hazardId: number) => api.post<VisionCandidate>(`/api/vision/hazard/${hazardId}/reject`).then((r) => r.data),
  adoptionRate: (signal?: AbortSignal) => api.get<AdoptionRate>("/api/vision/adoption-rate", { signal }).then((r) => r.data),
};
```

```ts
// formUrl.ts — 법정 서식은 새 탭에서 연다. 인쇄(Ctrl+P)로 PDF가 된다
export const formUrl = {
  assessment: (id: number) => `/form/assessment/${id}`,
  incident: (id: number) => `/form/incident/${id}`,
  workPlan: (id: number) => `/form/work-plan/${id}`,
};
```

- [ ] **Step 8: `src/hooks/useApiData.ts`** — 원천 복사 후 `isAbortOrLogout` → `isAbortError`(import·호출 2곳). `useDebounce.ts`, `useBreakpoint.ts` 원천 복사(변경 없음).

- [ ] **Step 9: `src/hooks/useSSEStream.ts` 작성(간소화판)**

```ts
// 봉투 파싱 훅 — 프레임 파서(utils/sseStream) 위에 type별 handler 디스패치만 얹는다.
// 재연결이 없다: SAIFE의 스트림은 전부 POST(대화 턴·사진 업로드)라 재연결이 곧 재요청이다.
import { useCallback, useEffect, useRef, useState } from "react";

import { fetchWithAuth } from "@/api/client";
import type { ConnectionState, SseEnvelope } from "@/types/sse";
import { isAbortError, registerAbortController, unregisterAbortController } from "@/utils/abortRegistry";
import { readSseStream, safeJson } from "@/utils/sseStream";

type HandlerMap<TEventMap> = {
  [K in keyof TEventMap]?: (payload: TEventMap[K], envelope: SseEnvelope<TEventMap[K]>) => void;
};

export interface StreamOutcome {
  reason: "ended" | "aborted" | "error";
  error?: Error;
}

export interface UseSSEStreamOptions<TEventMap> {
  url: string;
  method?: "GET" | "POST";
  /** 기본 body — start(body)로 덮을 수 있다. FormData면 그대로 보낸다. */
  body?: unknown;
  headers?: Record<string, string>;
  handlers: HandlerMap<TEventMap>;
  onConnectionChange?: (state: ConnectionState) => void;
}

export interface UseSSEStreamReturn {
  connectionState: ConnectionState;
  /** 스트림 시작. 끝날 때까지 기다릴 수 있다(도메인 훅이 "턴이 끝났다"를 알아야 한다). */
  start: (body?: unknown) => Promise<StreamOutcome>;
  abort: () => void;
}

export function useSSEStream<TEventMap>(options: UseSSEStreamOptions<TEventMap>): UseSSEStreamReturn {
  const { url, method = "GET", body, headers, handlers, onConnectionChange } = options;
  const [connectionState, setConnectionState] = useState<ConnectionState>("idle");
  const controllerRef = useRef<AbortController | null>(null);
  // 최신 콜백·옵션 참조 — 렌더 중 ref 쓰기 금지 → 커밋 후 동기화
  const handlersRef = useRef(handlers);
  const onChangeRef = useRef(onConnectionChange);
  const bodyRef = useRef(body);
  const headersRef = useRef(headers);
  useEffect(() => {
    handlersRef.current = handlers;
    onChangeRef.current = onConnectionChange;
    bodyRef.current = body;
    headersRef.current = headers;
  });

  const updateState = useCallback((s: ConnectionState) => {
    setConnectionState(s);
    onChangeRef.current?.(s);
  }, []);

  const start = useCallback(
    async (overrideBody?: unknown): Promise<StreamOutcome> => {
      // 이전 스트림 중단 — 한 번에 하나만 산다
      if (controllerRef.current) {
        controllerRef.current.abort();
        unregisterAbortController(controllerRef.current);
      }
      const controller = new AbortController();
      controllerRef.current = controller;
      registerAbortController(controller);
      updateState("connecting");

      try {
        const fetchBody = overrideBody ?? bodyRef.current;
        const reqHeaders: Record<string, string> = { ...headersRef.current };
        const init: RequestInit = { method, signal: controller.signal, headers: reqHeaders };
        if (fetchBody instanceof FormData) {
          init.body = fetchBody; // Content-Type은 브라우저가 boundary와 함께 붙인다
        } else if (fetchBody != null) {
          reqHeaders["Content-Type"] = "application/json";
          init.body = JSON.stringify(fetchBody);
        }

        const response = await fetchWithAuth(url, init);
        updateState("connected");

        for await (const ev of readSseStream(response, controller.signal)) {
          const envelope = safeJson<SseEnvelope>(ev.data);
          if (!envelope) continue;
          if (envelope.type === "system.heartbeat") continue;
          const handler = handlersRef.current[envelope.type as keyof TEventMap];
          if (handler) {
            (handler as (p: unknown, e: SseEnvelope) => void)(envelope.payload, envelope);
          }
        }
        updateState("idle");
        return { reason: "ended" };
      } catch (err) {
        if (isAbortError(err)) {
          updateState("idle");
          return { reason: "aborted" };
        }
        updateState("error");
        return { reason: "error", error: err instanceof Error ? err : new Error(String(err)) };
      } finally {
        if (controllerRef.current === controller) controllerRef.current = null;
        unregisterAbortController(controller);
      }
    },
    [url, method, updateState],
  );

  const abort = useCallback(() => {
    const c = controllerRef.current;
    if (c) {
      c.abort();
      unregisterAbortController(c);
    }
  }, []);

  // 언마운트 시 진행 중 스트림 정리
  useEffect(() => () => controllerRef.current?.abort(), []);

  return { connectionState, start, abort };
}
```

- [ ] **Step 10: 테스트 통과 · 커밋**

Run: `npm run test -- utils hooks` → PASS

```bash
git add frontend/src/api frontend/src/utils frontend/src/hooks
git commit -m "feat(frontend): api 클라이언트 · SSE 3계층 하부(파서·useSSEStream) · useApiData 이식

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: datetime 유틸

**Files:**
- Create: `src/utils/datetime.ts`, `src/utils/__tests__/datetime.test.ts`

**Interfaces:**
- `formatDate(v: string|null|undefined): string` → `YYYY-MM-DD` (date-only는 변환 없이 통과, 없으면 `"-"`)
- `formatDateTime(iso): string` → `YYYY-MM-DD HH:mm` (Asia/Seoul)
- `nowLocalInput(): string` → datetime-local 기본값 `YYYY-MM-DDTHH:mm`
- `toOffsetIso(local: string): string` → 브라우저 오프셋 붙인 ISO
- `dDayLabel(daysRemaining: number|null): string` → `D-30` / `D+3` / `D-Day` / `""`

- [ ] **Step 1: 실패 테스트** — `src/utils/__tests__/datetime.test.ts`

```ts
import { describe, expect, it } from "vitest";

import { dDayLabel, formatDate, formatDateTime, toOffsetIso } from "@/utils/datetime";

describe("datetime", () => {
  it("date-only는 변환 없이 그대로 둔다(경계에서 하루 밀림 방지)", () => {
    expect(formatDate("2026-09-21")).toBe("2026-09-21");
  });
  it("Instant는 Asia/Seoul로 고정해 표시한다", () => {
    expect(formatDateTime("2026-09-20T21:41:00Z")).toBe("2026-09-21 06:41");
  });
  it("빈 값은 대시", () => {
    expect(formatDate(null)).toBe("-");
    expect(formatDateTime(undefined)).toBe("-");
  });
  it("toOffsetIso는 초와 브라우저 오프셋을 붙인다", () => {
    const out = toOffsetIso("2026-09-21T06:41");
    expect(out.startsWith("2026-09-21T06:41:00")).toBe(true);
    expect(out).toMatch(/[+-]\d{2}:\d{2}$/);
  });
  it("D-day 라벨", () => {
    expect(dDayLabel(30)).toBe("D-30");
    expect(dDayLabel(-3)).toBe("D+3");
    expect(dDayLabel(0)).toBe("D-Day");
    expect(dDayLabel(null)).toBe("");
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- datetime`

- [ ] **Step 3: `src/utils/datetime.ts`**

```ts
// 시각 표시는 브라우저 로컬이 아니라 Asia/Seoul로 고정한다. 법정 기한 계산이 KST 기준이다.
import dayjs from "dayjs";
import timezone from "dayjs/plugin/timezone";
import utc from "dayjs/plugin/utc";

dayjs.extend(utc);
dayjs.extend(timezone);

export const SITE_TZ = "Asia/Seoul";
const EMPTY = "-";
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** LocalDate는 벽시계 날짜라 변환하지 않는다. Instant가 섞여 오면 기준시로 날짜만 뽑는다. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return EMPTY;
  if (DATE_ONLY.test(value)) return value;
  const d = dayjs(value);
  return d.isValid() ? d.tz(SITE_TZ).format("YYYY-MM-DD") : EMPTY;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  const d = dayjs(iso);
  return d.isValid() ? d.tz(SITE_TZ).format("YYYY-MM-DD HH:mm") : EMPTY;
}

/** datetime-local 기본값 — 지금(브라우저 로컬) */
export function nowLocalInput(): string {
  return dayjs().format("YYYY-MM-DDTHH:mm");
}

/**
 * datetime-local(오프셋 없음) → 오프셋이 붙은 ISO. 오프셋 없이 보내면 서버가 자기 시간대로 해석해
 * 법정 기한이 하루 밀릴 수 있다.
 */
export function toOffsetIso(local: string): string {
  const offsetMin = -new Date(local).getTimezoneOffset();
  const sign = offsetMin >= 0 ? "+" : "-";
  const pad = (n: number) => String(Math.floor(Math.abs(n))).padStart(2, "0");
  return `${local}:00${sign}${pad(offsetMin / 60)}:${pad(offsetMin % 60)}`;
}

/** 남은 일수 → D-day 표기. 음수는 기한이 지났다. */
export function dDayLabel(daysRemaining: number | null | undefined): string {
  if (daysRemaining === null || daysRemaining === undefined) return "";
  if (daysRemaining === 0) return "D-Day";
  return daysRemaining > 0 ? `D-${daysRemaining}` : `D+${Math.abs(daysRemaining)}`;
}
```

- [ ] **Step 4: 통과 · 커밋**

```bash
git add frontend/src/utils
git commit -m "feat(frontend): datetime 유틸 — Asia/Seoul 고정 · D-day · 오프셋 ISO

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: 앱 셸 — 사이드바 · 라우팅 · 에러 바운더리 · 토스트

**Files:**
- Create: `src/stores/uiStore.ts`, `src/components/layout/menu.ts`, `src/components/layout/GlobalSidebar.tsx`, `src/components/layout/MobileHeader.tsx`, `src/components/common/RouteErrorBoundary.tsx`, `src/components/common/SectionErrorBoundary.tsx`, `src/components/common/AgentMessage.tsx`(이동), `src/pages/{WorkPlan,Incident,Timeline,Vision}/index.tsx`(임시 재수출), `src/stores/__tests__/uiStore.test.ts`
- Modify: `src/App.tsx`
- 원천: `MF/components/common/{RouteErrorBoundary,SectionErrorBoundary}.tsx`, `MF/components/layout/GlobalSidebar.tsx`(참고만), `MF/stores/uiStore.ts`

**Interfaces:**
- `useUiStore`: `{ sidebarCollapsed: boolean | null; setSidebarCollapsed(v: boolean): void; toggleSidebar(current: boolean): void }` — `null`은 사용자 선호 없음. localStorage 키 `saife.sidebar-collapsed`, 접근은 try/catch.
- `MENU: { key, path, label, icon }[]` (`components/layout/menu.ts`), `LANDING_PATH = "/work-plan"`, `COLLAPSED_BY_DEFAULT = new Set(["/work-plan"])`.
- `GlobalSidebar({ isOpen, onClose })`, `MobileHeader({ onOpenSidebar })`.
- 임시 `pages/*/index.tsx`는 기존 평면 페이지를 `export { default }` 형태로 감싼다(Task 9~12에서 교체).

- [ ] **Step 1: 실패 테스트** — `src/stores/__tests__/uiStore.test.ts`

```ts
import { beforeEach, describe, expect, it } from "vitest";

import { useUiStore } from "@/stores/uiStore";

describe("uiStore — 사이드바 접힘 선호", () => {
  beforeEach(() => {
    localStorage.clear();
    useUiStore.setState({ sidebarCollapsed: null });
  });

  it("초기값은 null(선호 없음)이다", () => {
    expect(useUiStore.getState().sidebarCollapsed).toBeNull();
  });

  it("toggleSidebar(current)는 현재 표시 상태의 반대를 저장한다", () => {
    useUiStore.getState().toggleSidebar(true);
    expect(useUiStore.getState().sidebarCollapsed).toBe(false);
    expect(localStorage.getItem("saife.sidebar-collapsed")).toBe("false");
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- uiStore`

- [ ] **Step 3: `src/stores/uiStore.ts`**

```ts
import { create } from "zustand";

const STORAGE_KEY = "saife.sidebar-collapsed";

function readStored(): boolean | null {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === null ? null : v === "true";
  } catch {
    return null; // 사생활 보호 모드 등 — 선호 없음으로 간다
  }
}

function writeStored(v: boolean) {
  try { localStorage.setItem(STORAGE_KEY, String(v)); } catch { /* 무시 */ }
}

interface UiState {
  /** null = 사용자가 정한 적 없음. 그때는 화면별 기본값(작업계획서만 접힘)을 따른다. */
  sidebarCollapsed: boolean | null;
  setSidebarCollapsed: (v: boolean) => void;
  /** 지금 화면에 보이는 상태(current)의 반대를 사용자 선호로 저장한다. */
  toggleSidebar: (current: boolean) => void;
}

export const useUiStore = create<UiState>()((set) => ({
  sidebarCollapsed: readStored(),
  setSidebarCollapsed: (v) => { writeStored(v); set({ sidebarCollapsed: v }); },
  toggleSidebar: (current) => { writeStored(!current); set({ sidebarCollapsed: !current }); },
}));
```

- [ ] **Step 4: `src/components/layout/menu.ts`**

```ts
import { Camera, ClipboardList, History, Siren, type LucideIcon } from "lucide-react";

export interface MenuItem { key: string; path: string; label: string; icon: LucideIcon }

/** 시연 순서와 같다. 마지막이 타임라인인 이유는 앞의 기록이 한 설비 ID 위에 쌓인 것을 마지막에 보여주기 위해서다. */
export const MENU: MenuItem[] = [
  { key: "work-plan", path: "/work-plan", label: "작업계획서", icon: ClipboardList },
  { key: "vision", path: "/vision", label: "사진 판독", icon: Camera },
  { key: "incident", path: "/incident", label: "사고 등록", icon: Siren },
  { key: "timeline", path: "/timeline", label: "설비 타임라인", icon: History },
];

export const LANDING_PATH = "/work-plan";

/** 사용자 선호가 없을 때 접힌 채로 여는 화면 — 대화+트레이스 2열이 폭을 다 쓴다. */
export const COLLAPSED_BY_DEFAULT = new Set(["/work-plan"]);

export const SITE_NAME = "가상 정밀금속 사업장";
```

- [ ] **Step 5: `src/components/layout/GlobalSidebar.tsx`** — MetalFlow 구조(sticky 레일 + 6px 경계 토글 + 접힘 툴팁 + 모바일 드로어)를 SAIFE용으로 축약해 작성. 권한·플라이아웃·언어·로그아웃·로고 이미지 없음.

```tsx
import { useState } from "react";

import { X } from "lucide-react";
import { useLocation, useNavigate } from "react-router-dom";

import cn from "@/lib/cn";
import { useUiStore } from "@/stores/uiStore";
import { COLLAPSED_BY_DEFAULT, LANDING_PATH, MENU, SITE_NAME } from "@/components/layout/menu";

interface Props { isOpen: boolean; onClose: () => void }

interface TooltipState { label: string; top: number; left: number }

/** 접힌 레일의 항목 라벨 — overflow 영향을 받지 않도록 fixed */
function FixedTooltip({ tooltip }: { tooltip: TooltipState | null }) {
  if (!tooltip) return null;
  return (
    <div
      className="fixed z-[200] px-2.5 py-1.5 bg-slate-800 text-white text-xs font-medium rounded-md whitespace-nowrap shadow-modal pointer-events-none"
      style={{ top: tooltip.top, left: tooltip.left, transform: "translateY(-50%)" }}
    >
      {tooltip.label}
    </div>
  );
}

/** 사용자 선호가 있으면 그것, 없으면 화면별 기본값. */
export function resolveCollapsed(pref: boolean | null, pathname: string): boolean {
  return pref ?? COLLAPSED_BY_DEFAULT.has(pathname);
}

export default function GlobalSidebar({ isOpen, onClose }: Props) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const pref = useUiStore((s) => s.sidebarCollapsed);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);
  const collapsed = resolveCollapsed(pref, pathname);
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);
  const [edgeHover, setEdgeHover] = useState(false);

  const isActive = (path: string) => pathname.startsWith(path);
  const go = (path: string) => { navigate(path); onClose(); setTooltip(null); };

  const showTooltip = (e: React.MouseEvent, label: string) => {
    if (!collapsed) return;
    const r = e.currentTarget.getBoundingClientRect();
    setTooltip({ label, top: r.top + r.height / 2, left: r.right + 10 });
  };

  const brand = (
    <button onClick={() => go(LANDING_PATH)} className="flex flex-col items-start hover:opacity-80 transition-opacity">
      <span className="text-lg font-bold tracking-tight text-white leading-none">SAIFE</span>
      {!collapsed && <span className="mt-1 text-xs text-slate-400 whitespace-nowrap">설비 ID로 잇는 안전 데이터 코어</span>}
    </button>
  );

  const nav = (railCollapsed: boolean) => (
    <nav className={cn("flex-1 space-y-1 overflow-y-auto overflow-x-hidden py-2", railCollapsed ? "px-1.5" : "px-3")}>
      {MENU.map((item) => {
        const Icon = item.icon;
        const active = isActive(item.path);
        return (
          <button
            key={item.key}
            onClick={() => go(item.path)}
            onMouseEnter={(e) => showTooltip(e, item.label)}
            onMouseLeave={() => setTooltip(null)}
            aria-label={item.label}
            aria-current={active ? "page" : undefined}
            className={cn(
              "relative w-full flex items-center rounded-lg transition-colors duration-200 group",
              railCollapsed ? "justify-center p-2.5" : "gap-3 px-3 py-2.5",
              active ? "text-white bg-slate-800/70" : "text-slate-500 hover:text-white hover:bg-slate-800/40",
            )}
          >
            {active && !railCollapsed && <span className="absolute left-0 top-1/2 -translate-y-1/2 w-1 h-5 bg-progress rounded-r-full" />}
            <Icon size={20} className={cn("shrink-0 transition-colors", active ? "text-progress-border" : "text-slate-500 group-hover:text-white")} />
            {!railCollapsed && <span className={cn("text-stage whitespace-nowrap", active ? "font-semibold" : "font-medium")}>{item.label}</span>}
          </button>
        );
      })}
    </nav>
  );

  const footer = (railCollapsed: boolean) => (
    <div className={cn("border-t border-slate-800/60 text-xs text-slate-500", railCollapsed ? "px-2 py-3 text-center" : "p-4")}>
      {railCollapsed ? "가상" : <><p className="text-slate-300 font-medium">{SITE_NAME}</p><p className="mt-0.5">가상 사업장 · 데이터 전부 가상</p></>}
    </div>
  );

  return (
    <>
      {/* 데스크톱 레일 — sticky 풀뷰포트 */}
      <aside className={cn("hidden lg:flex flex-col h-screen sticky top-0 shrink-0 z-40 overflow-hidden bg-slate-950 text-white", collapsed ? "w-16" : "w-56")}>
        <div className={cn("flex items-center border-b border-slate-800/60", collapsed ? "py-4 px-2 justify-center" : "h-16 px-4")}>{brand}</div>
        {nav(collapsed)}
        {footer(collapsed)}
      </aside>

      {/* 경계선 토글 */}
      <div
        onClick={() => toggleSidebar(collapsed)}
        onMouseEnter={() => setEdgeHover(true)}
        onMouseLeave={() => setEdgeHover(false)}
        role="button"
        aria-label={collapsed ? "메뉴 펼치기" : "메뉴 접기"}
        className="hidden lg:block relative w-[6px] h-screen sticky top-0 cursor-pointer shrink-0 z-50 -ml-[6px]"
      >
        <div className={cn("absolute left-1/2 -translate-x-1/2 inset-y-0 rounded-full transition-all duration-200", edgeHover ? "w-[5px] bg-progress/80" : "w-[3px] bg-slate-800/30")} />
        <div className="absolute -left-4 -right-4 inset-y-0" />
      </div>

      <FixedTooltip tooltip={tooltip} />

      {/* 모바일 오버레이·드로어 */}
      {isOpen && <div className="fixed inset-0 z-[60] bg-black/50 lg:hidden" onClick={onClose} />}
      <aside className={cn("fixed top-0 left-0 bottom-0 z-[70] w-72 lg:hidden bg-slate-950 text-white transition-transform duration-300", isOpen ? "translate-x-0" : "-translate-x-full")}>
        <div className="h-16 px-4 flex items-center justify-between border-b border-slate-800/60">
          {brand}
          <button onClick={onClose} aria-label="닫기" className="p-1 text-white/60 hover:text-white hover:bg-white/10 rounded-md"><X size={22} /></button>
        </div>
        {nav(false)}
        {footer(false)}
      </aside>
    </>
  );
}
```

- [ ] **Step 6: `MobileHeader.tsx`** — 원천 복사 후 `LANDING_PATH` import를 `@/components/layout/menu`로, 브랜드 텍스트 `MetalFlow` → `SAIFE`.

- [ ] **Step 7: `RouteErrorBoundary.tsx`, `SectionErrorBoundary.tsx`** — 원천 복사. `text-verdict-nok-border` → `text-risk-high-border`, `text-[17px]` → `text-xl`, `text-gray-500` → `text-slate-500`, `text-gray-400` → `text-slate-400`.

- [ ] **Step 8: `AgentMessage.tsx` 이동** — `git mv src/components/AgentMessage.tsx src/components/common/AgentMessage.tsx`. 기존 페이지 import 경로는 Task 9에서 페이지가 사라지므로 지금은 `src/pages/WorkPlanChatPage.tsx`의 import만 `@/components/common/AgentMessage`로 고친다.

- [ ] **Step 9: 임시 `pages/*/index.tsx`** — 네 개:
  ```ts
  // src/pages/WorkPlan/index.tsx
  export { WorkPlanChatPage as default } from "@/pages/WorkPlanChatPage";
  ```
  Incident/Timeline/Vision도 동일 형태(`IncidentPage`, `TimelinePage`, `VisionPage`).

- [ ] **Step 10: `src/App.tsx` 교체**

```tsx
import { lazy, Suspense, useState } from "react";

import { Navigate, Route, Routes, useLocation } from "react-router-dom";

import RouteErrorBoundary from "@/components/common/RouteErrorBoundary";
import GlobalSidebar from "@/components/layout/GlobalSidebar";
import { LANDING_PATH } from "@/components/layout/menu";
import MobileHeader from "@/components/layout/MobileHeader";
import PageSkeleton from "@/components/ui/PageSkeleton";
import ToastContainer from "@/components/ui/ToastContainer";

const WorkPlanPage = lazy(() => import("@/pages/WorkPlan"));
const VisionPage = lazy(() => import("@/pages/Vision"));
const IncidentPage = lazy(() => import("@/pages/Incident"));
const TimelinePage = lazy(() => import("@/pages/Timeline"));

export default function App() {
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="flex min-h-screen flex-col bg-page">
      <ToastContainer />
      <MobileHeader onOpenSidebar={() => setSidebarOpen(true)} />
      <div className="flex flex-1">
        <GlobalSidebar isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
        {/* isolate — 콘텐츠의 z-index가 사이드바 경계 토글 위로 새지 않게 스태킹 컨텍스트를 만든다 */}
        <main className="isolate flex-1 min-w-0 flex flex-col">
          <RouteErrorBoundary resetKey={location.pathname}>
            <Suspense fallback={<PageSkeleton />}>
              <Routes>
                <Route path="/" element={<Navigate to={LANDING_PATH} replace />} />
                <Route path="/work-plan" element={<WorkPlanPage />} />
                <Route path="/vision" element={<VisionPage />} />
                <Route path="/incident" element={<IncidentPage />} />
                <Route path="/timeline" element={<TimelinePage />} />
                <Route path="*" element={<Navigate to={LANDING_PATH} replace />} />
              </Routes>
            </Suspense>
          </RouteErrorBoundary>
        </main>
      </div>
    </div>
  );
}
```

- [ ] **Step 11: 기존 페이지의 `RISK_CLASS` 참조 임시 복구** — Task 2에서 지운 `RISK_CLASS`를 기존 4페이지가 아직 쓰므로, 타입 검사가 통과하도록 `src/types/domain.ts`에 `/** @deprecated Task 9~12에서 제거 */ export const RISK_CLASS` 블록을 임시로 되살린다(Task 13에서 최종 삭제).

- [ ] **Step 12: 확인 · 커밋**

Run: `npm run lint && npm run check:types && npm run test` → PASS. `npm run dev`로 띄워 사이드바가 보이고 4메뉴가 이동하는지, 작업계획서에서 레일이 접혀 있는지 눈으로 확인.

```bash
git add -A frontend/src
git commit -m "feat(frontend): 앱 셸 — 좌측 사이드바 · lazy 라우팅 · 에러 바운더리 · 토스트

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: 작업계획서 화면 (UC3) 재작성

**Files:**
- Create: `src/pages/WorkPlan/WorkPlanPage.tsx`, `WorkPlanSkeleton.tsx`, `index.tsx`(교체), `hooks/useAgentStream.ts`, `hooks/useWorkPlans.ts`, `hooks/__tests__/useAgentStream.test.ts`, `components/{ChatThread,SlotPrompt,Composer,WorkPlanTable,WorkPlanDetailModal,ToolTracePanel}.tsx`
- Delete: `src/pages/WorkPlanChatPage.tsx`, `src/hooks/useAgentStream.ts`, `src/components/ToolTracePanel.tsx`

**Interfaces:**
- `useAgentStream()` → `{ trace: ToolTraceRow[], turns: Turn[], pendingSlot: SlotRequestPayload | null, error: string | null, streaming: boolean, restoring: boolean, connectionState, send(message): Promise<void>, answerSlot(slotKey, value): Promise<void>, reset(): void }`. `Turn = { role: "user"|"assistant"; text: string }`. sessionStorage 키 `saife.conversationId` 유지.
- `useWorkPlans()` → `{ plans: WorkPlanListItem[], loading, refetch, detail: WorkPlanDetail | null, openDetail(id), closeDetail(), acknowledge(id), approve(id), busy }`.
- `ToolTracePanel({ rows: ToolTraceRow[], connectionState })`.

- [ ] **Step 1: 실패 테스트** — `src/pages/WorkPlan/hooks/__tests__/useAgentStream.test.ts`

```ts
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/client", () => ({
  fetchWithAuth: vi.fn(),
  api: { get: vi.fn() },
  ApiError: class extends Error {},
}));

import { api, fetchWithAuth } from "@/api/client";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";

const mockFetch = vi.mocked(fetchWithAuth);
const mockGet = vi.mocked(api.get);

function envelope(type: string, seq: number, payload: unknown, correlationId = "conv-1") {
  return `event: ${type}\ndata: ${JSON.stringify({ type, correlationId, targetId: null, seq, ts: "t", payload })}\n\n`;
}

function sseResponse(chunks: string[]): Response {
  const enc = new TextEncoder();
  let i = 0;
  return {
    ok: true, status: 200,
    body: { getReader: () => ({
      read: async () => (i < chunks.length ? { done: false, value: enc.encode(chunks[i++]) } : { done: true, value: undefined }),
      cancel: async () => {}, releaseLock: () => {},
    }) },
  } as unknown as Response;
}

beforeEach(() => {
  sessionStorage.clear();
  mockFetch.mockReset();
  mockGet.mockReset();
});

describe("useAgentStream", () => {
  it("토큰을 assistant 턴에 이어 붙이고, 도구 시작/완료로 트레이스를 갱신하며, 끝나면 streaming=false", async () => {
    mockFetch.mockResolvedValue(sseResponse([
      envelope("ai.tool.start", 1, { toolName: "findLocationEquipment", params: "{}", callOrder: 1 }),
      envelope("ai.tool.done", 2, { toolName: "findLocationEquipment", callOrder: 1, success: true, durationMs: 17 }),
      envelope("ai.token", 3, "확인"), envelope("ai.token", 4, "했습니다"),
      envelope("ai.done", 5, null),
    ]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("내일 사다리 작업"); });
    expect(result.current.turns).toEqual([
      { role: "user", text: "내일 사다리 작업" },
      { role: "assistant", text: "확인했습니다" },
    ]);
    expect(result.current.trace).toEqual([
      expect.objectContaining({ toolName: "findLocationEquipment", status: "ok", durationMs: 17 }),
    ]);
    expect(result.current.streaming).toBe(false);
    expect(sessionStorage.getItem("saife.conversationId")).toBe("conv-1");
  });

  it("ai.slot.request 뒤 스트림이 닫혀도 pendingSlot은 남고 streaming은 false다", async () => {
    mockFetch.mockResolvedValue(sseResponse([
      envelope("ai.slot.request", 1, { slotKey: "work_height", question: "작업 높이는?", options: ["2m 미만", "2m 이상"] }),
    ]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("천장 도장"); });
    expect(result.current.pendingSlot?.slotKey).toBe("work_height");
    expect(result.current.streaming).toBe(false);
  });

  it("역행 seq는 버린다", async () => {
    mockFetch.mockResolvedValue(sseResponse([envelope("ai.token", 2, "b"), envelope("ai.token", 1, "a"), envelope("ai.token", 3, "c")]));
    const { result } = renderHook(() => useAgentStream());
    await act(async () => { await result.current.send("x"); });
    expect(result.current.turns[1].text).toBe("bc");
  });

  it("복원 응답이 send 이후 도착해도 turns를 덮지 않는다", async () => {
    sessionStorage.setItem("saife.conversationId", "conv-old");
    let resolveTranscript!: (v: { data: { role: string; text: string }[] }) => void;
    mockGet.mockReturnValue(new Promise((r) => { resolveTranscript = r; }) as never);
    mockFetch.mockResolvedValue(sseResponse([envelope("ai.token", 1, "새 답", "conv-old"), envelope("ai.done", 2, null, "conv-old")]));
    const { result } = renderHook(() => useAgentStream());
    expect(result.current.restoring).toBe(true);
    await act(async () => { await result.current.send("새 질문"); });
    await act(async () => { resolveTranscript({ data: [{ role: "user", text: "옛 질문" }] }); });
    await waitFor(() => expect(result.current.restoring).toBe(false));
    expect(result.current.turns.map((t) => t.text)).toEqual(["새 질문", "새 답"]);
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- useAgentStream` → 모듈 없음

- [ ] **Step 3: `hooks/useAgentStream.ts`**

```ts
// 에이전트 대화 도메인 훅 — useSSEStream 위에 이벤트 핸들러만 얹는다.
// 되묻기는 평범한 멀티턴이다. 모델이 질문하며 턴을 끝내는 것 자체가 일시정지다.
// 대화 ID는 sessionStorage에 남긴다(새로고침 한 번에 맥락이 사라지면 계획서가 쪼개진다 — QA 2026-09-21).
import { useCallback, useEffect, useRef, useState } from "react";

import { api } from "@/api/client";
import { agentTranscript, AGENT_CHAT } from "@/api/endpoints";
import { useSSEStream } from "@/hooks/useSSEStream";
import type { AiErrorPayload, SlotRequestPayload, SseEnvelope, ToolDonePayload, ToolStartPayload, ToolTraceRow } from "@/types/sse";

const CONVERSATION_KEY = "saife.conversationId";

function readStoredConversationId(): string | null {
  try { return sessionStorage.getItem(CONVERSATION_KEY); } catch { return null; }
}
function storeConversationId(id: string | null): void {
  try { id === null ? sessionStorage.removeItem(CONVERSATION_KEY) : sessionStorage.setItem(CONVERSATION_KEY, id); } catch { /* 저장 실패가 대화를 막으면 안 된다 */ }
}

export interface Turn { role: "user" | "assistant"; text: string }

interface AgentEventMap {
  "ai.token": string;
  "ai.tool.start": ToolStartPayload;
  "ai.tool.done": ToolDonePayload;
  "ai.slot.request": SlotRequestPayload;
  "ai.error": AiErrorPayload;
  "ai.done": null;
}

export function useAgentStream() {
  const [trace, setTrace] = useState<ToolTraceRow[]>([]);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [pendingSlot, setPendingSlot] = useState<SlotRequestPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [restoring, setRestoring] = useState(() => readStoredConversationId() !== null);

  const conversationIdRef = useRef<string | null>(readStoredConversationId());
  const lastSeqRef = useRef(0);
  // send가 한 번이라도 불리면 복원 결과로 turns를 덮지 않는다
  const sentRef = useRef(false);

  /** 봉투 공통 처리 — 역행 seq 폐기 + 대화 ID 동기화. true면 계속 처리. */
  const accept = useCallback((env: SseEnvelope<unknown>) => {
    if (env.seq <= lastSeqRef.current) return false;
    lastSeqRef.current = env.seq;
    if (conversationIdRef.current !== env.correlationId) {
      conversationIdRef.current = env.correlationId;
      storeConversationId(env.correlationId);
    }
    return true;
  }, []);

  const { connectionState, start, abort } = useSSEStream<AgentEventMap>({
    url: AGENT_CHAT,
    method: "POST",
    handlers: {
      "ai.token": (chunk, env) => {
        if (!accept(env)) return;
        const text = String(chunk ?? "");
        setTurns((prev) => {
          const last = prev[prev.length - 1];
          if (last && last.role === "assistant") return [...prev.slice(0, -1), { role: "assistant", text: last.text + text }];
          return [...prev, { role: "assistant", text }];
        });
      },
      "ai.tool.start": (p, env) => {
        if (!accept(env)) return;
        setTrace((prev) => [...prev, { callOrder: p.callOrder, toolName: p.toolName, params: p.params, status: "running" }]);
      },
      "ai.tool.done": (p, env) => {
        if (!accept(env)) return;
        setTrace((prev) => prev.map((row) => (row.callOrder === p.callOrder
          ? { ...row, status: p.success ? "ok" : "failed", durationMs: p.durationMs, errorMessage: p.errorMessage }
          : row)));
      },
      "ai.slot.request": (p, env) => { if (accept(env)) setPendingSlot(p); },
      "ai.error": (p, env) => { if (accept(env)) setError(p?.message ?? "알 수 없는 오류"); },
      "ai.done": (_p, env) => { accept(env); },
    },
  });

  const send = useCallback(async (message: string, slotKey?: string) => {
    sentRef.current = true;
    setError(null);
    setPendingSlot(null);
    lastSeqRef.current = 0; // seq는 턴마다 새로 시작한다
    setStreaming(true);
    setTurns((prev) => [...prev, { role: "user", text: message }]);
    setTrace([]);
    const outcome = await start({ message, conversationId: conversationIdRef.current, slotKey: slotKey ?? null });
    if (outcome.reason === "error") setError(outcome.error?.message ?? "스트림 오류");
    setStreaming(false);
  }, [start]);

  /** 되묻기 답변 — 같은 엔드포인트. 별도 재개 API가 없다 */
  const answerSlot = useCallback((slotKey: string, value: string) => send(value, slotKey), [send]);

  // 저장된 대화 복원 — 트레이스는 복원하지 않는다(지난 턴의 사건이라 방금 일어난 것처럼 보인다)
  useEffect(() => {
    const id = conversationIdRef.current;
    if (id === null) return;
    let cancelled = false;
    api.get<{ role: string; text: string }[]>(agentTranscript(id))
      .then((res) => {
        if (cancelled || sentRef.current) return;
        setTurns(res.data.filter((l) => l.role === "user" || l.role === "assistant").map((l) => ({ role: l.role as Turn["role"], text: l.text })));
      })
      .catch(() => { /* 복원 실패는 새 대화처럼 이어간다 */ })
      .finally(() => { if (!cancelled) setRestoring(false); });
    return () => { cancelled = true; };
  }, []);

  const reset = useCallback(() => {
    abort();
    conversationIdRef.current = null;
    storeConversationId(null);
    setTrace([]); setTurns([]); setPendingSlot(null); setError(null); setStreaming(false);
  }, [abort]);

  return { trace, turns, pendingSlot, error, streaming, restoring, connectionState, send, answerSlot, reset };
}
```

- [ ] **Step 4: `hooks/useWorkPlans.ts`**

```ts
import { useCallback, useState } from "react";

import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import type { WorkPlanDetail } from "@/types/workPlan";
import { getServerMessage } from "@/utils/errorMessage";

/** 목록·상세·확인·승인. 목록은 서버 데이터에서만 파생한다(낙관적 플래그 없음). */
export function useWorkPlans() {
  const { data, loading, refetch } = useApiData({
    fetchFn: (signal) => workPlanApi.list(0, 10, signal).then((p) => p.content),
    deps: [],
    errorMessage: "작업계획서 목록을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  const [detail, setDetail] = useState<WorkPlanDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const toast = useToastStore();

  const run = useCallback(async (fn: () => Promise<WorkPlanDetail>, done: string) => {
    setBusy(true);
    try {
      setDetail(await fn());
      toast.success(done);
      refetch();
    } catch (e) {
      toast.error(getServerMessage(e) ?? "처리하지 못했습니다");
    } finally {
      setBusy(false);
    }
  }, [refetch, toast]);

  return {
    plans: data ?? [],
    loading,
    refetch,
    detail,
    busy,
    openDetail: (id: number) => run(() => workPlanApi.detail(id), "작업계획서를 열었습니다"),
    closeDetail: () => setDetail(null),
    acknowledge: (id: number) => run(() => workPlanApi.acknowledge(id), "브리핑 확인이 기록됐습니다 (TBM)"),
    approve: (id: number) => run(() => workPlanApi.approve(id, "관리부"), "승인했습니다"),
  };
}
```

`openDetail`의 성공 토스트는 시끄럽다 — `run`에 `done?: string`으로 바꾸고 `openDetail`은 토스트 없이 호출한다(`if (done) toast.success(done)`).

- [ ] **Step 5: 컴포넌트 6종**

`components/ToolTracePanel.tsx`:
```tsx
import cn from "@/lib/cn";
import SseConnectionStatus from "@/components/ui/SseConnectionStatus";
import type { ConnectionState, ToolTraceRow } from "@/types/sse";

/**
 * 도구 호출 트레이스 — 심사위원이 "입력→판단→도구 실행→결과"를 눈으로 보게 만드는 패널.
 * 프로젝터에서 읽혀야 하므로 큰 글씨 6줄을 유지한다. 도구를 추가할 거면 이 레이아웃을 같이 본다.
 */
export default function ToolTracePanel({ rows, connectionState }: { rows: ToolTraceRow[]; connectionState: ConnectionState }) {
  return (
    <aside className="flex h-full flex-col rounded-lg border border-slate-200 bg-white p-5 shadow-card">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-600">에이전트 트레이스</h2>
        <SseConnectionStatus state={connectionState} />
      </div>
      <ol className="mt-4 space-y-3">
        {rows.length === 0 && <li className="text-sm text-slate-400">도구 호출 대기 중</li>}
        {rows.map((row) => (
          <li key={row.callOrder} className="flex items-center gap-3 text-stage">
            <span className={cn("h-2.5 w-2.5 shrink-0 rounded-full",
              row.status === "running" && "bg-progress animate-pulse",
              row.status === "ok" && "bg-slate-400",
              row.status === "failed" && "bg-risk-high")} />
            <span className="font-mono">{row.toolName}</span>
            {row.durationMs !== undefined && <span className="ml-auto text-sm tabular-nums text-slate-500">{row.durationMs}ms</span>}
          </li>
        ))}
      </ol>
    </aside>
  );
}
```

`components/ChatThread.tsx`:
```tsx
import { useEffect, useRef } from "react";

import AgentMessage from "@/components/common/AgentMessage";
import type { Turn } from "@/pages/WorkPlan/hooks/useAgentStream";

interface Props { turns: Turn[]; streaming: boolean; restoring: boolean }

/** 대화 스레드. 새 답변이 오면 아래로 따라간다(발표자가 이전 턴을 보고 있게 되는 것을 막는다). */
export default function ChatThread({ turns, streaming, restoring }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { const el = ref.current; if (el) el.scrollTop = el.scrollHeight; }, [turns]);
  const waiting = streaming && turns[turns.length - 1]?.role === "user";
  return (
    <div ref={ref} className="flex-1 space-y-3 overflow-auto rounded-lg border border-slate-200 bg-white p-4 shadow-card">
      {turns.length === 0 && (
        <p className="text-sm text-slate-400">
          {restoring ? "이전 대화를 불러오는 중…" : "예: 내일 공장동 후면 차양부에서 사다리 놓고 천장 페인트 칠할 건데요"}
        </p>
      )}
      {turns.map((turn, i) => turn.role === "user" ? (
        <div key={i} className="flex justify-end">
          <p className="max-w-[80%] rounded-lg bg-slate-900 px-3 py-2 text-sm text-white">{turn.text}</p>
        </div>
      ) : (
        <div key={i} className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm"><AgentMessage text={turn.text} /></div>
      ))}
      {waiting && <p className="text-sm text-slate-400 animate-cursor-blink">확인하고 있습니다…</p>}
    </div>
  );
}
```
(`AgentMessage`는 default export로 바꾼다 — Task 8에서 이동한 파일의 `export function AgentMessage` → `export default function AgentMessage`.)

`components/SlotPrompt.tsx`:
```tsx
import { useState } from "react";

import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import Input from "@/components/ui/Input";
import SegmentedControl from "@/components/ui/SegmentedControl";
import type { SlotRequestPayload } from "@/types/sse";

interface Props { slot: SlotRequestPayload; disabled: boolean; onAnswer: (slotKey: string, value: string) => void }

/** 되묻기 카드 — options가 있으면 세그먼트, 없으면 입력. ledgerValue가 있으면 "확인" 성격이라 미리 채운다. */
export default function SlotPrompt({ slot, disabled, onAnswer }: Props) {
  const [value, setValue] = useState(slot.ledgerValue ?? "");
  const submit = () => { if (value.trim()) onAnswer(slot.slotKey, value.trim()); };
  return (
    <Callout tone="pending" title={slot.question}>
      {slot.ledgerValue && <p className="text-xs text-slate-500">설비 대장 기록: {slot.ledgerValue}</p>}
      <div className="mt-2 flex items-center gap-2">
        {slot.options && slot.options.length > 0 ? (
          <SegmentedControl ariaLabel={slot.question} size="sm" value={value || null}
            options={slot.options.map((o) => ({ value: o, label: o }))} onChange={setValue} className="flex-1" />
        ) : (
          <Input value={value} onChange={(e) => setValue(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter" && !disabled) submit(); }} placeholder="답변을 입력하세요" />
        )}
        <Button size="sm" disabled={disabled || !value.trim()} onClick={submit}>답변</Button>
      </div>
    </Callout>
  );
}
```

`components/Composer.tsx`:
```tsx
import { useState } from "react";

import Button from "@/components/ui/Button";
import Textarea from "@/components/ui/Textarea";

interface Props { disabled: boolean; onSend: (message: string) => void }

/** 입력창. Enter 전송, Shift+Enter 줄바꿈. */
export default function Composer({ disabled, onSend }: Props) {
  const [input, setInput] = useState("");
  const send = () => { const m = input.trim(); if (!m || disabled) return; onSend(m); setInput(""); };
  return (
    <div className="flex items-end gap-2">
      <Textarea rows={2} placeholder="작업 내용을 입력하세요" value={input} onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }} />
      <Button loading={disabled} disabled={!input.trim()} onClick={send}>보내기</Button>
    </div>
  );
}
```

`components/WorkPlanTable.tsx`:
```tsx
import { StatusBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { WorkPlanListItem } from "@/types/workPlan";
import { formatDate } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

interface Props { plans: WorkPlanListItem[]; onOpen: (id: number) => void }

export default function WorkPlanTable({ plans, onOpen }: Props) {
  const columns: Column<WorkPlanListItem>[] = [
    { key: "workName", header: "작업명", render: (p) => p.workName },
    { key: "workDate", header: "일자", width: "w-28", render: (p) => <span className="tabular-nums">{formatDate(p.workDate)}</span> },
    { key: "equipment", header: "설비", render: (p) => p.equipmentName ?? "-" },
    { key: "status", header: "상태", width: "w-28", render: (p) => <StatusBadge tone={workPlanStatusTone(p.status)}>{WORK_PLAN_STATUS_LABEL[p.status]}</StatusBadge> },
    { key: "briefing", header: "브리핑", width: "w-24", render: (p) => p.briefingAcknowledged ? <span className="text-risk-low-text">확인됨</span> : <span className="text-slate-400">미확인</span> },
    { key: "open", header: "", width: "w-20", align: "right", render: (p) => <Button variant="subtle" size="sm" onClick={() => onOpen(p.id)}>열기</Button> },
  ];
  return <DataTable columns={columns} data={plans} rowKey={(p) => p.id} onRowClick={(p) => onOpen(p.id)} emptyMessage="등록된 작업계획서가 없습니다" />;
}
```

`components/WorkPlanDetailModal.tsx`:
```tsx
import { formUrl } from "@/api/formUrl";
import AgentMessage from "@/components/common/AgentMessage";
import Button from "@/components/ui/Button";
import Modal from "@/components/ui/Modal";
import { StatusBadge } from "@/components/ui/Badge";
import { WORK_PLAN_STATUS_LABEL } from "@/types/domain";
import type { WorkPlanDetail } from "@/types/workPlan";
import { formatDateTime } from "@/utils/datetime";
import { workPlanStatusTone } from "@/utils/statusColors";

interface Props { detail: WorkPlanDetail | null; busy: boolean; onClose: () => void; onAcknowledge: (id: number) => void; onApprove: (id: number) => void }

export default function WorkPlanDetailModal({ detail, busy, onClose, onAcknowledge, onApprove }: Props) {
  if (!detail) return null;
  const footer = (
    <>
      <a className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50" href={formUrl.workPlan(detail.id)} target="_blank" rel="noreferrer">법정 서식</a>
      {!detail.briefingAckAt && detail.briefing && <Button size="sm" variant="secondary" loading={busy} onClick={() => onAcknowledge(detail.id)}>브리핑 확인 (TBM 기록)</Button>}
      {detail.status === "SUBMITTED" && <Button size="sm" loading={busy} onClick={() => onApprove(detail.id)}>승인</Button>}
    </>
  );
  return (
    <Modal isOpen onClose={onClose} title={`#${detail.id} ${detail.workName}`} footer={footer} maxWidth="2xl">
      <div className="flex flex-wrap items-center gap-2 text-sm text-slate-600">
        <StatusBadge tone={workPlanStatusTone(detail.status)}>{WORK_PLAN_STATUS_LABEL[detail.status]}</StatusBadge>
        <span>{detail.equipmentName ?? "설비 미상"}</span><span>·</span><span className="tabular-nums">{detail.workDate}</span>
      </div>
      {detail.briefingAckAt && <p className="mt-2 text-sm text-risk-low-text">브리핑 확인 {formatDateTime(detail.briefingAckAt)} — 상시평가 트랙의 TBM 증빙으로 보존됩니다.</p>}
      {detail.slots.length > 0 && (
        <table className="mt-4 w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs text-slate-500"><tr><th className="px-3 py-2 font-semibold">확인 항목</th><th className="w-32 px-3 py-2 font-semibold">대장 기록</th><th className="w-32 px-3 py-2 font-semibold">작업자 확인</th></tr></thead>
          <tbody className="divide-y divide-slate-200">
            {detail.slots.map((s) => (
              <tr key={s.slotKey}>
                <td className="px-3 py-2">{s.question}</td>
                <td className="px-3 py-2">{s.ledgerValue ?? "-"}</td>
                <td className={s.conflicted ? "px-3 py-2 text-risk-high-text" : "px-3 py-2"}>{s.answeredValue ?? "-"}{s.conflicted && " (불일치)"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {detail.briefing && (
        <section className="mt-4 rounded-lg border border-slate-200 bg-slate-50 p-4">
          <h3 className="text-xs font-semibold text-slate-500">작업 전 브리핑</h3>
          <div className="mt-2 text-sm"><AgentMessage text={detail.briefing} /></div>
        </section>
      )}
    </Modal>
  );
}
```

- [ ] **Step 6: `WorkPlanPage.tsx` · `WorkPlanSkeleton.tsx` · `index.tsx`**

```tsx
// WorkPlanPage.tsx — UC3 대화형 작업계획서. 좌 대화 / 우 트레이스가 시연 레이아웃이다.
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import ChatThread from "@/pages/WorkPlan/components/ChatThread";
import Composer from "@/pages/WorkPlan/components/Composer";
import SlotPrompt from "@/pages/WorkPlan/components/SlotPrompt";
import ToolTracePanel from "@/pages/WorkPlan/components/ToolTracePanel";
import WorkPlanDetailModal from "@/pages/WorkPlan/components/WorkPlanDetailModal";
import WorkPlanTable from "@/pages/WorkPlan/components/WorkPlanTable";
import { useAgentStream } from "@/pages/WorkPlan/hooks/useAgentStream";
import { useWorkPlans } from "@/pages/WorkPlan/hooks/useWorkPlans";
import WorkPlanSkeleton from "@/pages/WorkPlan/WorkPlanSkeleton";

export default function WorkPlanPage() {
  const agent = useAgentStream();
  const plans = useWorkPlans();

  if (plans.loading && plans.plans.length === 0) return <WorkPlanSkeleton />;

  const sendTurn = async (message: string, slotKey?: string) => {
    if (slotKey) await agent.answerSlot(slotKey, message); else await agent.send(message);
    plans.refetch(); // 턴이 끝난 뒤 서버 데이터로 목록 갱신
  };

  return (
    <PageLayout>
      <PageHeader title="작업계획서 대화형 등록" description="작업 내용을 말로 설명하면 서식을 채우고, 모르는 값만 되묻습니다."
        actions={<Button variant="secondary" size="sm" onClick={() => { agent.reset(); plans.closeDetail(); }}>새 대화</Button>} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        <section className="flex min-h-[520px] flex-col gap-3">
          <ChatThread turns={agent.turns} streaming={agent.streaming} restoring={agent.restoring} />
          {agent.pendingSlot && <SlotPrompt key={agent.pendingSlot.slotKey} slot={agent.pendingSlot} disabled={agent.streaming} onAnswer={(k, v) => void sendTurn(v, k)} />}
          {agent.error && <Callout tone="high">{agent.error}</Callout>}
          <Composer disabled={agent.streaming} onSend={(m) => void sendTurn(m)} />
          <SectionTitle className="mt-2">작업계획서 목록</SectionTitle>
          <WorkPlanTable plans={plans.plans} onOpen={plans.openDetail} />
        </section>
        <ToolTracePanel rows={agent.trace} connectionState={agent.connectionState} />
      </div>
      <WorkPlanDetailModal detail={plans.detail} busy={plans.busy} onClose={plans.closeDetail} onAcknowledge={plans.acknowledge} onApprove={plans.approve} />
    </PageLayout>
  );
}
```

```tsx
// WorkPlanSkeleton.tsx — 실제 레이아웃과 같은 2열 골격
import PageLayout from "@/components/ui/PageLayout";
import Skeleton from "@/components/ui/Skeleton";

export default function WorkPlanSkeleton() {
  return (
    <PageLayout>
      <Skeleton className="h-7 w-56 mb-2" /><Skeleton className="h-4 w-80 mb-6" />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div className="space-y-3"><Skeleton className="h-80 w-full" /><Skeleton className="h-16 w-full" /><Skeleton className="h-40 w-full" /></div>
        <Skeleton className="h-96 w-full" />
      </div>
    </PageLayout>
  );
}
```

`index.tsx`: `export { default } from "@/pages/WorkPlan/WorkPlanPage";`

- [ ] **Step 7: 삭제 · 확인 · 커밋**

```bash
git rm frontend/src/pages/WorkPlanChatPage.tsx frontend/src/hooks/useAgentStream.ts frontend/src/components/ToolTracePanel.tsx
```
Run: `npm run lint && npm run check:types && npm run test` → PASS. `npm run dev`에서 대화 1턴(데모 모드 가능) 완주·트레이스 점등·목록 갱신·상세 모달 확인.

```bash
git add -A frontend/src
git commit -m "feat(frontend): 작업계획서 화면 재작성 — useSSEStream 기반 에이전트 훅 · 프리미티브 적용

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: 설비 타임라인 화면 (UC4) 재작성

**Files:**
- Create: `src/pages/Timeline/{TimelinePage,TimelineSkeleton,index}.tsx`, `hooks/useTimeline.ts`, `components/{TimelineSummary,TimelineList}.tsx`, `components/__tests__/TimelineList.test.tsx`
- Delete: `src/pages/TimelinePage.tsx`

**Interfaces:**
- `useTimeline()` → `{ equipment: EquipmentItem[], equipmentId: number | null, setEquipmentId, timeline: EquipmentTimeline | null, loading, initialLoaded }`
- `TimelineList({ events, focusId, onFocus })` — 연결 계산(`linkedIds(events, focusId): Set<string>`)은 이 파일의 named export.

- [ ] **Step 1: 실패 테스트** — `components/__tests__/TimelineList.test.tsx`

```tsx
import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import TimelineList, { linkedIds } from "@/pages/Timeline/components/TimelineList";
import type { TimelineEvent } from "@/types/timeline";

const ev = (id: string, linked: string[] = [], emphasis: TimelineEvent["emphasis"] = "NORMAL"): TimelineEvent => ({
  id, type: "ASSESSMENT", at: "2026-09-21", occurredAt: null, title: `사건 ${id}`, detail: "", riskLevel: null,
  accidentType: null, status: null, refId: 1, linkedEventIds: linked, linkedLabels: [], causalOrder: 0, emphasis,
});

describe("TimelineList", () => {
  it("linkedIds는 양방향 연결을 모은다", () => {
    const events = [ev("a", ["b"]), ev("b"), ev("c", ["a"])];
    expect([...linkedIds(events, "a")].sort()).toEqual(["b", "c"]);
  });

  it("사건을 누르면 onFocus에 id를 넘기고, 포커스된 카드는 progress 링을 가진다", () => {
    const onFocus = vi.fn();
    render(<TimelineList events={[ev("a", ["b"]), ev("b")]} focusId="a" onFocus={onFocus} />);
    fireEvent.click(screen.getByText("사건 b"));
    expect(onFocus).toHaveBeenCalledWith("b");
    expect(screen.getByText("사건 a").closest("button")!.className).toContain("ring-progress-border");
  });
});
```
(파일 상단 import에 `vi` 추가: `import { describe, expect, it, vi } from "vitest";`)

- [ ] **Step 2: 실패 확인** — `npm run test -- TimelineList`

- [ ] **Step 3: `hooks/useTimeline.ts`**

```ts
import { useEffect, useState } from "react";

import { equipmentApi } from "@/api/equipmentApi";
import { timelineApi } from "@/api/timelineApi";
import { useApiData } from "@/hooks/useApiData";

export function useTimeline() {
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const [equipmentId, setEquipmentId] = useState<number | null>(null);
  // 설비 목록이 오면 첫 설비를 기본 선택한다
  useEffect(() => { if (equipmentId === null && equipment.data?.length) setEquipmentId(equipment.data[0].id); }, [equipment.data, equipmentId]);
  const timeline = useApiData({
    fetchFn: (s) => timelineApi.byEquipment(equipmentId!, s),
    deps: [equipmentId],
    enabled: equipmentId !== null,
    errorMessage: "타임라인을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  return {
    equipment: equipment.data ?? [],
    equipmentId, setEquipmentId,
    timeline: timeline.data,
    loading: equipment.loading || (timeline.loading && !timeline.initialLoaded),
  };
}
```

- [ ] **Step 4: `components/TimelineSummary.tsx`**

```tsx
import { RiskBadge } from "@/components/ui/Badge";
import Card from "@/components/ui/Card";
import KpiCell from "@/components/ui/KpiCell";
import type { EquipmentTimeline } from "@/types/timeline";

/** 헤드라인 한 줄만 읽혀도 논지가 전달돼야 한다(프로젝터). */
export default function TimelineSummary({ timeline }: { timeline: EquipmentTimeline }) {
  const { equipment, summary } = timeline;
  return (
    <Card title={<span className="flex flex-wrap items-center gap-2">{equipment.name}{summary.currentRiskLevel && <RiskBadge level={summary.currentRiskLevel} />}</span>}
      description={[equipment.locationTag, equipment.processName].filter(Boolean).join(" · ")}>
      <p className="text-stage">{summary.headline}</p>
      <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-5">
        <KpiCell label="위험성평가" value={summary.assessmentCount} />
        <KpiCell label="작업계획서" value={summary.workPlanCount} />
        <KpiCell label="사고" value={summary.incidentCount} tone={summary.incidentCount > 0 ? "high" : undefined} />
        <KpiCell label="미이행 조치" value={summary.unfinishedActionCount} tone={summary.unfinishedActionCount > 0 ? "pending" : undefined} />
        <KpiCell label="기한 경과" value={summary.overdueActionCount} tone={summary.overdueActionCount > 0 ? "high" : undefined} subText={summary.overdueActionCount > 0 ? "즉시 조치" : undefined} />
      </div>
    </Card>
  );
}
```

- [ ] **Step 5: `components/TimelineList.tsx`**

```tsx
import { formUrl } from "@/api/formUrl";
import { Badge, RiskBadge } from "@/components/ui/Badge";
import EmptyState from "@/components/ui/EmptyState";
import cn from "@/lib/cn";
import { ACCIDENT_LABEL } from "@/types/domain";
import { EVENT_LABEL, type TimelineEvent } from "@/types/timeline";
import { formatDate } from "@/utils/datetime";
import { emphasisTone, toneColor } from "@/utils/statusColors";

/** 포커스된 사건과 연결된 사건 — 선 대신 강조로 표현한다. 양방향. */
export function linkedIds(events: TimelineEvent[], focusId: string | null): Set<string> {
  const out = new Set<string>();
  if (!focusId) return out;
  events.find((e) => e.id === focusId)?.linkedEventIds.forEach((id) => out.add(id));
  events.filter((e) => e.linkedEventIds.includes(focusId)).forEach((e) => out.add(e.id));
  return out;
}

function formHref(ev: TimelineEvent): string | null {
  if (ev.type === "ASSESSMENT") return formUrl.assessment(ev.refId);
  if (ev.type === "INCIDENT") return formUrl.incident(ev.refId);
  if (ev.type === "WORK_PLAN") return formUrl.workPlan(ev.refId);
  return null;
}

interface Props { events: TimelineEvent[]; focusId: string | null; onFocus: (id: string | null) => void }

export default function TimelineList({ events, focusId, onFocus }: Props) {
  if (events.length === 0) return <EmptyState message="이 설비에 기록된 사건이 없습니다" />;
  const linked = linkedIds(events, focusId);
  return (
    <ol className="relative ml-2 border-l-2 border-slate-300 pl-6">
      {events.map((ev) => {
        const focused = focusId === ev.id;
        const dot = toneColor(emphasisTone(ev.emphasis)).solid;
        const href = formHref(ev);
        return (
          <li key={ev.id} className="relative mb-4">
            <span className={cn("absolute -left-[31px] top-5 h-3.5 w-3.5 rounded-full ring-4 ring-page", dot)} />
            <button type="button" onClick={() => onFocus(focused ? null : ev.id)}
              className={cn("w-full rounded-lg border bg-white p-4 text-left shadow-card transition-colors",
                focused ? "border-progress-border ring-2 ring-progress-border" : linked.has(ev.id) ? "border-progress-border bg-progress-bg" : "border-slate-200 hover:bg-slate-50")}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs tabular-nums text-slate-400">{formatDate(ev.at)}</span>
                <Badge>{EVENT_LABEL[ev.type]}</Badge>
                <span className="text-stage font-semibold">{ev.title}</span>
                {ev.riskLevel && <RiskBadge level={ev.riskLevel} />}
                {ev.accidentType && <span className="text-xs text-slate-500">{ACCIDENT_LABEL[ev.accidentType]}</span>}
              </div>
              <p className="mt-1 text-sm text-slate-700">{ev.detail}</p>
              {ev.linkedLabels.length > 0 && <p className="mt-1 text-xs text-slate-500">연결된 기록: {ev.linkedLabels.join(" · ")}</p>}
              {href && <a className="mt-2 inline-flex rounded-md bg-slate-100 px-2 py-1 text-xs text-slate-600 hover:bg-slate-200" href={href} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>법정 서식</a>}
            </button>
          </li>
        );
      })}
    </ol>
  );
}
```

- [ ] **Step 6: `TimelinePage.tsx` · `TimelineSkeleton.tsx` · `index.tsx`**

```tsx
import { useState } from "react";

import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import Select from "@/components/ui/Select";
import TimelineList from "@/pages/Timeline/components/TimelineList";
import TimelineSummary from "@/pages/Timeline/components/TimelineSummary";
import { useTimeline } from "@/pages/Timeline/hooks/useTimeline";
import TimelineSkeleton from "@/pages/Timeline/TimelineSkeleton";

/** UC4 — 이 화면이 증명하는 건 기능이 아니라 구조다. 전부 같은 설비 ID에 매달려 있다. */
export default function TimelinePage() {
  const { equipment, equipmentId, setEquipmentId, timeline, loading } = useTimeline();
  const [focusId, setFocusId] = useState<string | null>(null);
  if (loading) return <TimelineSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="설비 타임라인" description="평가 → 작업계획서 → 사고 → 재평가가 하나의 설비 ID 위에서 이어집니다."
        actions={<Select className="w-72" value={equipmentId ?? ""} onChange={(e) => { setEquipmentId(Number(e.target.value)); setFocusId(null); }}>
          {equipment.map((e) => <option key={e.id} value={e.id}>{e.name} — {e.locationTag ?? "-"}</option>)}
        </Select>} />
      {timeline && (
        <div className="space-y-5">
          <TimelineSummary timeline={timeline} />
          <TimelineList events={timeline.events} focusId={focusId} onFocus={setFocusId} />
          <p className="text-xs text-slate-500">사건을 클릭하면 연결된 기록이 함께 강조됩니다. 연결 관계는 서버가 계산한 것입니다.</p>
        </div>
      )}
    </PageLayout>
  );
}
```

Skeleton: PageLayout 안에 `h-7 w-40`, `h-4 w-96`, `h-40 w-full`, 그 아래 `h-28 w-full` 3개. `index.tsx`: `export { default } from "@/pages/Timeline/TimelinePage";`

- [ ] **Step 7: 삭제 · 확인 · 커밋** — `git rm frontend/src/pages/TimelinePage.tsx`, lint/types/test 통과, dev 서버에서 설비 전환·포커스 강조 확인.

```bash
git add -A frontend/src
git commit -m "feat(frontend): 설비 타임라인 화면 재작성 — KpiCell 요약 · 강조도 토큰

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: 사고 등록 화면 (UC2) 재작성

**Files:**
- Create: `src/pages/Incident/{IncidentPage,IncidentSkeleton,index}.tsx`, `hooks/useIncident.ts`, `utils/incidentForm.ts`, `utils/__tests__/incidentForm.test.ts`, `components/{IncidentForm,IncidentResult,IncidentTable}.tsx`
- Delete: `src/pages/IncidentPage.tsx`

**Interfaces:**
- `IncidentFormState = { equipmentId: string; workPlanId: string; occurredAt: string; victimName: string; severity: IncidentSeverity; leaveDays: string; accidentType: AccidentType; description: string }`
- `defaultIncidentForm(): IncidentFormState`, `toRegisterRequest(form): RegisterIncidentRequest` (`utils/incidentForm.ts`)
- `useIncident()` → `{ equipment, plans, incidents, loading, form, setForm, submit(): Promise<void>, busy, response: IncidentRegisterResponse | null, error: string | null }`

- [ ] **Step 1: 실패 테스트** — `utils/__tests__/incidentForm.test.ts`

```ts
import { describe, expect, it } from "vitest";

import { defaultIncidentForm, toRegisterRequest } from "@/pages/Incident/utils/incidentForm";

describe("toRegisterRequest", () => {
  it("휴업일수 빈 문자열은 null이다(0으로 바꾸지 않는다 — 미입력은 '모른다')", () => {
    const req = toRegisterRequest({ ...defaultIncidentForm(), leaveDays: "" });
    expect(req.leaveDays).toBeNull();
  });
  it("설비·작업계획서 미선택은 null, 선택은 숫자", () => {
    const req = toRegisterRequest({ ...defaultIncidentForm(), equipmentId: "3", workPlanId: "" });
    expect(req.equipmentId).toBe(3);
    expect(req.workPlanId).toBeNull();
  });
  it("발생 일시에 오프셋을 붙인다", () => {
    const req = toRegisterRequest({ ...defaultIncidentForm(), occurredAt: "2026-09-21T06:41" });
    expect(req.occurredAt).toMatch(/^2026-09-21T06:41:00[+-]\d{2}:\d{2}$/);
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- incidentForm`

- [ ] **Step 3: `utils/incidentForm.ts`**

```ts
import type { AccidentType, IncidentSeverity } from "@/types/domain";
import type { RegisterIncidentRequest } from "@/types/incident";
import { nowLocalInput, toOffsetIso } from "@/utils/datetime";

export interface IncidentFormState {
  equipmentId: string; workPlanId: string; occurredAt: string; victimName: string;
  severity: IncidentSeverity; leaveDays: string; accidentType: AccidentType; description: string;
}

export function defaultIncidentForm(): IncidentFormState {
  return { equipmentId: "", workPlanId: "", occurredAt: nowLocalInput(), victimName: "", severity: "LOST_TIME", leaveDays: "14", accidentType: "FALL", description: "" };
}

/** 폼 → 등록 요청. 빈 값은 null이다 — 서버가 판단 보류로 처리한다. */
export function toRegisterRequest(f: IncidentFormState): RegisterIncidentRequest {
  return {
    equipmentId: f.equipmentId ? Number(f.equipmentId) : null,
    workPlanId: f.workPlanId ? Number(f.workPlanId) : null,
    occurredAt: toOffsetIso(f.occurredAt),
    victimName: f.victimName || null,
    severity: f.severity,
    leaveDays: f.leaveDays === "" ? null : Number(f.leaveDays),
    accidentType: f.accidentType,
    description: f.description || null,
  };
}
```

- [ ] **Step 4: `hooks/useIncident.ts`**

```ts
import { useEffect, useState } from "react";

import { equipmentApi } from "@/api/equipmentApi";
import { incidentApi } from "@/api/incidentApi";
import { workPlanApi } from "@/api/workPlanApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import type { IncidentRegisterResponse } from "@/types/incident";
import { getServerMessage } from "@/utils/errorMessage";
import { defaultIncidentForm, toRegisterRequest, type IncidentFormState } from "@/pages/Incident/utils/incidentForm";

export function useIncident() {
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const plans = useApiData({ fetchFn: (s) => workPlanApi.list(0, 20, s).then((p) => p.content), deps: [] });
  const incidents = useApiData({ fetchFn: (s) => incidentApi.list(0, 20, s).then((p) => p.content), deps: [], skipFirstSkeleton: true });
  const [form, setForm] = useState<IncidentFormState>(defaultIncidentForm);
  const [response, setResponse] = useState<IncidentRegisterResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useToastStore();

  // 첫 설비를 기본 선택
  useEffect(() => {
    if (!form.equipmentId && equipment.data?.length) setForm((f) => ({ ...f, equipmentId: String(equipment.data![0].id) }));
  }, [equipment.data, form.equipmentId]);

  const submit = async () => {
    setBusy(true); setError(null);
    try {
      setResponse(await incidentApi.register(toRegisterRequest(form)));
      toast.success("사고를 등록했습니다");
      incidents.refetch();
    } catch (e) {
      setError(getServerMessage(e) ?? "사고를 등록하지 못했습니다");
    } finally {
      setBusy(false);
    }
  };

  return { equipment: equipment.data ?? [], plans: plans.data ?? [], incidents: incidents.data ?? [], loading: equipment.loading || plans.loading, form, setForm, submit, busy, response, error };
}
```

- [ ] **Step 5: `components/IncidentForm.tsx`**

```tsx
import Button from "@/components/ui/Button";
import Callout from "@/components/ui/Callout";
import Card from "@/components/ui/Card";
import FormField from "@/components/ui/FormField";
import Input from "@/components/ui/Input";
import Select from "@/components/ui/Select";
import Textarea from "@/components/ui/Textarea";
import type { IncidentFormState } from "@/pages/Incident/utils/incidentForm";
import { ACCIDENT_LABEL, SEVERITY_LABEL, type AccidentType, type IncidentSeverity } from "@/types/domain";
import type { EquipmentItem } from "@/types/equipment";
import type { WorkPlanListItem } from "@/types/workPlan";

interface Props {
  form: IncidentFormState; setForm: (f: IncidentFormState) => void;
  equipment: EquipmentItem[]; plans: WorkPlanListItem[];
  busy: boolean; error: string | null; onSubmit: () => void;
}

export default function IncidentForm({ form, setForm, equipment, plans, busy, error, onSubmit }: Props) {
  const set = <K extends keyof IncidentFormState>(k: K) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setForm({ ...form, [k]: e.target.value });
  return (
    <Card>
      <div className="grid gap-3 md:grid-cols-3">
        <FormField label="사고 설비"><Select value={form.equipmentId} onChange={set("equipmentId")}><option value="">(설비 미상)</option>{equipment.map((e) => <option key={e.id} value={e.id}>{e.name} — {e.locationTag ?? "-"}</option>)}</Select></FormField>
        <FormField label="관련 작업계획서"><Select value={form.workPlanId} onChange={set("workPlanId")}><option value="">(없음)</option>{plans.map((p) => <option key={p.id} value={p.id}>#{p.id} {p.workName} ({p.workDate})</option>)}</Select></FormField>
        <FormField label="발생 일시" required><Input type="datetime-local" value={form.occurredAt} onChange={set("occurredAt")} /></FormField>
        <FormField label="발생형태"><Select value={form.accidentType} onChange={(e) => setForm({ ...form, accidentType: e.target.value as AccidentType })}>{(Object.keys(ACCIDENT_LABEL) as AccidentType[]).map((a) => <option key={a} value={a}>{ACCIDENT_LABEL[a]}</option>)}</Select></FormField>
        <FormField label="재해 정도"><Select value={form.severity} onChange={(e) => setForm({ ...form, severity: e.target.value as IncidentSeverity })}>{(Object.keys(SEVERITY_LABEL) as IncidentSeverity[]).map((s) => <option key={s} value={s}>{SEVERITY_LABEL[s]}</option>)}</Select></FormField>
        <FormField label="휴업일수" hint="3일 이상이면 조사표 제출 대상"><Input type="number" min={0} value={form.leaveDays} onChange={set("leaveDays")} /></FormField>
        <FormField label="재해 경위" className="md:col-span-2"><Textarea rows={2} placeholder="예: 차양부 천장 도장 작업 중 사다리 상부에서 중심을 잃고 약 3.2m 아래로 추락" value={form.description} onChange={set("description")} /></FormField>
        <FormField label="재해자"><Input value={form.victimName} onChange={set("victimName")} /></FormField>
      </div>
      <div className="mt-4 flex items-center gap-3">
        <Button variant="danger" loading={busy} onClick={onSubmit}>사고 등록</Button>
        {error && <Callout tone="high" className="flex-1 py-2">{error}</Callout>}
      </div>
    </Card>
  );
}
```

- [ ] **Step 6: `components/IncidentResult.tsx`** — 결과 순서: ① 소환 배너 ② KPI 3개 ③ 사전 이력·수시평가 2열 ④ 조사표 초안.

```tsx
import { formUrl } from "@/api/formUrl";
import { Badge, RiskBadge } from "@/components/ui/Badge";
import Callout from "@/components/ui/Callout";
import Card from "@/components/ui/Card";
import KpiCell from "@/components/ui/KpiCell";
import { ACCIDENT_LABEL, RISK_LABEL } from "@/types/domain";
import type { IncidentRegisterResponse } from "@/types/incident";
import { dDayLabel, formatDate, formatDateTime } from "@/utils/datetime";
import { reportDutyTone } from "@/utils/statusColors";

function FormLink({ href, children }: { href: string; children: React.ReactNode }) {
  return <a className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50" href={href} target="_blank" rel="noreferrer">{children}</a>;
}

export default function IncidentResult({ r }: { r: IncidentRegisterResponse }) {
  const dutyTone = reportDutyTone(r.reportDuty.status);
  return (
    <div className="space-y-4">
      {/* 이 화면에서 가장 중요한 줄 */}
      <Callout tone={r.recall.predicted ? "high" : "neutral"} title={r.recall.headline}>
        {r.recall.equipmentName}{r.recall.locationTag ? ` · ${r.recall.locationTag}` : ""}
      </Callout>

      <div className="grid gap-3 sm:grid-cols-3">
        <KpiCell label="조사표 제출 기한" value={r.reportDuty.dueDate ? dDayLabel(r.reportDuty.daysRemaining) : r.reportDuty.statusLabel}
          subText={r.reportDuty.dueDate ? `${r.reportDuty.statusLabel} · ${formatDate(r.reportDuty.dueDate)}` : r.reportDuty.basis} tone={dutyTone === "neutral" ? undefined : dutyTone} title={r.reportDuty.basis} />
        <KpiCell label="휴업일수" value={r.incident.leaveDays === null ? "미입력" : `${r.incident.leaveDays}일`} subText="3일 이상이면 조사표 제출" />
        <KpiCell label="수시평가" value={r.followUp.assessmentId ? `#${r.followUp.assessmentId}` : "-"} subText={r.followUp.kindLabel} tone={r.followUp.assessmentId ? "progress" : undefined} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="사고 전 이 설비에 기록돼 있던 사항">
          <h3 className="text-xs font-semibold text-slate-500">위험요인</h3>
          <ul className="mt-1 space-y-1 text-sm">
            {r.recall.priorHazards.length === 0 && <li className="text-slate-400">없음</li>}
            {r.recall.priorHazards.map((h) => (
              <li key={h.hazardId} className={h.sameAxisAsIncident ? "font-medium" : ""}>
                <Badge className="mr-1">{h.accidentType ? ACCIDENT_LABEL[h.accidentType] : "-"}</Badge>{h.missingControl ?? h.description}
                {h.lastRiskLevel && <span className="ml-1 text-slate-500">· 최근 평가 {RISK_LABEL[h.lastRiskLevel]} ({formatDate(h.lastAssessedOn)})</span>}
                {h.sameAxisAsIncident && <span className="ml-1 text-risk-high-text">— 사고와 같은 발생형태</span>}
              </li>
            ))}
          </ul>
          <h3 className="mt-3 text-xs font-semibold text-slate-500">미이행 조치</h3>
          <ul className="mt-1 space-y-1 text-sm">
            {r.recall.unfinishedActions.length === 0 && <li className="text-slate-400">없음</li>}
            {r.recall.unfinishedActions.map((a) => (
              <li key={a.actionId}>{a.content}<span className="ml-1 text-slate-500">(기한 {formatDate(a.dueDate)}{a.overdueDays !== null && a.overdueDays > 0 && <span className="text-risk-high-text"> · {a.overdueDays}일 경과</span>})</span></li>
            ))}
          </ul>
          {r.recall.warnedAt && <p className="mt-2 text-sm text-risk-high-text">작업 전 브리핑으로 경고 전달됨 — {formatDateTime(r.recall.warnedAt)}</p>}
        </Card>

        <Card title={`수시평가 자동 생성${r.followUp.assessmentId ? ` — #${r.followUp.assessmentId}` : ""}`} description={r.followUp.legalBasis}
          actions={r.followUp.assessmentId ? <FormLink href={formUrl.assessment(r.followUp.assessmentId)}>위험성평가표</FormLink> : undefined}>
          <ul className="space-y-2 text-sm">
            {r.followUp.regraded.map((g) => (
              <li key={g.hazardId}>
                <span className="text-slate-500">{g.accidentType ? ACCIDENT_LABEL[g.accidentType] : "-"}</span>{" "}
                {g.before && g.changed ? <><RiskBadge level={g.before} /> <span className="text-slate-400">→</span> <RiskBadge level={g.after} /></> : <><RiskBadge level={g.after} /> <span className="text-xs text-slate-500">유지</span></>}
                {/* 등급 옆에는 항상 룰 트레이스 */}
                <p className="mt-0.5 font-mono text-xs text-slate-500">{g.ruleTrace}</p>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <Card title={<span className="flex items-center gap-2">산업재해조사표 초안{!r.draft.aiGenerated && <Badge>AI 생성 아님</Badge>}</span>}
        actions={<FormLink href={formUrl.incident(r.incident.id)}>산업재해조사표</FormLink>}>
        <h3 className="text-xs font-semibold text-slate-500">재해 발생 원인</h3>
        <p className="whitespace-pre-wrap text-sm">{r.draft.cause}</p>
        <h3 className="mt-3 text-xs font-semibold text-slate-500">재발 방지 계획</h3>
        <p className="whitespace-pre-wrap text-sm">{r.draft.prevention}</p>
        <p className="mt-3 text-xs text-slate-400">{r.draft.disclaimer}</p>
      </Card>
    </div>
  );
}
```

- [ ] **Step 7: `components/IncidentTable.tsx`**

```tsx
import { StatusBadge } from "@/components/ui/Badge";
import DataTable, { type Column } from "@/components/ui/DataTable";
import { ACCIDENT_LABEL } from "@/types/domain";
import type { IncidentListItem } from "@/types/incident";
import { dDayLabel, formatDate, formatDateTime } from "@/utils/datetime";
import { reportDutyTone } from "@/utils/statusColors";

export default function IncidentTable({ incidents }: { incidents: IncidentListItem[] }) {
  const columns: Column<IncidentListItem>[] = [
    { key: "occurredAt", header: "발생 일시", width: "w-40", render: (i) => <span className="tabular-nums">{formatDateTime(i.occurredAt)}</span> },
    { key: "equipment", header: "설비", render: (i) => i.equipmentName ?? "-" },
    { key: "type", header: "발생형태", width: "w-24", render: (i) => (i.accidentType ? ACCIDENT_LABEL[i.accidentType] : "-") },
    { key: "leave", header: "휴업", width: "w-20", render: (i) => <span className="tabular-nums">{i.leaveDays === null ? "미입력" : `${i.leaveDays}일`}</span> },
    { key: "report", header: "조사표", width: "w-28", render: (i) => <StatusBadge tone={reportDutyTone(i.reportStatus)}>{i.reportStatusLabel}</StatusBadge> },
    { key: "due", header: "기한", width: "w-36", render: (i) => <span className="tabular-nums">{formatDate(i.reportDueDate)}{i.daysRemaining !== null && <span className={i.daysRemaining < 0 ? "ml-2 text-risk-high-text" : "ml-2 text-slate-500"}>{dDayLabel(i.daysRemaining)}</span>}</span> },
    { key: "followUp", header: "수시평가", width: "w-24", render: (i) => (i.followUpAssessmentId ? `#${i.followUpAssessmentId}` : "-") },
  ];
  return <DataTable columns={columns} data={incidents} rowKey={(i) => i.id} emptyMessage="등록된 사고가 없습니다" />;
}
```

- [ ] **Step 8: `IncidentPage.tsx` · `IncidentSkeleton.tsx` · `index.tsx`**

```tsx
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import SectionTitle from "@/components/ui/SectionTitle";
import IncidentForm from "@/pages/Incident/components/IncidentForm";
import IncidentResult from "@/pages/Incident/components/IncidentResult";
import IncidentTable from "@/pages/Incident/components/IncidentTable";
import { useIncident } from "@/pages/Incident/hooks/useIncident";
import IncidentSkeleton from "@/pages/Incident/IncidentSkeleton";

/** UC2 — 등록 버튼 하나로 법정 기한 판정·설비 이력 소환·수시평가 생성·조사표 초안이 동시에 일어난다. */
export default function IncidentPage() {
  const s = useIncident();
  if (s.loading) return <IncidentSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="산업재해 등록" description="등록하면 같은 설비의 이력을 소환하고, 수시평가를 만들고, 법정 제출 기한을 계산합니다." />
      <div className="space-y-6">
        <IncidentForm form={s.form} setForm={s.setForm} equipment={s.equipment} plans={s.plans} busy={s.busy} error={s.error} onSubmit={() => void s.submit()} />
        {s.response && <IncidentResult r={s.response} />}
        <section>
          <SectionTitle className="mb-2">사고 목록 · 제출 기한</SectionTitle>
          <IncidentTable incidents={s.incidents} />
        </section>
      </div>
    </PageLayout>
  );
}
```

Skeleton: 제목 2줄 + `h-64 w-full` + `h-40 w-full`. `index.tsx`: `export { default } from "@/pages/Incident/IncidentPage";`

- [ ] **Step 9: 삭제 · 확인 · 커밋** — `git rm frontend/src/pages/IncidentPage.tsx`, lint/types/test 통과, dev 서버에서 사고 1건 등록 → 소환 배너·KPI·목록 갱신 확인.

```bash
git add -A frontend/src
git commit -m "feat(frontend): 사고 등록 화면 재작성 — 소환 배너 Callout · KPI · DataTable

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 12: 사진 판독 화면 (UC1) 재작성

**Files:**
- Create: `src/pages/Vision/{VisionPage,VisionSkeleton,index}.tsx`, `hooks/{useVisionStream,useVision}.ts`, `hooks/__tests__/useVisionStream.test.ts`, `components/{UploadPanel,CandidateCard}.tsx`
- Delete: `src/pages/VisionPage.tsx`, `src/hooks/useVisionStream.ts`

**Interfaces:**
- `useVisionStream()` → `{ result: VisionAnalysisResult | null, progress: string | null, error: string | null, analyzing: boolean, connectionState, analyze(file, equipmentId): Promise<void>, applyDecision(hazardId, adopted) }`
- `useVision()` → `{ equipment, equipmentId, setEquipmentId, rate: AdoptionRate | null, refetchRate, preview: string | null, pick(file), decide(hazardId, adopt), busyId, stream: ReturnType<typeof useVisionStream> }`

- [ ] **Step 1: 실패 테스트** — `hooks/__tests__/useVisionStream.test.ts`

```ts
import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/api/client", () => ({ fetchWithAuth: vi.fn(), ApiError: class extends Error {} }));

import { fetchWithAuth } from "@/api/client";
import { useVisionStream } from "@/pages/Vision/hooks/useVisionStream";

const mockFetch = vi.mocked(fetchWithAuth);
const env = (type: string, seq: number, payload: unknown) =>
  `event: ${type}\ndata: ${JSON.stringify({ type, correlationId: "v1", targetId: null, seq, ts: "t", payload })}\n\n`;
function sseResponse(chunks: string[]): Response {
  const enc = new TextEncoder(); let i = 0;
  return { ok: true, status: 200, body: { getReader: () => ({
    read: async () => (i < chunks.length ? { done: false, value: enc.encode(chunks[i++]) } : { done: true, value: undefined }),
    cancel: async () => {}, releaseLock: () => {} }) } } as unknown as Response;
}

beforeEach(() => mockFetch.mockReset());

describe("useVisionStream", () => {
  it("멀티파트로 올리고 progress → done 순으로 상태를 갱신한다", async () => {
    const result = { assessmentId: 7, status: "DONE", candidates: [], demoMode: false };
    mockFetch.mockResolvedValue(sseResponse([env("assess.progress", 1, { message: "판독 중" }), env("assess.done", 2, result)]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => { await r.current.analyze(new File(["x"], "a.jpg", { type: "image/jpeg" }), 3); });
    const init = mockFetch.mock.calls[0][1]!;
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("equipmentId")).toBe("3");
    expect(r.current.result?.assessmentId).toBe(7);
    expect(r.current.analyzing).toBe(false);
    expect(r.current.progress).toBeNull();
  });

  it("assess.failed면 error에 메시지가 들어가고 analyzing이 끝난다", async () => {
    mockFetch.mockResolvedValue(sseResponse([env("assess.failed", 1, { message: "모델 응답 없음" })]));
    const { result: r } = renderHook(() => useVisionStream());
    await act(async () => { await r.current.analyze(new File(["x"], "a.jpg"), null); });
    expect(r.current.error).toBe("모델 응답 없음");
    expect(r.current.analyzing).toBe(false);
  });
});
```

- [ ] **Step 2: 실패 확인** — `npm run test -- useVisionStream`

- [ ] **Step 3: `hooks/useVisionStream.ts`**

```ts
// 사진 판독 스트림(UC1). 업로드 응답 자체가 SSE다: assess.progress → assess.done | assess.failed.
import { useCallback, useState } from "react";

import { VISION_ANALYZE } from "@/api/endpoints";
import { useSSEStream } from "@/hooks/useSSEStream";
import type { SseErrorPayload } from "@/types/sse";
import type { VisionAnalysisResult } from "@/types/vision";

interface VisionEventMap {
  "assess.progress": { message?: string };
  "assess.done": VisionAnalysisResult;
  "assess.failed": SseErrorPayload;
}

export function useVisionStream() {
  const [result, setResult] = useState<VisionAnalysisResult | null>(null);
  const [progress, setProgress] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);

  const { connectionState, start } = useSSEStream<VisionEventMap>({
    url: VISION_ANALYZE,
    method: "POST",
    handlers: {
      "assess.progress": (p) => setProgress(p?.message ?? "판독 중"),
      "assess.done": (p) => { setResult(p); setProgress(null); },
      "assess.failed": (p) => { setError(p?.message ?? "판독에 실패했습니다"); setProgress(null); },
    },
  });

  const analyze = useCallback(async (file: File, equipmentId: number | null) => {
    setResult(null); setError(null); setProgress("업로드 중"); setAnalyzing(true);
    const form = new FormData();
    form.append("image", file);
    if (equipmentId !== null) form.append("equipmentId", String(equipmentId));
    const outcome = await start(form);
    if (outcome.reason === "error") setError(outcome.error?.message ?? "업로드 실패");
    setProgress(null);
    setAnalyzing(false);
  }, [start]);

  /** 채택·반려 후 서버 값을 그대로 반영한다 */
  const applyDecision = useCallback((hazardId: number, adopted: boolean | null) => {
    setResult((prev) => prev && { ...prev, candidates: prev.candidates.map((c) => (c.hazardId === hazardId ? { ...c, adopted } : c)) });
  }, []);

  return { result, progress, error, analyzing, connectionState, analyze, applyDecision };
}
```

- [ ] **Step 4: `hooks/useVision.ts`**

```ts
import { useEffect, useState } from "react";

import { equipmentApi } from "@/api/equipmentApi";
import { visionApi } from "@/api/visionApi";
import { useApiData } from "@/hooks/useApiData";
import { useToastStore } from "@/stores/useToastStore";
import { getServerMessage } from "@/utils/errorMessage";
import { useVisionStream } from "@/pages/Vision/hooks/useVisionStream";

export function useVision() {
  const equipment = useApiData({ fetchFn: (s) => equipmentApi.list(s), deps: [], errorMessage: "설비 목록을 불러오지 못했습니다" });
  const rate = useApiData({ fetchFn: (s) => visionApi.adoptionRate(s), deps: [], skipFirstSkeleton: true });
  const [equipmentId, setEquipmentId] = useState<number | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const stream = useVisionStream();
  const toast = useToastStore();

  useEffect(() => { if (equipmentId === null && equipment.data?.length) setEquipmentId(equipment.data[0].id); }, [equipment.data, equipmentId]);
  // 판독이 끝나면 채택률을 다시 읽는다 — 서버 값에서만 파생
  useEffect(() => { if (!stream.analyzing) rate.refetch(); }, [stream.analyzing, stream.result]); // eslint-disable-line react-hooks/exhaustive-deps

  const pick = (file: File | undefined) => {
    if (!file) return;
    setPreview(URL.createObjectURL(file));
    void stream.analyze(file, equipmentId);
  };

  const decide = async (hazardId: number, adopt: boolean) => {
    setBusyId(hazardId);
    try {
      const updated = adopt ? await visionApi.adopt(hazardId) : await visionApi.reject(hazardId);
      stream.applyDecision(hazardId, updated.adopted);
      rate.refetch();
    } catch (e) {
      toast.error(getServerMessage(e) ?? "처리하지 못했습니다");
    } finally {
      setBusyId(null);
    }
  };

  return { equipment: equipment.data ?? [], loading: equipment.loading, equipmentId, setEquipmentId, rate: rate.data, preview, pick, decide, busyId, stream };
}
```

- [ ] **Step 5: `components/UploadPanel.tsx`, `components/CandidateCard.tsx`**

```tsx
// UploadPanel.tsx
import { useRef } from "react";

import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import FormField from "@/components/ui/FormField";
import Select from "@/components/ui/Select";
import type { EquipmentItem } from "@/types/equipment";

interface Props { equipment: EquipmentItem[]; equipmentId: number | null; onEquipmentChange: (id: number | null) => void; preview: string | null; analyzing: boolean; progress: string | null; onPick: (f: File | undefined) => void }

export default function UploadPanel({ equipment, equipmentId, onEquipmentChange, preview, analyzing, progress, onPick }: Props) {
  const fileRef = useRef<HTMLInputElement>(null);
  return (
    <Card>
      <FormField label="대상 설비">
        <Select value={equipmentId ?? ""} onChange={(e) => onEquipmentChange(e.target.value ? Number(e.target.value) : null)}>
          <option value="">(설비 지정 없음)</option>
          {equipment.map((e) => <option key={e.id} value={e.id}>{e.name} — {e.locationTag ?? "-"}</option>)}
        </Select>
      </FormField>
      <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={(e) => onPick(e.target.files?.[0])} />
      <div className="mt-3">
        {preview ? <img src={preview} alt="판독 대상" className="w-full rounded-md border border-slate-200 bg-slate-50 object-contain" />
          : <div className="flex h-56 items-center justify-center rounded-md border border-dashed border-slate-300 bg-slate-50 text-sm text-slate-400">사진을 선택하세요</div>}
      </div>
      <Button className="mt-3 w-full" loading={analyzing} onClick={() => fileRef.current?.click()}>{analyzing ? progress ?? "판독 중…" : "사진 선택"}</Button>
    </Card>
  );
}
```

```tsx
// CandidateCard.tsx — 등급 옆에 룰 트레이스를 항상 같이 띄운다. 채택 버튼이 사람의 자리다.
import { Badge, RiskBadge } from "@/components/ui/Badge";
import Button from "@/components/ui/Button";
import Card from "@/components/ui/Card";
import type { VisionCandidate } from "@/types/vision";

interface Props { c: VisionCandidate; busy: boolean; onDecide: (hazardId: number, adopt: boolean) => void }

export default function CandidateCard({ c, busy, onDecide }: Props) {
  return (
    <Card>
      <div className="flex flex-wrap items-center gap-2">
        <Badge>{c.accidentLabel}</Badge>
        <span className="text-stage font-semibold">{c.missingControl}</span>
        <RiskBadge level={c.riskLevel} />
        {c.alreadyKnown && <Badge>기존 위험요인 재확인</Badge>}
        {c.gateStatus === "CHECKLIST" && <Badge variant="pending">참고 — 현장 확인 필요</Badge>}
      </div>
      {c.gateNote && <p className="mt-1 text-xs text-slate-500">{c.gateNote}</p>}
      {c.evidence && <p className="mt-2 text-sm text-slate-700"><span className="text-slate-500">근거: </span>{c.evidence}</p>}
      <p className="mt-2 rounded-md bg-panel px-2 py-1 font-mono text-xs text-slate-600">{c.ruleTrace}</p>
      <div className="mt-3 flex items-center gap-2">
        {c.adopted === null ? (
          <>
            <Button size="sm" loading={busy} onClick={() => onDecide(c.hazardId, true)}>채택</Button>
            <Button size="sm" variant="secondary" disabled={busy} onClick={() => onDecide(c.hazardId, false)}>반려</Button>
            <span className="text-xs text-slate-400">확정은 사람이 합니다</span>
          </>
        ) : (
          <>
            <span className={c.adopted ? "text-sm font-medium text-risk-low-text" : "text-sm font-medium text-slate-500"}>{c.adopted ? "채택됨" : "반려됨"}</span>
            <Button size="sm" variant="subtle" disabled={busy} onClick={() => onDecide(c.hazardId, !c.adopted)}>되돌리기</Button>
          </>
        )}
      </div>
    </Card>
  );
}
```

- [ ] **Step 6: `VisionPage.tsx` · `VisionSkeleton.tsx` · `index.tsx`**

```tsx
import { formUrl } from "@/api/formUrl";
import Callout from "@/components/ui/Callout";
import EmptyState from "@/components/ui/EmptyState";
import KpiCell from "@/components/ui/KpiCell";
import PageHeader from "@/components/ui/PageHeader";
import PageLayout from "@/components/ui/PageLayout";
import CandidateCard from "@/pages/Vision/components/CandidateCard";
import UploadPanel from "@/pages/Vision/components/UploadPanel";
import { useVision } from "@/pages/Vision/hooks/useVision";
import VisionSkeleton from "@/pages/Vision/VisionSkeleton";

/** UC1 — 사진에서 빠진 안전조치를 찾는다. 사고유형 분류가 아니라 물리적으로 있거나 없는 것만 판정한다. */
export default function VisionPage() {
  const v = useVision();
  const { result, progress, error, analyzing } = v.stream;
  if (v.loading) return <VisionSkeleton />;
  return (
    <PageLayout>
      <PageHeader title="현장 사진 판독" description="사진에서 빠진 안전조치를 찾습니다. 물리적으로 있거나 없는 것만 판정합니다."
        actions={v.rate && <KpiCell className="min-w-[180px]" label={`후보 채택률${v.rate.axes?.length ? ` (${v.rate.axes.join("·")})` : ""}`} value={`${v.rate.adopted}/${v.rate.suggested}`} subText={v.rate.rate !== null ? `${Math.round(v.rate.rate * 100)}%` : v.rate.note} title={v.rate.note} />} />
      <div className="grid gap-5 lg:grid-cols-[360px_minmax(0,1fr)]">
        <UploadPanel equipment={v.equipment} equipmentId={v.equipmentId} onEquipmentChange={v.setEquipmentId} preview={v.preview} analyzing={analyzing} progress={progress} onPick={v.pick} />
        <div className="space-y-3">
          {analyzing && <Callout tone="progress" title={progress ?? "판독 중…"}>3~10초 걸립니다. 후보는 제안일 뿐이고 등급은 룰 엔진이 냅니다.</Callout>}
          {error && <Callout tone="high">{error}</Callout>}
          {result?.demoMode && <Callout tone="neutral">데모 모드입니다. API 키가 없어 모델 응답 대신 고정 픽스처를 표시하고 있습니다.</Callout>}
          {result && result.candidates.length === 0 && <EmptyState message="빠진 안전조치를 찾지 못했습니다" description="판정 대상 6축 중 이 사진에 해당하는 항목이 없습니다." />}
          {result?.candidates.map((c) => <CandidateCard key={c.hazardId} c={c} busy={v.busyId === c.hazardId} onDecide={(id, adopt) => void v.decide(id, adopt)} />)}
          {result && <a className="inline-flex items-center rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50" href={formUrl.assessment(result.assessmentId)} target="_blank" rel="noreferrer">위험성평가표 (평가 #{result.assessmentId})</a>}
        </div>
      </div>
    </PageLayout>
  );
}
```

Skeleton: 제목 2줄 + 2열(`h-96 w-full` / `h-40 w-full`×2). `index.tsx`: `export { default } from "@/pages/Vision/VisionPage";`

- [ ] **Step 7: 삭제 · 확인 · 커밋** — `git rm frontend/src/pages/VisionPage.tsx frontend/src/hooks/useVisionStream.ts`, lint/types/test 통과, dev 서버에서 사진 1장 판독(데모 모드) → 후보 카드·채택·채택률 갱신 확인.

```bash
git add -A frontend/src
git commit -m "feat(frontend): 사진 판독 화면 재작성 — useSSEStream 멀티파트 · 후보 카드

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 13: 잔재 정리 · 규칙 문서

**Files:**
- Delete: `src/api/saifeApi.ts`, `src/types/domain.ts`의 임시 `RISK_CLASS`
- Create: `frontend/.claude/rules/design-system.md`, `ui-styling.md`, `architecture.md`
- Modify: `.claude/rules/sse-streaming.md`(프론트엔드 절), `CLAUDE.md`(프로젝트 구조 절)

- [ ] **Step 1: 삭제** — `git rm frontend/src/api/saifeApi.ts`; `domain.ts`에서 `RISK_CLASS` 블록 제거. `grep -rn "saifeApi\|RISK_CLASS\|gray-\|red-\|amber-\|emerald-\|rounded-2xl\|rounded-3xl" frontend/src` 결과가 0건인지 확인(0건이 아니면 고친다).

- [ ] **Step 2: `frontend/.claude/rules/design-system.md`**

```markdown
---
globs: ["src/components/**", "src/pages/**"]
---

# 디자인 시스템 규칙 (SAIFE)

SAIFE는 **무대 밀도** 한 표면이다. 프로젝터(1280×720)에서 읽혀야 한다 — 본문 14px, 트레이스·타임라인·강조 문장은 `text-stage`(15px).

## 색 — 편차에만 쓴다

`tailwind.config.js`의 토큰이 SSOT다. **hex·`red-*`·`amber-*`·`emerald-*`·`gray-*` 금지.** 중립은 `slate-*`.

| 토큰 | 뜻 | 쓰는 곳 |
|---|---|---|
| `risk-high` | 등급 상 · 사고 · 기한 경과 · 오류 | RiskBadge, 사고 소환 배너, 실패 점 |
| `risk-medium` | 등급 중 | RiskBadge |
| `risk-low` | 등급 하 · 성공 토스트 | RiskBadge, 확인됨 표기 |
| `pending` | 되묻기 슬롯 · 승인 대기 · 제출 필요 | SlotPrompt, StatusBadge |
| `progress` | 도구 실행 중 · 활성 네비 · 선택/연결 강조 | 트레이스 점, 사이드바, 타임라인 링 |
| `neutral` | 그 밖의 전부 | Badge 기본, 이벤트 타입 태그 |

상태→클래스 매핑은 `@/utils/statusColors`(`toneColor`, `riskColor`, `workPlanStatusTone`, `emphasisTone`, `reportDutyTone`)로만 한다. 컴포넌트에서 상태를 직접 색으로 분기하지 않는다.

## 형태

- 반경 `rounded`(4) · `md`(8) · `lg`(10) · `xl`(12)까지. `2xl` 이상 금지. 알약(`rounded-full`)은 상태 점만.
- 그림자는 `shadow-card` 하나. `shadow-modal`·`shadow-toast`는 해당 컴포넌트 전용.
- 금지: 그라데이션, `backdrop-blur`(Modal 배경 제외), 이모지, 장식 아이콘, 임의 픽셀 클래스(`text-[10px]` 등).
- 배지는 각진 태그(`Badge`). 카드는 `Card`(흰 배경 + slate-200 테두리).

## 공통 컴포넌트 우선

| 패턴 | 컴포넌트 |
|---|---|
| 페이지 쉘 / 제목 | `PageLayout` / `PageHeader` |
| 카드 / 섹션 제목 | `Card` / `SectionTitle` |
| 안내 상자(배너·슬롯·진행·데모) | `Callout tone=…` |
| 표 | `DataTable` |
| 수치 요약 | `KpiCell` |
| 버튼 · 입력 · 셀렉트 · 여러 줄 | `Button` · `Input` · `Select` · `Textarea` (+`FormField`) |
| 배지 | `Badge` · `RiskBadge` · `StatusBadge` |
| 모달 / 확인 | `Modal` / `ConfirmModal`(+`useConfirm`) |
| 빈 상태 / 로딩 | `EmptyState` / `Skeleton`·`PageSkeleton` |
| 탭 / 세그먼트 | `TabBar` / `SegmentedControl` |
| 토스트 | `useToastStore.getState().success|error|info|warning` |

인라인으로 같은 패턴을 다시 만들지 않는다. 등급 배지 옆에는 **항상 룰 트레이스**를 같이 띄운다.

## Skeleton

`src/pages/{Domain}/{Domain}Skeleton.tsx`. 실제 레이아웃과 같은 골격. 첫 로딩에만 보이고 재조회 때는 보이지 않는다(`useApiData.skipFirstSkeleton`).
```

- [ ] **Step 3: `frontend/.claude/rules/ui-styling.md`**

```markdown
---
globs: ["src/**/*.tsx", "tailwind.config.js"]
---

# UI 스타일링 규칙

## cn()
`@/lib/cn` (clsx + tailwind-merge). 조건부 클래스는 `cn()` 인자로, 외부 `className`은 마지막 인자. 템플릿 리터럴 삼항 금지.

## 클래스 순서
레이아웃 → 크기·여백 → 타이포 → 색·테두리·반경·그림자 → 상호작용(`hover:` `transition`).

## 타이포 (무대 밀도)
| 역할 | 클래스 |
|---|---|
| 페이지 제목 | `text-xl font-bold text-slate-900` |
| 페이지 설명 | `text-sm text-slate-500` |
| 섹션 제목 | `text-base font-semibold text-slate-900` |
| 라벨 | `text-xs font-semibold text-slate-500` |
| 본문 | `text-sm text-slate-900` |
| 메타 | `text-xs text-slate-400` |
| 강조 문장·트레이스·타임라인 제목 | `text-stage` |

## 밀도
- 페이지 컨테이너 `max-w-[1600px] px-6 pt-6 pb-4` (`PageLayout`)
- 표 셀 `px-3 py-2`, 카드 `p-4`, 카드 사이 `gap-4`
- 폰트: Pretendard Variable 자체 호스팅(`src/styles/index.css`). CDN 금지 — 무대 오프라인 대비.

## 토큰 레퍼런스
- 주요 버튼 `bg-slate-900 hover:bg-slate-800`, 위험 버튼 `bg-risk-high`
- 입력 `border-slate-300 focus:border-slate-500 focus:ring-1 focus:ring-slate-500`
- 페이지 배경 `bg-page`, 패널 `bg-panel`, 테두리 `border-slate-200`
```

- [ ] **Step 4: `frontend/.claude/rules/architecture.md`**

```markdown
---
globs: ["src/**/*.ts", "src/**/*.tsx"]
---

# 프로젝트 구조 규칙

```
src/
  api/         client.ts(axios+fetchWithAuth) endpoints.ts {domain}Api.ts formUrl.ts
  components/  ui/(디자인 시스템 프리미티브) layout/(사이드바·헤더) common/(에러 바운더리·AgentMessage)
  hooks/       도메인 비종속 훅 (useApiData · useSSEStream · useDebounce · useConfirm · useBreakpoint · useFormKeyboardNav)
  lib/ stores/ types/ utils/
  pages/{Domain}/
    {Domain}Page.tsx  index.tsx  {Domain}Skeleton.tsx
    components/   페이지 전용 UI
    hooks/        데이터 페칭·SSE·로직 (UI 컴포넌트 안에서 fetch 금지)
    utils/        페이지 전용 순수 함수
```

- `src/components/`에는 **2페이지 이상**이 쓰는 것만 둔다.
- 데이터 페칭은 `useApiData`(REST) · `useSSEStream`(스트림) 위의 도메인 훅으로만. 폴링 금지.
- 목록 갱신은 서버 데이터에서 파생한다 — 낙관적 플래그로 "생성됐다"고 가정하지 않는다.
- 페이지는 `App.tsx`에서 `React.lazy`. `RouteErrorBoundary` → `Suspense(PageSkeleton)` → `Routes`.
- `types/`는 백엔드 DTO와 1:1(camelCase). `any` 금지. import는 `@/` 별칭.
- SSE 3계층: `utils/sseStream`(파서) → `hooks/useSSEStream`(봉투·핸들러) → `pages/*/hooks/use*Stream`(도메인). 규약은 루트 `.claude/rules/sse-streaming.md`.
```

- [ ] **Step 5: 루트 `.claude/rules/sse-streaming.md`의 "## 프론트엔드 규칙" 절을 교체**

```markdown
## 프론트엔드 규칙

### 3계층 구조

| 계층 | 파일 | 역할 |
|---|---|---|
| 프레임 파서 (React 무관) | `src/utils/sseStream.ts` | `readSseStream(response, signal)` — `event:`/`data:` 블록을 async generator로 |
| 봉투 훅 | `src/hooks/useSSEStream.ts` | 봉투 파싱 + `handlers[type]` 디스패치 + `connectionState`. `start(body)`는 스트림이 끝날 때까지 기다리는 Promise |
| 도메인 훅 | `src/pages/WorkPlan/hooks/useAgentStream.ts`, `src/pages/Vision/hooks/useVisionStream.ts` | 이벤트별 상태 갱신, seq 처리, 대화 ID 보존 |

**`EventSource`를 쓰지 않는다.** POST로 시작해야 하고(대화 턴·멀티파트 업로드) 헤더가 필요하다.
**재연결이 없다.** SAIFE의 스트림은 전부 POST라 재연결이 곧 재요청이다. 끊기면 `connectionState`가 `error`로 남고 `SseConnectionStatus`가 표시한다.

### seq 처리

재개 후에도 `seq`가 이어지므로 **역행하는 seq만 중복으로 버린다.** 정렬하지 않고 도착 순서대로 처리한다. 턴 시작 시 `lastSeq=0`으로 초기화한다(도메인 훅 책임).

### 새 SSE 이벤트 추가 절차

1. 백엔드: `SseEvent.of("domain.action", ...)`
2. 프론트: `src/types/sse.ts`의 `SseEventType` union + payload 인터페이스
3. 프론트: 해당 도메인 훅의 `EventMap`과 `handlers`에 case 추가
4. 이 파일의 이벤트 목록 갱신
```

- [ ] **Step 6: `CLAUDE.md` 프로젝트 구조 블록에 프론트 규칙 링크 추가** — `├── frontend/   React 19 + Vite + TypeScript` 줄 아래에:
```
│   └── .claude/rules/   프론트 규칙 (design-system, ui-styling, architecture)
```

- [ ] **Step 7: 확인 · 커밋**

Run: `npm run lint && npm run check:types && npm run test` → PASS

```bash
git add -A
git commit -m "refactor(frontend): 잔재 제거 · 프론트 규칙 문서 3종 · SSE 3계층 규약 갱신

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 14: 빌드 · 전체 스모크 · 스크린샷 검증

**Files:**
- Create: `.gstack/qa-reports/screenshots/redesign-{workplan,incident,timeline,vision}.png`
- Modify(필요 시): 스모크에서 드러난 결함 수정

- [ ] **Step 1: 프로덕션 빌드**

Run: `cd frontend && npm run build`
Expected: 성공. `dist/assets/` 안에 Pretendard woff2 청크가 포함돼 있는지 `ls dist/assets | grep -ci pretendard`로 확인(0이면 폰트 import가 빠진 것).

- [ ] **Step 2: 백엔드 기동(데모 모드)** — `docker compose -f docker-compose.dev.yml up -d` 후 `backend/`에서 `./gradlew bootRun`(키 없이 `SAIFE_DEMO_MODE` 자동 하강). `frontend/`에서 `npm run dev`.

- [ ] **Step 3: 4화면 스모크(브라우저 1280×720)**
  1. `/work-plan`: 레일 접힘 기본 확인. "내일 공장동 후면 차양부에서 사다리 놓고 천장 페인트 칠할 건데요" 전송 → 트레이스 점등 → 되묻기 슬롯 답변 → 목록 갱신 → 열기 → 모달에서 브리핑 확인.
  2. `/incident`: 폼 기본값 그대로 등록 → 소환 배너·KPI 3개·수시평가 카드·목록 갱신.
  3. `/timeline`: 설비 전환 → 요약 KpiCell → 사건 클릭 시 연결 강조.
  4. `/vision`: 사진 1장 선택 → 진행 Callout → 후보 카드 → 채택 → 채택률 갱신.
  5. 새로고침 후 `/work-plan` 대화가 복원되는지.
  6. 404 경로(`/nope`)가 `/work-plan`으로 회수되는지.

- [ ] **Step 4: 스크린샷 4장 저장** — 각 화면을 `.gstack/qa-reports/screenshots/redesign-*.png`로 저장하고 기존 `uc3-continuity.png`·`incident-result-ui.png`·`timeline-after-fix.png`·`vision-initial.png`와 나란히 봐서 (a) `UC#` 꼬리표 없음 (b) 사이드바 (c) 각진 배지 (d) 등급색이 벽돌/황토/저채도녹 인지 확인.

- [ ] **Step 5: 발견 결함 수정 후 최종 커밋**

```bash
git add -A
git commit -m "test(frontend): 리디자인 스모크 · 스크린샷 4장

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## 자체 검토 결과

- **스펙 커버리지**: §3 토큰(T1) · §3.4 타이포(T1, T5, T13) · §4 셸(T8) · §5.1 프리미티브(T3~5) · §5.2 레이아웃/common(T8) · §5.3 훅/유틸(T6, T7) · §5.4 SSE 3계층(T6, T9, T12, T13) · §5.5 테스트(T1~T12) · §6 폴더·규칙 문서(T13) · §7 4화면(T9~T12) · §8 오류 처리(T6 fetchWithAuth, useApiData 토스트, Callout) · §10 검증(T14). InfoTooltip은 이식만 하고 사용처가 없다 — 스펙 목록에 있어 유지.
- **타입 일관성**: `isAbortError`(T6)를 useApiData·useSSEStream·테스트 목이 같은 이름으로 쓴다. `StreamOutcome.reason` 3값을 T9·T12가 그대로 분기한다. `Tone` 6값을 Badge·KpiCell·Callout·statusColors가 공유한다. `useUiStore.toggleSidebar(current)` 시그니처를 GlobalSidebar가 맞춰 호출한다.
- **Review Focus 매핑**: 1→T6 테스트 3번, 2→T9 테스트 2번, 3→T9 테스트 4번, 4→T11 테스트 1번, 5→T7 테스트 4번.
