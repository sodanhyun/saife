# 검색 기능 구현 규칙

SAIFE는 **초성/유사도 인메모리 매칭을 사용하지 않는다**(KoreanSearchUtil 폐기). 데이터가 한/중/영 혼재이고 검색 대상이 구조화 필드·다국어 이름이므로, ① 케이스 = **구조 필터**, ② 기준정보 이름 = **다국어 안전 ILIKE substring**으로 구현한다.

## 왜 초성 매칭을 폐기하는가

- 데이터가 한국어 단일이 아니라 **한/중/영 혼재**(중국 지사) — 한글 초성 분해가 무의미하거나 편향.
- 검색 대상이 자유텍스트 이름이 아니라 **구조화된 케이스 속성**(호기/상태/날짜/고객/LOT)이 대부분 → 필터가 초성 매칭보다 정확·빠름.

## 케이스 검색 = 구조 필터 (DB WHERE)

- 검사 케이스 목록은 **구조 필터**로만 조회한다: 호기(machine)·상태(state)·날짜 범위(insp_date)·고객(customer)·LOT.
- JPQL/QueryDSL `WHERE` 조건으로 처리, 인메모리 후처리 없음. 페이징은 DB 페이징(`PageResponse.from`).

```java
// 구조 필터 — 인메모리 스마트매칭 없음.
Page<InspectionCase> search(Long machineId, CaseState state,
                            LocalDate from, LocalDate to,
                            Long customerId, String lot, Pageable pageable);
```

## 기준정보 이름 검색 = 다국어 안전 ILIKE substring

- 기준정보(고객/재질/공급사/파라미터 이름 등) 이름 검색은 **`ILIKE '%keyword%'` substring**으로 한/중/영 균일 처리. 대소문자 무시(`ILIKE`), 부분일치.
- 초성 분해·유사도 점수·인메모리 정렬 없음. 정렬은 이름 오름차순 또는 관련성 단순 규칙(시작일치 우선 정도).

```java
// 다국어 안전 substring — 한/중/영 균일.
@Query("select m from Customer m where m.name ilike concat('%', :kw, '%')")
List<Customer> searchByName(@Param("kw") String kw);
```

- Postgres 문자셋은 UTF8/ICU(und) — 다국어 저장·정렬 안전(deployment/charset 규칙). 언어별 정렬 필요 컬럼만 `COLLATE "ko-x-icu"`/`"zh-x-icu"`.

## 프론트엔드

- 검색 바 placeholder는 초성 안내 문구 제거 → 대상에 맞는 일반 문구(i18n 키). 케이스 목록은 `FilterBar`(구조 필터) 중심.
- `useDebounce(keyword, 300)`(이름 검색), `SearchInput`·`Select`·`FilterBar`·`RefreshButton`·`useUrlSync` 공통 컴포넌트/훅 사용.
