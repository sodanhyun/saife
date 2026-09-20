# Notion Docs 문서 작성 (ADR / TroubleShooting)

이번 세션에서 구현한 내용을 바탕으로 Notion의 Docs 데이터베이스에 ADR 또는 TroubleShooting 문서를 작성합니다.

## 인자

- `$ARGUMENTS`: 작성할 문서 유형과 주제 (예: "ADR 검사 데이터 원자성", "TS JWT 토큰 만료 처리")
  - 미지정 시 현재 브랜치의 구현 내용을 분석하여 적절한 유형을 자동 판단

## Notion 대상 정보

- **Docs 데이터베이스 data_source_id**: `⟨확인 필요: SAIFE Docs DB data_source_id — 첫 사용 시 API-post-search로 조회·검증⟩`
- **Project relation**: SAIFE 프로젝트 페이지 URL (첫 사용 시 확인)

> ⚠️ 첫 사용 전에 반드시 `API-post-search`로 SAIFE Docs DB를 검색하여 실제 data_source_id를 확인하고 이 파일의 플레이스홀더를 교체하세요.

## 문서 유형별 양식

### ADR (Architecture Decision Record)

```
Title: ADR-SAIFE-{번호}
title(optional): {결정 요약}
Type: ADR
Status: Accepted | Proposed | Deprecated

내용 구조:
## Context       — 배경/문제 상황
## Decision      — 결정 사항
## Alternatives  — 검토한 대안들 (각 대안의 기각 사유 포함)
## Rationale     — 결정 근거
## Trade-Off(Optional) — 비교 테이블 (기존 vs 신규)
## Consequences  — 결과/영향
```

### TroubleShooting

```
Title: TroubleShooting-SAIFE-{번호}
title(optional): {문제 요약}
Type: TroubleShooting

내용 구조:
## 문제              — 발생한 문제 설명
## 원인(Optional)    — 근본 원인 분석
## 해결              — 해결 방법 + 코드 스니펫
## 한계 및 트레이드오프 — 해결의 한계점
## 결과              — 적용 결과
```

## 실행 절차

### Phase 1: 컨텍스트 수집

1. 현재 브랜치의 커밋 히스토리 확인 (변경된 리포별)
2. 변경된 파일과 diff 요약 분석
3. 관련 스펙/플랜 문서 확인 (`.superpowers/specs/`, `.superpowers/plans/`)

### Phase 2: 기존 문서 번호 확인

1. Notion에서 기존 ADR/TroubleShooting 문서 검색 (`API-post-search`)
2. `ADR-SAIFE-*` 또는 `TroubleShooting-SAIFE-*` 최신 번호 확인 후 다음 번호 부여

### Phase 3: 문서 작성

1. 구현 내용의 성격에 따라 문서를 분리 가능 (하나의 구현이 여러 결정을 포함하는 경우)
   - **아키텍처 패턴 선택** → ADR
   - **상태 모델/데이터 모델 변경** → ADR
   - **실제 발생한 버그/문제 해결** → TroubleShooting
   - **성능 개선** → TroubleShooting 또는 ADR

2. 작성 규칙:
   - 코드 스니펫은 핵심 변경 부분만 포함 (Before/After 비교 권장)
   - Trade-Off 테이블은 Notion table 태그 사용
   - 관련 PR 번호를 Consequences/결과 섹션에 포함
   - 한국어로 작성 (기술 용어는 영문 유지)

### Phase 4: Notion 생성

`API-post-page` 도구로 SAIFE Docs 데이터베이스에 페이지 생성:

- parent: `{ type: "data_source_id", data_source_id: "⟨확인 필요: SAIFE Docs DB data_source_id⟩" }`
- properties 필수 항목:
  - `Title`: `"ADR-SAIFE-{N}"` 또는 `"TroubleShooting-SAIFE-{N}"`
  - `title(optional)`: `"{요약}"`
  - `Type`: `"ADR"` 또는 `"TroubleShooting"`
  - `Status`: `"Accepted"` (ADR의 경우)
  - `Project`: SAIFE 프로젝트 관계 (첫 사용 시 확인)
- content: Notion-flavored Markdown으로 양식에 맞게 작성

### Phase 5: 결과 보고

- 생성된 문서 URL 출력
- 문서 목록 테이블 (제목, 유형, URL)
