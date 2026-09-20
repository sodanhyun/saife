---
globs: ["frontend/src/api/**/*.ts", "frontend/src/types/**/*.ts", "backend/**/dto/**/*.java", "backend/**/controller/**/*.java"]
---

# API 계약 규칙 (프론트-백엔드 통합)

## 필드명 매핑

- 백엔드 DTO 필드명(camelCase, Jackson 기본) = 프론트엔드 인터페이스 필드명
- `@JsonProperty` 사용 시 프론트엔드 인터페이스도 해당 이름으로 통일

## 마스터 식별자·이름 컬럼 명칭 규약 (전 계층 단일 어휘 — 2026-07-17 확정)

기준정보/설정 마스터의 **식별자·표시이름 컬럼은 DB 컬럼명 → 엔티티 → DTO → 프론트 타입 → i18n 라벨까지 한 어휘로 통일**한다(운영 정합). 새 마스터를 추가할 때도 이 규약을 따른다.

| 개념 | DB 컬럼 | 엔티티/DTO/프론트 필드 | i18n 헤더 |
|------|---------|----------------------|----------|
| 표시이름 | `name` | `name` | `col.name` = "이름" |
| 자연 비즈니스코드(자기 식별자) | `code` | `code` | `col.code` = "코드" |
| 대리키(BIGINT identity, 비즈니스코드 없음) | `id` | `id` | `col.id` = "ID" |

- **`label`·`description`·`key`·`cause_key`·VARCHAR `id` 등 변형 금지** — 표시이름=`name`, 자연코드=`code`로 수렴. `key`는 SQL 예약어라 `code`가 정합·안전.
- **대리키 마스터**(business_partner·material·production_line·product·inspection_trigger 등)는 비즈니스코드가 없으므로 `id`(surrogate) 유지, 헤더 "ID". "자연코드=코드 / 대리키=ID"의 **규칙 일관성**.
- **FK 컬럼은 관계 의미 보존**(rename 대상 아님): `spec_item_key`·`symptom_key`·`category_id`·`param_code`·`cause_ref`는 "이 측정이 속한 검사항목" 등 관계 어휘라 유지. 마스터 자기 컬럼 통일과 별개 레이어.
- **그래프(Neo4j) 노드 속성명**은 그래프 어휘(예 Cause 노드의 `label`)로 유지하되 **PG 원천 컬럼은 `name`/`code`** — 투영 매퍼 한 곳에서만 매핑(neo4j-kb.md).
- **DB 컬럼 rename은 전용 Flyway 마이그레이션**(예 V21 `RENAME COLUMN`)으로, `-- ROLLBACK:` + R 스크립트 동반. 값(value)은 불변, 컬럼명만. PostgreSQL이 FK/UNIQUE/PK 제약을 자동 추종한다.
- **동시 반영 필수**: 이 rename은 하위호환이 아니므로(필드명 자체 변경) 백엔드 Response/Request DTO와 프론트 타입을 **같은 사이클에 함께** 반영한다(deployment.md 순서 준수).

## 타입 매핑

| Java (백엔드 DTO) | TypeScript (프론트엔드) |
|-------------------|----------------------|
| `Long`, `Integer` | `number` |
| `String` | `string` |
| `Boolean` | `boolean` |
| `LocalDateTime` | `string` (ISO 8601 형식) |
| `List<T>` | `T[]` |
| `PageResponse<T>` | `PaginationResponse<T>` (content, number, size, totalPages, totalElements) |
| `enum` | union 타입 (`"INSPECTOR" \| "EXPERT"`) |

## 페이징 응답 규칙

- 컨트롤러에서 `Page<T>`를 직접 반환하지 않음 → 반드시 `PageResponse.from(page)`로 변환
- 백엔드: `io.saife.common.dto.PageResponse<T>` (record)
- 프론트엔드: `PaginationResponse<T>` (`src/types/common.ts`)
- 사용 예: `ResponseEntity.ok(PageResponse.from(service.findPage(...)))`

## Response 구조 변경 시

- 백엔드 DTO 필드 추가/삭제/변경 시, 프론트엔드 `src/types/` 인터페이스도 **동시에** 수정
- 프론트엔드에서 사용하지 않는 필드도 인터페이스에 포함하여 전체 구조 유지 권장

## API 경로 규칙

- 백엔드: `@RequestMapping("/api/{domain}/...")` 패턴
- 프론트엔드: `src/api/{domain}Api.ts`에서 경로 문자열로 직접 사용
- Vite 프록시가 `/api` → `localhost:8080` 전달

## 에러 응답

- 백엔드 공통 에러 구조에 맞춰 프론트엔드에서 에러 핸들링
- 401 Unauthorized → `authStore.logout()` → 로그인 페이지 리다이렉트
- 403 Forbidden → 권한 부족 안내

## SSE 응답 타입 매핑

| Java (백엔드) | TypeScript (프론트엔드) |
|--------------|----------------------|
| `SseService.SseEvent` record | `SseEnvelope<T>` (`src/types/sse.ts`) |
| `SseEvent.of(type, corrId, targetId, payload)` | `useSSEStream` 훅이 자동 파싱 |
| `SseEvent.type` (String) | `SseEnvelope.type` (string) — `domain.action` 형태 |
| `SseEvent.payload` (Object) | `SseEnvelope.payload` (T) — EventMap에서 타입 추론 |
| `SseEvent.targetId` (String) | `SseEnvelope.targetId` (string \| null) |

## 권한 프로토콜 (2026-07-29 확정 — 권한 프레임 재설계)

권한 코드는 `resource:action` 형태의 **단일 문자열**이며 백엔드 `PermissionResource` enum이 SSOT다. 현재 규모는 **31 리소스 · 111 원자**(해시 `89fab582` — 2026-08-18 KB 버전 UI 폐지 + `admin.dataset:retry` 폐지). 규모·해시는 카탈로그를 바꿀 때마다 움직이므로 **문서 수치가 아니라 `permission-catalog.json`의 `hash`를 기준으로 판단한다.**

> **menuGroup 폐기(2026-08-11)**: 카탈로그는 키·액션만 소유한다. 권한 편집 UI의 그룹핑·순서·1:1 라벨의 정본은 프론트 `MENU_TREE`(+콘솔 `consoleTabOrder.ts`)이며, 노드의 유일 리소스는 행 라벨을 메뉴 i18n 키로 자동 위임한다(`permissionGridModel.SOLE_RESOURCE_NODE_I18N` — 위임 리소스는 `perm.resource.*` 라벨을 정의하지 않는다, `permLabels.test.ts`가 강제).

| 항목 | 백엔드 | 프론트 |
|------|--------|--------|
| 카탈로그 SSOT | `rbac/catalog/PermissionResource` | `src/types/permissions.generated.ts` (자동 생성 — 직접 수정 금지) |
| 동기화 | `./gradlew exportPermissionCatalog` → `permission-catalog.json` 커밋 | `npm run gen:permissions` → 생성 파일 커밋 |
| 정합 강제 | `PermissionCatalogSnapshotTest`(enum↔JSON) | `npm run check:permissions`(JSON↔생성물) + `generatedCatalog.test.ts` 해시 리터럴 고정 |
| 배포 스큐 감지 | `/api/auth/me`의 `catalogHash` | 번들 `CATALOG_HASH` 대조 → `CatalogSkewBanner` 경고 |
| 인가 게이트 | `@RequirePermission` + 기동 커버리지 검증(선언 누락·고아 원자 → 기동 실패) | 3계층(라우트 403 / 메뉴·탭 숨김 / 액션 비활성+안내) — `src/auth/menuTree.ts` 단일 트리 파생 |

### 권한 API 계약

| 엔드포인트 | 응답/요청 |
|-----------|----------|
| `GET /api/auth/me` | `{ userId, loginId, userName, role, permissions[], catalogHash }` |
| `GET /api/auth/permission-catalog` | `{ hash, resources: [{ key, allowedActions[] }] }` (인증 필요 — menuGroup은 2026-08-11 폐기) |
| `GET /api/admin/users/{id}/permissions` | `{ rolePreset[], grants[], denies[], effective[] }` |
| `PUT /api/admin/users/{id}/permissions` | `{ grants[], denies[] }` 전량 치환 |
| `GET /api/admin/role-presets` | `{ presets, userCounts, adminAtomCount }` (ADMIN 미포함) |
| `PUT /api/admin/role-presets/{role}` | `{ codes[] }` 전량 치환 |

### 규칙

- **유효권한 = ADMIN 전권 하드가드 | (역할 프리셋 ∪ GRANT) − DENY** — **DENY가 모두를 이긴다.** 권한은 JWT 클레임이 아니라 요청마다 재해석된다(변경 즉시 반영).
- **권한 원자 추가·삭제 절차(5단계)**: ① `PermissionResource` 수정 ② `exportPermissionCatalog` 실행·JSON 커밋 ③ 프론트 `gen:permissions` ④ `MENU_TREE`에 노출(가시성 제외 대상은 `groupOnlyResources`) ⑤ ko·zh 라벨(`perm.resource.*`·`perm.action.*`) 추가. 하나라도 빠지면 백엔드 기동 실패 또는 프론트 테스트 실패로 드러난다.
- **엔드포인트와 enum은 같은 커밋에** — 고아 권한 검사가 양방향이라 카탈로그만 먼저 추가하면 기동이 실패한다.
- **참조 옵션(`/api/masters/*/options`)은 `@AuthenticatedOnly`** — 다른 리소스 화면이 이름 표시·드롭다운 선택지로 소비하는 경량 조회라 자기 read 원자를 요구하면 부분 권한 사용자의 참조 화면이 403으로 깨진다(2026-07-30 QA ISSUE-001). 표(페이징)·CRUD 게이트는 유지하며, read 원자는 표 엔드포인트가 계속 참조하므로 고아가 되지 않는다. 새 마스터의 옵션 경로도 이 규약을 따른다.
- **프론트 권한 체크는 보안이 아니라 UX다.** 실제 인가는 백엔드가 전담한다.
