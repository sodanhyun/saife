# API 계약 동기화 점검

점검 대상: $ARGUMENTS

## 점검 절차

### 1. 백엔드 DTO 확인
- `backend/src/main/java/com/taelim/saife/{module}/dto/` 내 Response DTO 필드 확인
- 필드명, 타입, nullable 여부 파악

### 2. 프론트엔드 타입 확인
- `frontend/src/types/` 내 대응하는 인터페이스 필드 확인
- 페이지 전용 타입이 있다면 `frontend/src/pages/{Domain}/types/`도 확인

### 3. 불일치 탐지

| 점검 항목 | 설명 |
|----------|------|
| 필드명 차이 | camelCase 변환 오류, 오타 |
| 누락된 필드 | 백엔드에 있으나 프론트에 없는 필드 (또는 반대) |
| 타입 불일치 | `Long` ↔ `number`, `LocalDateTime` ↔ `string` 등 |
| nullable 차이 | 백엔드 nullable ↔ 프론트엔드 optional(`?`) 불일치 |
| enum 값 차이 | 백엔드 enum 상수 ↔ 프론트엔드 union 타입 값 |

### 4. API 경로 확인
- 백엔드 `@RequestMapping` / `@GetMapping` / `@PostMapping` 경로
- 프론트엔드 `frontend/src/api/{domain}Api.ts` 내 호출 경로
- 일치 여부 확인

### 5. 페이지네이션 구조 확인 (해당 시)
- 백엔드 `PageResponse<T>` 응답 구조 (`io.saife.common.dto.PageResponse`)
- 프론트엔드 `PaginationResponse<T>` 타입 매핑 (`frontend/src/types/common.ts`)

불일치 발견 시 양쪽 수정 방안을 제시합니다.
