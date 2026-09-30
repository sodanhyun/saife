---
globs: ["backend/**/repository/**/*.java", "backend/**/*Matcher*.java", "backend/**/*Search*.java"]
---

# 검색·매칭 규칙

SAIFE의 검색은 세 종류뿐이고, 각각 방식이 다르다.

## 1. 설비 매칭 — 가장 중요하고 가장 위험하다

자연어("공장동 후면 차양부")를 설비·장소 레코드에 붙이는 일. 도구 1
`findLocationEquipment`가 이걸 한다.

**false negative가 나면 같은 설비가 두 ID로 쪼개지고, 그 순간 이 출품작 전체가
기대는 "하나의 설비 ID"가 무너진다.** UC4 타임라인 뷰가 눈에 띄게 깨진다.

절차:

1. **1차 완전일치** — 정규화 명칭(공백 제거·소문자화) + 위치 태그
2. **2차 유사도** — Jaro-Winkler (`commons-text`). 임계값 이상이면 **생성하지 말고**
   "혹시 이것입니까?" 후보를 되묻는다
3. **3차 미등록** — 히트가 없으면 `미등록` 반환 → 되묻기 슬롯으로 설비명·위치·종류를 받는다
4. **최종 방어는 DB 유니크 제약** (`uq_equipment_identity`). 앱 로직만 믿지 않는다

유사도 점수로 **자동 선택하지 않는다.** 애매하면 사람에게 묻는다.
잘못 붙은 설비 ID는 조용히 틀리고, 틀린 걸 나중에 알아차리기 어렵다.

## 2. 사례 검색 — 임베딩 + 구조 필터

도구 4 `searchCases`. 캐시된 `public_case` 테이블을 대상으로 한다.

- **주력은 `keyword` 필드의 임베딩 유사도.** `[9/17, 충남 아산시] 차량 유도 작업 중
  후진하는 타이어롤러에 부딪힘` 형태로 정규화되어 있어 매칭에 이상적이다
- **업종 필터를 먼저 건다** — 국내재해사례(1060)는 `business` 필드를 준다
  (건설 39% / 제조 32% / 서비스 20% / 조선 8.4%). 제조업 사례를 찾는데 건설 사례를
  올리면 근거로서 약하다
- 발생형태(`accident_type`)로 2차 필터
- pgvector HNSW / COSINE. 임베딩 모델 `gemini-embedding-2` (**768차원** — 2026-09-20
  당시 1536으로 적었으나 실제 연동 시 768로 확정됐다. `SearchPolicy.EMBEDDING_DIMENSIONS`,
  `VectorCodec`, `application.yml`의 `dimensions: 768`이 근거)
- 실제 검색 파이프라인(근거 계층): 하이브리드 RRF(벡터 0.6 + 키워드 tsquery 0.4, K=60) →
  같은 parent로 병합되는 child는 parent 청크로 확장 → 상위 후보를 Gemini Flash로
  0~10점 리랭크(4점 미만 제외) → **데모 모드(임베딩 불가)에서는 키워드 폴백**으로
  내려간다. 도구 4 `searchCases`뿐 아니라 UC3 브리핑의 조문·지침·MSDS 근거 카드도
  같은 파이프라인을 쓴다. 상세: `docs/superpowers/specs/2026-09-28-evidence-rag-and-connectivity-design.md`

## 3. 일반 목록 조회 — 구조 필터

설비 목록, 평가 이력, 작업계획서 목록 등은 **DB `WHERE` 조건**으로 처리한다.
인메모리 후처리·정렬 없음. 페이징은 DB 페이징(`PageResponse.from`).

이름 검색이 필요하면 `ILIKE '%keyword%'` substring으로 충분하다.
**초성 분해·유사도 점수 매칭을 쓰지 않는다** — 데이터가 한국어 단일이고
검색 대상이 구조화 필드라 필터가 더 정확하고 빠르다.

```java
Page<Equipment> search(Long siteId, Long processId, String nameKeyword, Pageable pageable);
```

## 프론트엔드

- 이름 검색에는 `useDebounce(keyword, 300)`
- 설비 선택은 자유 입력이 아니라 **후보 목록에서 고르게** 한다 (중복 생성 방지)
