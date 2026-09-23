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
