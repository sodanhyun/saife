---
globs: ["backend/**/controller/**/*.java", "backend/**/dto/**/*.java", "frontend/src/types/**/*.ts", "frontend/src/api/**/*.ts"]
---

# API 계약 규칙 (프론트-백엔드 통합)

## 필드명 매핑

- 백엔드 DTO 필드명(camelCase, Jackson 기본) = 프론트엔드 인터페이스 필드명
- `@JsonProperty`를 쓰면 프론트 인터페이스도 그 이름으로 통일

## 타입 매핑

| Java (백엔드 DTO) | TypeScript (프론트엔드) |
|-------------------|----------------------|
| `Long`, `Integer` | `number` |
| `String` | `string` |
| `Boolean` | `boolean` |
| `LocalDate` | `string` (`YYYY-MM-DD`) |
| `LocalDateTime`, `OffsetDateTime` | `string` (ISO 8601) |
| `BigDecimal` | `number` |
| `List<T>` | `T[]` |
| `PageResponse<T>` | `PaginationResponse<T>` |
| `enum` | union 타입 |

도메인 enum은 프론트에서도 union으로 1:1 유지한다:

```ts
type AccidentType   = "FALL" | "CAUGHT" | "DROP" | "STRUCK" | "FIRE" | "PPE";
type RiskLevel      = "HIGH" | "MEDIUM" | "LOW";
type AssessmentKind = "INITIAL" | "OCCASIONAL" | "REGULAR" | "ROUTINE";
type WorkPlanStatus = "DRAFT" | "SUBMITTED" | "APPROVED" | "CONDITIONAL" | "REJECTED" | "CLOSED";
```

## 페이징 응답 규칙

- 컨트롤러에서 `Page<T>`를 직접 반환하지 않는다 → 반드시 `PageResponse.from(page)`
- 백엔드 `io.saife.common.dto.PageResponse<T>` (record) ↔ 프론트 `PaginationResponse<T>` (`src/types/common.ts`)

```java
return ResponseEntity.ok(PageResponse.from(service.findPage(...)));
```

## Response 구조 변경 시

백엔드 DTO 필드를 추가·삭제·변경하면 프론트 `src/types/`도 **같은 커밋에서** 고친다.
프론트에서 안 쓰는 필드도 인터페이스에 남겨 전체 구조를 유지한다.

## API 경로 규칙

- 백엔드 `@RequestMapping("/api/{domain}/...")`, 프론트 `src/api/{domain}Api.ts`
- dev는 Vite 프록시, prod는 nginx가 `/api` → `backend:8080` 전달

| 경로 | 모듈 |
|---|---|
| `/api/agent/**` | 에이전트 대화 · 되묻기 슬롯 재개 |
| `/api/equipment/**` `/api/process/**` | 데이터 코어 |
| `/api/assessment/**` `/api/hazard/**` `/api/action/**` | 위험성평가 |
| `/api/work-plan/**` | UC3 작업계획서 |
| `/api/incident/**` | UC2 사고 |
| `/api/dashboard/**` | UC4 설비 타임라인 |

## 에러 응답

- 401 → 프론트에서 로그인 페이지 리다이렉트
- 403 → 권한 부족 안내
- 백엔드 공통 에러 구조에 맞춰 프론트에서 핸들링

## 인증·권한

**SAIFE는 권한 카탈로그를 쓰지 않는다.** 역할 2종뿐이고 가상 사업장 1곳이라 테넌시도 없다.

| 역할 | 할 수 있는 것 |
|---|---|
| `WORKER` | 작업계획서 작성·제출, 브리핑 확인, 아차사고 보고 |
| `MANAGER` | 위 전부 + 작업계획서 승인, 평가 확정, 사고 등록, 대시보드 |

`@PreAuthorize("hasRole('MANAGER')")` 수준으로 충분하다. 세분화된 원자 권한·카탈로그·
해시 동기화를 만들지 않는다 — 이번 범위에서 보상받지 못하는 표면적이다.

## SSE 응답 타입 매핑

| Java (백엔드) | TypeScript (프론트엔드) |
|--------------|----------------------|
| `SseService.SseEvent` record | `SseEnvelope<T>` (`src/types/sse.ts`) |
| `SseEvent.of(type, corrId, targetId, payload)` | `useAgentStream` 훅이 파싱 |
| `SseEvent.type` | `SseEventType` union — `domain.action` 형태 |
| `SseEvent.payload` | 이벤트별 payload 인터페이스 |
| `SseEvent.targetId` | `string \| null` |

새 SSE 이벤트를 추가하면 `SseEventType` union과 payload 인터페이스를 같이 추가한다.
상세: `.claude/rules/sse-streaming.md`
