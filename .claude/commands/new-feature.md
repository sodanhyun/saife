# 풀스택 기능 추가

새 기능을 프론트엔드와 백엔드 양쪽에 추가합니다: $ARGUMENTS

> **참조 스텁**: 실제 코드 패턴은 `backend/src/main/java/com/taelim/saife/domain/_example/`와 `frontend/src/pages/_example/`를 리빙 레퍼런스로 사용합니다.

## 개발 순서

### Phase 1: 백엔드 API 개발 (backend/)

1. **Entity 설계** + Flyway 마이그레이션
   - `backend/src/main/resources/db/migration/V{다음번호}__{설명}.sql`
   - **반드시 `ls backend/src/main/resources/db/migration/`을 실행하여 현재 가장 높은 V번호를 확인하고 +1 사용** (추측 금지, 번호 충돌 방지)
   - `IF NOT EXISTS` 멱등성 확보
   - `SoftDeletableEntity` 상속 (`deleted` + `deletedAt`)

2. **DTO 생성** (`io.saife.{module}/dto/`)
   - Request: `@Getter @Setter`
   - Response: `@Getter @Builder`

3. **Repository** (`io.saife.{module}/repository/`)
   - 태그 주석 필수: `// [태그] 메서드 목적 — 핵심 조건/동작 설명`
   - `deleted = false` 조건 필수
   - N+1 방지: `JOIN FETCH` 또는 `@EntityGraph`

4. **Service** (`io.saife.{module}/service/`)
   - `@RequiredArgsConstructor` + `final` 필드 주입
   - 명명 컨벤션: `get*()`, `find*()`, `create*()`, `update*()`, `delete*()`
   - `@Transactional(readOnly = true)` 조회, `@Transactional` 변경

5. **Controller** (`io.saife.{module}/controller/`)
   - `@RestController` + `@RequestMapping("/api/{domain}")`
   - `@PreAuthorize` 또는 SecurityConfig 권한 매핑

6. **SecurityConfig 업데이트** (필요 시)
   - 역할: `INSPECTOR` / `EXPERT` (SAIFE 2-role 체계)
   - `ROLE_EXPERT`는 전체 접근, `ROLE_INSPECTOR`는 자기 할당 데이터

7. **단위 테스트** 작성

### Phase 2: 프론트엔드 연동 (frontend/)

1. **TypeScript 인터페이스** (`frontend/src/types/{domain}.ts`)
   - 백엔드 Response DTO와 1:1 매핑
   - `any` 타입 금지

2. **API 함수** (`frontend/src/api/{domain}Api.ts`)
   - Axios 기반 순수 fetch 함수

3. **페이지 구조** (FSD 패턴, `_example` 스텁 참조)
   ```
   frontend/src/pages/{Feature}/
   ├── {Feature}Page.tsx
   ├── hooks/use{Feature}.ts
   └── components/
   ```
   - 커스텀 훅에 AbortController 클린업 필수
   - 컴포넌트는 UI 렌더링만 담당

4. **라우팅** (`frontend/src/App.tsx`)
   - Route 추가, 필요 시 PrivateRoute 적용

5. **네비게이션 메뉴** 추가 (해당 시)

### Phase 3: 통합 확인

- 백엔드 실행: `cd backend && ./gradlew bootRun`
- 프론트엔드 실행: `cd frontend && npm run dev`
- API 통신 정상 확인 (Vite 프록시 `/api` → `localhost:8080`)
- JWT 인증 흐름 확인

### 비동기 작업 + SSE 기능인 경우

`.claude/rules/sse-streaming.md`의 "비동기 작업 SSE 연결 패턴" 섹션 필수 준수:
- **백엔드**: HTTP 스레드에서 "진행 중" 상태를 DB에 REQUIRES_NEW로 선커밋 → `@Async` 디스패치
- **프론트엔드**: API 후 refetch → `hasXxx` 서버 데이터 파생 → `enabled: hasXxx` → SSE 자동 연결
- 낙관적 플래그(`isSending`, `isProcessing`), always-on SSE 금지
