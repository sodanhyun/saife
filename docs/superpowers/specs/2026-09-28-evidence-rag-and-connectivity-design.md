# 근거 계층(Evidence/RAG) + 연결성 개선 통합 설계

- 작성일: 2026-09-28 · 기준 커밋: af9327e · 상태: 사용자 검토 대기 · 2026-09-28 Inufleet 검색 구조 이식 반영(§4.1, §5, §5.6)
- 동반 문서: `docs/SAIFE_연결성_개선_설계서.docx`, `docs/SAIFE_연결성_개선_프롬프트.md` (변경 ①~④의 세부 구현 지시는 그 문서가 그대로 유효하다. 이 스펙은 근거 계층 설계 전체와 두 작업의 통합 지점·순서·지표를 정한다)
- 작업 기간: 2026-09-29 ~ 10-04(코드 프리즈) · 10-05 문서 · 10-06 제출

---

## 1. 목표와 범위

**목표.** 에이전트의 모든 주장(유사 사고, 지침, 법 조문, 물질 정보)이 실제 출처 원문으로 이어지고, 사진·PDF·조문·GHS 픽토그램이 화면에 카드로 붙는다. 검색은 pgvector 벡터 검색이고, 외부 API는 실제로 호출하되 키가 없거나 네트워크가 죽어도 캐시로 동작한다. 여기에 연결성 개선(설비 홈·회상 카드·사고 연쇄·오늘 할 일·시드 이야기)을 같은 기간에 함께 넣는다.

**사용자 결정(2026-09-28 확정).**

| 질문 | 결정 |
|---|---|
| 런타임 외부 호출 정책 | 라이브 우선 + 캐시 폴백. 카드에 origin(LIVE/CACHE)과 조회 시각 표시 |
| 임베딩 시점 | 개발 중 사전 계산해 시드에 동봉. 런타임은 질의 임베딩만(키 없으면 키워드 폴백) |
| 사진·PDF 보관 | DB에는 URL만. 백엔드 온디맨드 프록시 + 디스크 캐시 |
| 화면 범위 | UC3 대화 + UC2 사고 등록 + UC1 사진 판독 후보 |
| 접근 방식 | A. 백엔드 근거 계층 + 구조화 SSE 이벤트(모델은 근거 번호만 인용) |
| 연결성 개선 | 같은 기간에 통합. 회상 카드·사고 연쇄·UC3 근거·시드 V8은 절단 불가 |

**범위 밖.** 인증, 설비 병합 액션, 재해사례 첨부(API 폐기), 토큰 스트리밍, 수동 도구 루프 전환.

---

## 2. 확인된 사실 (2026-09-28 실측)

| 소스 | 라이브 | 시각 자료 | 원문 링크 | 비고 |
|---|---|---|---|---|
| 사고사망 1040 (2,940건) | 정상 | 전 건 `<img src='https://portal.kosha.or.kr/api/compn24/auth/stdtboard/getImage.do?...'>` (PNG, 약 300KB, 공개 URL로 다운로드 확인) | 게시글 딥링크 없음 | 현재 `CaseTextCleaner`가 `<img>`를 삭제한다. 시드 9,312건 중 이미지 URL 보존 0건 |
| 국내재해사례 1060 (6,372건) | 정상 | `atcflcnt=1`이지만 첨부 API 1070은 `NO_OPENAPI_SERVICE_ERROR`(서비스 폐기). 포털 다운로드는 내부 POST `stdtboard/process.do`로 비공개 | 포털 목록 페이지 `https://portal.kosha.or.kr/archive/disaster-case/accident-case` (딥링크 없음) | 첨부는 포기. 사진은 1040으로 충당 |
| KOSHA GUIDE 1050 (1,039건) | 정상 | `fileDownloadUrl` → PDF 다운로드 확인(application/pdf, 약 500KB) | PDF URL | 본문 텍스트 추출 대상 |
| MSDS `getChemList001` / `getChemDetail0{N}1` | 정상(XML) | GHS 그림문자 파일명(`GHS02.gif` 등) | 공단 MSDS 화면 링크 | 톨루엔 chemId=001032 실측 |
| 법제처 `lawSearch.do` / `lawService.do?target=law&LM=&JO=` | 정상(JSON, OC 유효) | 없음 | `법령상세링크` 제공 | 조문 JSON은 `조문단위{조문번호, 항[{항번호, 항내용}]}`. `JO`가 장 제목(조문여부=전문)을 돌려주는 경우가 있어 시드 크롤은 법령 전체(`MST`) 1회 호출로 수집 |

---

## 3. 아키텍처

```
브라우저 ── REST/SSE ──► Spring Boot
                          ├─ ai/agent      AgentService(턴 앞뒤) · EvidenceLedger(대화별 근거 원장) · CitationSanitizer
                          ├─ ai/tools      @Tool 6종(변경 없음, 내부가 EvidenceSearchService를 호출)
                          ├─ evidence/     EvidenceSearchService · EvidenceChunkRepository · IndexBuilder · SeedExporter
                          │                LawArticleService · MsdsLiveClient · LiveOrCache · MediaController · MediaCache
                          ├─ publicapi/    PublicApiCrawler(이미지 URL 보존 · PDF 텍스트 추출) · PublicCacheSeedLoader(벡터·조문 시드)
                          ├─ dashboard/    cards · recall · today (연결성)
                          └─ incident/     cascade · affectedWorkPlans · similarCases
PostgreSQL 16 + pgvector: evidence_chunk(vector 768, HNSW cosine) · law_article · public_case(+image_url) · msds_cache(+pictograms)
외부: KOSHA(1040/1050/1060, MSDS) · 법제처 · 미디어 원본(portal.kosha.or.kr)
```

원칙: 근거는 **백엔드가 만들고 번호를 매긴다.** 모델은 `[#n]`만 쓴다. 카드·회상·연쇄·오늘 할 일은 전부 DB 조회 결과로만 만든다(연결성 원칙 4·5와 동일).

---

## 4. 데이터 모델 (Flyway V9__evidence_schema.sql)

V8은 연결성(`work_plan.warning_note`, 고소작업대 이야기 시드)이 쓴다. V9가 근거 계층이다. 검색 구조는 Inufleet(`C:\Users\taeli\taelim\tealim-be`, `ai/chat/rag`·`ai/embedding`)에서 검증된 것을 이식하되, 그쪽에서 드러난 함정(§5.6)은 처음부터 피한다.

```sql
ALTER TABLE public_case
  ADD COLUMN image_url  TEXT,          -- 1040 원문의 첫 <img src>. 없으면 NULL
  ADD COLUMN source_url TEXT;          -- 1060: 포털 목록 페이지. 1040: NULL(딥링크 없음)

ALTER TABLE msds_cache ADD COLUMN pictograms VARCHAR(200);  -- 'GHS02,GHS07,GHS08' (02 항목 응답에서 추출)

CREATE TABLE law_article (
  id            BIGSERIAL PRIMARY KEY,
  law_id        VARCHAR(20)  NOT NULL,   -- 법제처 법령ID (예: 001766)
  law_name      VARCHAR(100) NOT NULL,   -- 산업안전보건법 / 산업안전보건법 시행규칙 / 산업안전보건기준에 관한 규칙
  article_no    INTEGER      NOT NULL,   -- 조문번호
  article_sub   INTEGER      NOT NULL DEFAULT 0,  -- 조문가지번호(제36조의2 → 2)
  paragraph_no  INTEGER      NOT NULL DEFAULT 0,  -- 항번호(없으면 0 = 조 전체)
  title         VARCHAR(300),            -- 조문제목
  text          TEXT         NOT NULL,   -- 항 원문(항이 없으면 조문내용)
  effective_on  DATE,
  source_url    TEXT,                    -- 법령상세링크 + 조문 앵커
  fetched_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (law_id, article_no, article_sub, paragraph_no)
);

CREATE TABLE evidence_chunk (
  id            BIGSERIAL PRIMARY KEY,
  kind          VARCHAR(20)  NOT NULL,   -- CASE_FATALITY / CASE_DISASTER / GUIDE / LAW
  ref_id        BIGINT       NOT NULL,   -- public_case.id / kosha_guide.id / law_article.id
  ref_key       VARCHAR(120) NOT NULL,   -- source_key / guide_no#c3 / law_id:조:항 (시드 재적재 시 매칭 키)
  chunk_level   VARCHAR(10)  NOT NULL DEFAULT 'child',  -- child / parent (Inufleet Parent-Child)
  parent_id     BIGINT       REFERENCES evidence_chunk(id),
  searchable    BOOLEAN      NOT NULL DEFAULT true,     -- parent=false. NULL 배제 문제를 피하려고 불리언으로 명시
  section_title VARCHAR(300),
  title         VARCHAR(500) NOT NULL,
  text          TEXT         NOT NULL,   -- 검색·임베딩 텍스트 = [맥락 프리픽스]\n원문(+오버랩)
  metadata      JSONB        NOT NULL DEFAULT '{}'::jsonb,
  embedding     vector(768),             -- parent는 NULL (임베딩하지 않는다)
  tsv           tsvector GENERATED ALWAYS AS (
                  to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(section_title,'') || ' ' || text)
                ) STORED,                -- 생성 컬럼이라 어떤 INSERT 경로에서도 빠지지 않는다
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (kind, ref_key)
);
CREATE INDEX ix_evidence_chunk_embedding ON evidence_chunk USING hnsw (embedding vector_cosine_ops) WHERE searchable;
CREATE INDEX ix_evidence_chunk_tsv  ON evidence_chunk USING gin (tsv);
CREATE INDEX ix_evidence_chunk_kind ON evidence_chunk (kind, searchable);
CREATE INDEX ix_evidence_chunk_meta ON evidence_chunk USING gin (metadata);

CREATE TABLE conversation_evidence (
  id              BIGSERIAL PRIMARY KEY,
  conversation_id VARCHAR(40) NOT NULL,
  turn_no         INTEGER     NOT NULL,
  evidence_no     INTEGER     NOT NULL,   -- 대화 내 유일 번호 (#n)
  payload         JSONB       NOT NULL,   -- EvidenceDto 직렬화
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (conversation_id, evidence_no)
);

CREATE TABLE work_plan_evidence (
  work_plan_id BIGINT  NOT NULL REFERENCES work_plan(id),
  evidence_no  INTEGER NOT NULL,
  payload      JSONB   NOT NULL,
  PRIMARY KEY (work_plan_id, evidence_no)
);
```

Spring AI의 `vector_store` 자동 테이블은 쓰지 않는다. `spring.ai.vectorstore.pgvector.initialize-schema`는 `false`로 바꾼다. `ddl-auto: validate`이므로 엔티티(`PublicCase`, `MsdsCache`, `LawArticle`, `EvidenceChunk`, `ConversationEvidence`, `WorkPlanEvidence`)를 같은 커밋에서 갱신한다. `embedding`·`tsv` 컬럼은 JPA 매핑 없이 네이티브 쿼리(JdbcTemplate)로만 다룬다(엔티티에는 `columnDefinition`만 명시해 validate 통과).

### 4.1 청킹 규칙 (Inufleet `ChunkOverlapUtil`·Parent-Child 이식)

| kind | Parent | Child | 오버랩 | 맥락 프리픽스 |
|---|---|---|---|---|
| CASE_FATALITY / CASE_DISASTER | 없음(사례 1건이 자기 완결) | 1건 = 1청크, `keyword + " " + contents` | 없음 | 없음. 대신 제목에 `[발생형태 라벨] [업종]`을 넣어 tsvector가 축·업종 단어로도 맞게 한다 |
| GUIDE | 섹션(제목 휴리스틱: `제N장`, 숫자 접두, 로마숫자, 60자 이하 제목줄) 단위, 없으면 연속 child 5개. 텍스트만 저장, `searchable=false`, 임베딩 안 함 | 약 1,200자(최소 300, 3,000 초과 시 재분할), PDFBox `PDFTextStripper` 페이지 순서 유지 | 이전 청크 꼬리 200자를 문장 경계(`. \n ? ! 다`)에서 잘라 앞에 붙임 | **적용.** `ContextualEnricher`가 지침 앞 3,000자 + 청크 주변 ±2,500자를 보고 "이 청크가 어느 섹션·주제인지" 50~150자 한 줄을 만들어 `[…]\n` 프리픽스로 붙인다(Gemini Flash, temperature 0, maxOutputTokens 150, thinkingBudget 0). 실패 시 원문 그대로 |
| LAW | 조(條) = parent(항 전체 합본, `searchable=false`) | 항 1개 = child. 항 없는 조는 child 1개(parent 없음) | 없음 | 없음. 제목 `산업안전보건법 제36조(위험성평가의 실시) ①`이 곧 맥락 |

Inufleet과 달리 **parent는 임베딩하지 않는다**(검색 대상이 아닌데 임베딩·enrich 비용을 내던 낭비 지점). child 1개짜리 그룹은 parent 없이 child만 둔다. 예상 청크: 사례 9,312 + 지침 child 약 5,000(+ parent 약 1,200) + 조문 child 약 1,500(+ parent 약 600) ≈ 17,600행, 임베딩은 약 15,800건. 768차원 float32 → 약 48MB, `evidence_chunk.jsonl.gz` 약 50MB.

**시드 파일.** `backend/src/main/resources/seed/`에 `law_article.jsonl.gz`, `evidence_chunk.jsonl.gz`(각 줄: `{kind, refKey, chunkLevel, parentRefKey, searchable, sectionTitle, title, text, metadata, embedding(base64 float32 LE, parent는 null)}`) 추가. `public_case.jsonl.gz`는 `imageUrl, sourceUrl` 추가로 재생성. `PublicCacheSeedLoader` 적재 순서: public_case → kosha_guide → law_article → evidence_chunk(parent 먼저, child가 `parentRefKey`로 parent id 해석). 500건 배치 네이티브 INSERT(`?::vector`). 차원을 바꾸면 시드·컬럼·인덱스를 한 커밋에서 같이 바꾼다(Inufleet V85가 staging 테이블을 빠뜨려 두 번 고친 지점).

---

## 5. 검색 — `EvidenceSearchService` (Inufleet `AgenticRagService` 파이프라인 이식)

```java
public record SearchRequest(String query, Set<EvidenceKind> kinds, AccidentType accidentType,
                            String business, int k, boolean rerank)
public record Evidence(int no, EvidenceKind kind, Long refId, String refKey, String title, String snippet,
                       String sourceUrl, String mediaUrl, String thumbnailUrl,
                       Origin origin /*LIVE|CACHE|KEYWORD_FALLBACK*/, double score, OffsetDateTime fetchedAt,
                       Map<String,Object> meta)
```

파이프라인 (한 호출):

```
질의 → ① 후보 수집: 벡터 top k×4  ‖  키워드 top k×4      (kinds·축·업종 필터, searchable=true)
     → ② 가중 RRF 합산 (0.6 / 0.4, K=60) → 상위 k×4
     → ③ Parent 확장 (child→parent, 같은 parent는 1건으로, 확장 실패 시 child 유지)
     → ④ LLM 리랭크 (Gemini Flash, 0~10점, 4점 미만 제외) → 상위 k
     → ⑤ 도메인 보정 (+같은 축 0.05, +제조업 0.03, +사진 0.02) → Evidence 변환
```

1. **벡터 후보**: `QueryEmbedder.embed(query)`(인터페이스, 구현 `GeminiQueryEmbedder` 768차원, 8초 타임아웃, 회로 차단). `SELECT …, 1 - (embedding <=> :q) AS sim FROM evidence_chunk WHERE searchable AND kind = ANY(:kinds) AND (:axis IS NULL OR metadata->>'accidentType' = :axis) AND 1 - (embedding <=> :q) >= 0.40 ORDER BY embedding <=> :q LIMIT :k4`. 실패·키 없음 → 빈 리스트(예외를 밖으로 내지 않는다).
2. **키워드 후보**(`KeywordSearchRepository`, JdbcTemplate 격리): Inufleet의 `plainto_tsquery`(모든 토큰 AND)는 조사가 붙은 한국어에서 거의 안 맞았다. 여기서는 `TsQueryBuilder`가 질의를 공백·구두점으로 나눠 2자 이상 토큰 최대 8개를 **OR**로 묶고(`'사다리' | '천장' | '페인트'`), 명사 뒤 조사(`은/는/이/가/을/를/에서/으로/와/과/의`)를 벗긴 변형을 함께 넣는다. `WHERE tsv @@ to_tsquery('simple', :q) AND searchable AND kind = ANY(:kinds) … ORDER BY ts_rank_cd(tsv, to_tsquery('simple', :q)) DESC LIMIT :k4`. 실패 → 빈 리스트.
3. **RRF**: `score[id] += 0.6/(60+rank_vec+1)`, `+= 0.4/(60+rank_kw+1)` → 내림차순 상위 k×4(`HybridRrf`, 순수 함수).
4. **Parent 확장**(`ParentChunkExpander`): child 중 `parent_id`가 있는 것은 parent로 치환(같은 parent 여러 child → 1건, 원래 RRF 순위 중 최고를 유지). parent 조회 실패 시 **child를 그대로 남긴다**(Inufleet은 여기서 child를 버렸다). 사례는 parent가 없으므로 그대로.
5. **LLM 리랭크**(`LlmReranker`): 후보가 k 이하면 건너뜀. 입력 상한 40건, 문서당 1,500자(맥락 프리픽스는 유지, 리랭커에 유용). 프롬프트는 Inufleet `prompts/rag-rerank.txt`를 그대로 가져와 `backend/src/main/resources/prompts/evidence-rerank.txt`에 둔다(0~10점 기준표, "JSON 정수 배열만"). 옵션: `model(gemini-3.8-flash)`, `temperature(0.0)`, `maxOutputTokens(500)`, `responseMimeType("application/json")`, **`thinkingBudget(0)`**(안 끄면 Flash가 JSON 지시를 무시한 것이 Inufleet 실측). 파싱: 첫 `[`~마지막 `]` 추출 → `int[]` → 실패 시 객체 배열의 첫 숫자 필드 → **길이가 문서 수와 다르면 폴백**(Inufleet은 int[] 경로에서 길이를 안 봐서 꼬리가 조용히 잘렸다). 점수 내림차순, 4점 미만 제외, 상위 k. 어떤 예외든 RRF 순서 상위 k로 폴백. 데모 모드(키 없음)에서는 이 단계를 건너뛴다.
6. **도메인 보정과 변환**: 같은 발생형태 +0.05, 제조업 +0.03, 사진 있음 +0.02(사례만). 같은 지침(guideNo)은 1건만. `Evidence`로 변환하면서 맥락 프리픽스를 벗긴다(`stripContextualPrefix`: 앞의 `[…]\n`을 200자 이내에서 반복 제거). `snippet` 200자, `sourceUrl`/`mediaUrl`/`thumbnailUrl`은 §6.5. `origin`: 벡터가 있었으면 CACHE(LIVE는 라이브 API 카드에만), 키워드만 썼으면 KEYWORD_FALLBACK.

호출 비용: 임베딩 1회(300ms) + 리랭크 1회(Flash, 약 1초) + SQL 2회(각 10ms) ≈ 1.5초. UC3 한 턴에 최대 3회이므로 도구 병렬화 없이도 5초 이내. `rerank=false`로 호출하면(UC1 후보별 검색처럼 다건일 때) 리랭크를 생략해 300ms로 끝낸다.

### 5.6 Inufleet에서 미리 피하는 함정

| Inufleet에서 겪은 것 | SAIFE에서의 처리 |
|---|---|
| QNA 경로가 `updateTsvector`를 안 불러 키워드 검색이 빠짐 | `tsv`를 생성 컬럼으로 두어 INSERT 경로와 무관하게 채워진다 |
| `chunkLevel != 'parent'` 필터가 NULL 행을 배제 | `searchable` 불리언 NOT NULL |
| `plainto_tsquery` AND 매칭이 한국어 조사에 약함 | OR 매칭 + 조사 제거 변형, `ts_rank_cd` |
| parent 조회 실패 시 child 유실 | child 유지 |
| 리랭크 입력 80건×1,500자 | 상한 40건 |
| 리랭크 `thinkingBudget` 기본값으로 JSON 무시, `maxOutputTokens 100`으로 배열 잘림 | `thinkingBudget(0)`, `maxOutputTokens 500` |
| `int[]` 파싱이 길이 불일치를 안 봄 | 길이 검사 후 폴백 |
| parent까지 enrich·임베딩해 비용 낭비 | parent는 텍스트만 |
| HyDE(예상 질문 프리픽스)로 의미 격차 발생 | 사실 기반 맥락 프리픽스(Contextual Enrichment)만 |
| 대화 이력 기반 쿼리 재작성이 키워드를 희석 | 재작성 없음. 모델이 도구 파라미터 `query`로 직접 질의를 쓴다 |
| 차원 변경 시 staging 테이블을 빠뜨림 | 시드·컬럼·인덱스를 한 커밋에서 |
| yml에 실제 API 키가 기본값으로 박힘 | 기본값은 빈 문자열만. Inufleet 키는 회전 권장 |
| 출처 행에 fileId가 없어 이름으로 역조회 | `Evidence.refId/refKey`를 항상 저장 |
| 규칙 문서와 코드가 어긋남 | `.claude/rules/ai-tool-calling.md`에 검색 파라미터를 상수 이름과 함께 적고, 테스트가 상수를 참조한다 |

---

## 6. 외부 연동

### 6.1 `LiveOrCache`
```java
<T> Fetched<T> fetch(String host, Supplier<T> live, Supplier<Optional<T>> cache, Consumer<T> store)
// Fetched(T value, Origin origin, OffsetDateTime fetchedAt, String note)
```
- 키 있음 && 회로 닫힘 → live(타임아웃 8초) → 성공 시 store, origin=LIVE.
- 실패·타임아웃·키 없음 → cache → origin=CACHE. 캐시도 없으면 `Fetched.empty(note)`.
- 회로: 호스트별 연속 실패 3회 → 60초 열림. `Caffeine`(이미 의존성 있음)으로 상태 보관.

### 6.2 MSDS — `MsdsLiveClient`
- `resolve(productName)`: 기존 `MsdsResolver` 성분 추정 유지 → `getChemList001?searchWrd=` 라이브 → 첫 `chemId` → `getChemDetail021/051/071/081` 4콜 병렬(`CompletableFuture`) → `msds_cache` upsert(pictograms 포함). XML은 JDK `XMLInputFactory`로 파싱(의존성 추가 없음).
- 폴백: `msds_cache`(시드는 톨루엔 + 크롤로 확보한 제품 약 20종을 `msds_cache.jsonl.gz`로 동봉).

### 6.3 법제처 — `LawArticleService`
- 시드 크롤(1회, 관리자 엔드포인트): `lawSearch.do?query=` → `MST` → `lawService.do?target=law&MST=&type=JSON` 전체 조문 → `law_article` upsert. 대상 3법령: 산업안전보건법(001766), 산업안전보건법 시행규칙, 산업안전보건기준에 관한 규칙.
- 런타임 `get(lawName, articleNo, articleSub)`: LiveOrCache로 `JO=` 호출 → 시행일이 캐시와 다르면 갱신. 카드 origin에 반영.
- 축별 고정 인용 `LawCitationTable`(Java, 룰 엔진과 같은 방식): FALL → 안전보건규칙 제42·43·44조, 산안법 제38조 / CAUGHT → 규칙 제87·92조 / DROP → 규칙 제14·174조 / STRUCK → 규칙 제172·179조 / FIRE → 규칙 제239·241·422조 / PPE → 규칙 제32조 / 공통 → 산안법 제36조, 시행규칙 제37조(위험성평가 기록) / 사고 후 → 시행규칙 제73조(조사표), 산안법 제36조(수시평가). 조문 존재는 시드 크롤 후 테스트로 고정한다(번호 오류가 있으면 테스트가 잡는다).

### 6.4 공단 사례·지침
- `PublicApiCrawler`: 1040 저장 시 `image_url`(첫 `<img src>`), `CaseTextCleaner`는 텍스트만 정리. 1060은 `source_url`=포털 목록 URL. 1050은 PDF를 받아 `PdfTextExtractor`(PDFBox)로 텍스트 추출 → GUIDE 청크 생성(파일은 저장하지 않음). 새 건은 키가 있으면 즉시 임베딩.
- `checkLatest(dataset)`: 1페이지 `numOfRows=1`로 최신 등재 건수·시각만 확인(카드 메타 "공단 최신 등재 기준"). 회로 차단 적용, 실패 시 생략.
- 의존성 추가: `org.apache.pdfbox:pdfbox:3.0.5` 1개(`external-library.md`에 추가 사유 기록).

### 6.5 미디어 프록시 — `MediaController` / `MediaCache`
- `GET /api/media/case/{caseId}/photo[?w=320]`, `GET /api/media/guide/{guideNo}.pdf`.
- 원본 URL은 DB에서만 가져온다(파라미터로 URL을 받지 않는다). 허용 호스트 `portal.kosha.or.kr`. 캐시 경로 `${saife.media-dir:./data/media}/case/{id}.png`, `{id}_320.jpg`, `guide/{guideNo}.pdf`. 응답 `Cache-Control: public, max-age=86400`, PDF는 `Content-Disposition: inline`.
- 썸네일은 `javax.imageio` + `java.awt.Image.getScaledInstance`로 320px 폭 JPEG(품질 0.8).
- 원본 실패 → 404 + 로그. 프론트는 사진 없는 카드로 degrade.
- `POST /api/admin/media/prefetch?scenario=demo`: 제조업 사례 상위 200건 사진 + 축별 지침 30건 PDF를 미리 캐시(리허설 전 1회).

### 6.6 관리자 엔드포인트
- `POST /api/admin/index/rebuild?kind=CASE|GUIDE|LAW|ALL` — 청크 재생성 + 임베딩(키 필요, 진행률 `crawl_checkpoint` 재사용).
- `GET /api/admin/index/export` — `evidence_chunk.jsonl.gz`·`law_article.jsonl.gz`·`public_case.jsonl.gz`·`msds_cache.jsonl.gz`를 `./data/export/`에 쓴다. 개발자가 `seed/`로 복사해 커밋.
- `POST /api/admin/law/crawl` — 3법령 수집.
- `GET /api/system/status` → `{demoMode, embeddingAvailable, lastCrawlAt, evidenceChunkCount, circuitOpenHosts}` (프론트 상태 줄).

---

## 7. 에이전트·도구·SSE

### 7.1 도구 변경(6종 유지)
| 도구 | 변경 |
|---|---|
| `searchCases(accidentType, business?, query?)` | `query` 파라미터 추가. `EvidenceSearchService`(kinds=CASE_*) k=3. 결과 줄: `#12 [사진] [7/28 경남 함양] 설비 내 슬러지 제거 중 스크류에 끼임 (유사도 84%, 캐시 09-21)` |
| `analyzeHazards(...)` | 축 도출은 그대로. 지침: kinds=GUIDE k=3(축 키워드 + 작업유형 질의). 조문: `LawCitationTable` 고정 매핑 → `law_article` 원문 → 근거 번호. 결과에 `#n` 병기 |
| `getMsds(productName)` | `MsdsLiveClient` → 근거 번호 1개(MSDS 카드) + 02/05/07/08 요약 + GHS 코드 |
| 나머지 3종 | 변경 없음. `createWorkPlan`은 원장의 근거 전부를 `work_plan_evidence`에 저장 |

### 7.2 `EvidenceLedger`
- `ToolCallContext`와 같은 static 맵(대화별). `register(conversationId, Evidence) → no`. 번호는 대화 전체에서 유일(DB `conversation_evidence`의 max+1에서 이어감). 같은 `(kind, refKey)`가 다시 나오면 기존 번호 재사용.
- 턴 종료: `AgentService`가 `ledger.newInTurn(conversationId)`를 `ai.evidence {items}`로 발행 → `conversation_evidence` 저장 → 원장 턴 경계 갱신.
- 다음 턴 시스템 프롬프트 끝: `[이미 제시한 근거] #1 제목, #2 제목 …`(최대 20개, 제목 40자).

### 7.3 `CitationSanitizer`
최종 텍스트의 `\[#(\d+)\]`를 스캔해 원장에 없는 번호는 제거(대괄호째), 있는 번호는 유지. 데모 스크립트 문장에도 적용. 단위 테스트 필수.

### 7.4 시스템 프롬프트 추가
```
[근거 인용]
- 사고사례·지침·법 조문·MSDS를 언급할 때는 도구가 준 근거 번호를 문장 끝에 [#n] 형식으로 붙이세요.
- 번호가 없는 출처를 지어내지 마세요. URL, 파일명, 사진을 직접 쓰지 마세요.
```
연결성 2-1의 "첫 문장은 회상" 규칙도 같은 프롬프트에 들어간다.

### 7.5 SSE 이벤트(추가 2종, `.claude/rules/sse-streaming.md` 갱신)
| 이벤트 | 발행 지점 | payload |
|---|---|---|
| `ai.recall` | `findLocationEquipment` 매칭 성공 시 도구 레벨 | `RecallView`(+knownSlots) — 연결성 2-1 |
| `ai.evidence` | 턴 종료, `ai.token` 뒤 `ai.done` 앞 | `{items: Evidence[]}` |

### 7.6 UC2 사고 등록
`IncidentService.register` 5.5단계: `EvidenceSearchService.search(description, kinds=CASE_*, axis, business=제조업, k=3)` → `RecallView.similarCases: Evidence[]`. `IncidentReportDrafter` 프롬프트에 3건 + 조문(사고 후 매핑)을 번호와 함께 넣고 `[#n]` 요구, `CitationSanitizer` 적용. `RegisterResponse.evidence: Evidence[]`(사례 3 + 조문 2). 연결성 cascade 1단계 RECALL 카드 안에 유사 사례 그리드가 들어간다.

### 7.7 UC1 사진 판독
`VisionAssessmentService.persist` 후 후보마다 `search(missingControl + ' ' + axis 라벨)`로 GUIDE 1 + LAW 1(고정 매핑) + CASE 1(사진 있는 것 우선) → `VisionCandidate.evidence: Evidence[]`. 모델 호출 없음.

---

## 8. 화면

### 8.1 공통 `components/evidence/`
`EvidenceCard`(kind별 레이아웃, 썸네일/아이콘, 제목·발췌 2줄·메타줄 `유사도 84% · 실시간 조회 14:02 | 캐시 09-21 | 키워드 검색`, "원문 보기" 새 탭), `EvidenceGrid`(≤3 세로, ≥4 2열, 접기), `PhotoLightbox`, `LawArticleCard`(항 원문 크게, 시행일, 법제처 링크), `MsdsCard`(GHS 픽토그램 SVG 9종 리포 동봉 `public/ghs/GHS01..09.svg`, 신호어, H코드, TWA/STEL), `CitationChip`(`[#n]` → 칩, 클릭 시 카드로 스크롤·하이라이트, 원장에 없는 번호는 평문).

### 8.2 화면별
- **UC3**: `useAgentStream`에 `ai.evidence`·`ai.recall` 핸들러. 턴 객체 `{role, text, evidence[]}`. `ChatThread`: RecallCard(연결성) → 말풍선 → 말풍선 아래 `EvidenceGrid`. `AgentMessage`가 `[#n]`을 `CitationChip`으로. 오른쪽 열 `ToolTracePanel` 아래 "이 대화의 근거 n건(사진 k·지침 k·조문 k·MSDS k)" 한 줄. `/transcript` 응답에 턴별 evidence 포함 → 복원. `WorkPlanDetailModal` 브리핑 아래 "참고 자료" 그리드(`work_plan_evidence`). 작업계획서 법정 서식 하단 "참고 자료" 목록(제목·URL·조회 시각, 사진 제외).
- **UC2**: `IncidentResult`의 RECALL 카드에 "동종 유사 사고(공단 사례)" `EvidenceGrid`. 조사표 초안의 `[#n]` 칩.
- **UC1**: `CandidateCard` 하단 접힌 "근거 3건" 행(썸네일 3개) → 펼치면 카드.
- **전역**: `GlobalSidebar` 하단 상태 줄 `외부 API 실시간 · 벡터 검색` / `캐시 모드 · 키워드 검색`(`/api/system/status` 1회).

### 8.3 타입 계약
`types/evidence.ts`: `Evidence{no, kind, refId, refKey, title, snippet, sourceUrl, mediaUrl, thumbnailUrl, origin, score, fetchedAt, meta}`. `sse.ts`: `EvidencePayload{items}`, `RecallPayload`, `SseEventType` += `ai.evidence`, `ai.recall`. `incident.ts`: `RecallView.similarCases`, `RegisterResponse.evidence`, `cascade`, `affectedWorkPlans`. `vision.ts`: `VisionCandidate.evidence`. `types/system.ts`: `SystemStatus`.

---

## 9. 연결성 개선 통합

연결성 변경 ①~④의 세부(엔드포인트·DTO·컴포넌트·규칙 7종·V8 시드 내용)는 `SAIFE_연결성_개선_프롬프트.md` Phase 1~4를 그대로 따른다. 이 스펙이 정하는 통합 규칙:

| 지점 | 결정 |
|---|---|
| SSE | `ai.recall`(도구 레벨) + `ai.evidence`(턴 종료). 핸들러 등록·타입 추가 방식 동일 |
| UC3 화면 순서 | RecallCard → 대화 → 말풍선별 EvidenceGrid. 회상("설비가 기억하는 것")과 근거("공단·법이 말하는 것")는 카드가 다르다 |
| UC2 | cascade 4스텝 유지. 1단계 RECALL 카드 안에 유사 사례 그리드. 5번째 스텝 없음 |
| 마이그레이션 | V8 = 연결성(`work_plan.warning_note`, 고소작업대 이야기). V9 = 근거 스키마. 벡터·조문 시드는 시드 로더 |
| 진입 컨텍스트 | `?equipmentId=`는 연결성 그대로. 근거 검색은 설비와 무관 |
| 데모 모드 | 전부 키 없이 동작. 근거는 키워드 폴백 + 미디어 프록시(공개 URL) |
| 돌아오기 링크 | 연결성 1-6의 3지점 그대로. 근거 카드와 독립 |

---

## 10. Phase·일정·절단선

| Phase | 내용 | 일정 | 병렬 |
|---|---|---|---|
| 0 | 기준선: 연결성 B1~B5 + 근거 B9(턴당 근거 카드 수, 현재 0) B10(응답 내 URL 유효율, 현재 측정 불가=0) B11(유사 사례 사진 비율, 현재 0). `baseline_connectivity.py` 하나에 통합 | 9/29 저녁 | — |
| 1 | 연결성 ① 설비 홈·상세 | 9/29~9/30 | 프론트 |
| 2 | 근거 코어: V9·엔티티, 크롤러 image_url·PDF 텍스트, 법제처 크롤, IndexBuilder·SeedExporter, 시드 생성(1회 임베딩 실행), `EvidenceSearchService`(하이브리드 RRF·Parent 확장·LLM 리랭크·Contextual Enrichment 이식), `MediaController`, `MsdsLiveClient`, `LiveOrCache`, `/api/system/status` | 9/29~10/1 | 백엔드 (Phase 1과 동시) |
| 3 | 연결성 ② 회상 카드 + 근거 UC3(도구 재배선, 원장, 후처리, 두 SSE 이벤트, EvidenceCard 패밀리, ChatThread, transcript 복원, 서식 참고 자료) | 10/1~10/2 | 백엔드/프론트 |
| 4 | 연결성 ② 사고 연쇄 + 근거 UC2 | 10/2~10/3 | 백엔드/프론트 |
| 5 | 연결성 ③ 오늘 할 일 + ④ 시드 V8 | 10/3 | 백엔드/프론트 |
| 6 | 근거 UC1 + 미디어 프리페치 + 전역 상태 줄 | 10/3~10/4 오전 | — |
| 7 | 클린 빌드(키 없이), 시연 동선 3회 완주, QA 리포트, README·CLAUDE.md·rules 갱신, 코드 프리즈, 1차 영상 | 10/4 | — |

각 Phase 끝: `./gradlew test` · `npm run lint && npm run build && npx vitest run` · 해당 스모크 실행 · 커밋(Conventional Commits, 한국어 본문).

**절단선(밀리면 이 순서로 뺀다)**: ① UC1 근거 카드 → ② 오늘 할 일을 OVERDUE_ACTION·REPORT_DUE 2종으로 축소 → ③ GUIDE PDF 본문 청크를 표지 청크만으로 축소 → ④ 법제처 런타임 라이브 확인 제거(시드만) → ⑤ Contextual Enrichment 생략(지침 청크를 원문만으로 임베딩; 리랭크·RRF는 유지). **절단 불가**: 회상 카드, 사고 연쇄, UC3 근거 카드, 시드 V8.

---

## 11. 지표 (연결성 M1~M8 + 근거 M9~M11)

| 지표 | 정의 | Before | 목표 |
|---|---|---|---|
| M9 근거 첨부율 | 데모·라이브 UC3 첫 턴 10회 중 `ai.evidence` items ≥ 1 | 0/10 | 10/10 |
| M10 링크 유효율 | 카드의 sourceUrl·mediaUrl·thumbnailUrl HTTP 200 비율(캐시 상태) | 측정 불가 | 100% |
| M11 사진 첨부율 | 유사 사례 카드 중 사진 있는 비율 | 0 | ≥ 60% (1040 비중) |
| M12 인용 정합률 | 응답 `[#n]` 중 원장에 존재하는 비율(후처리 전 측정, 후처리 후 100%) | — | 후처리 후 100% |

측정 스크립트: `evidence_smoke.py`(UC3 3턴 시나리오 → 이벤트·카드·URL 검증), `uc2_smoke.py`·`uc1_smoke.py` 확장, `baseline_connectivity.py`(Before/After).

---

## 12. 테스트

백엔드: `HybridRrfTest`(양쪽에 있는 문서가 1위, 한쪽 비면 다른 쪽 순서, topK 상한), `TsQueryBuilderTest`(OR 결합·조사 제거·토큰 상한), `ParentChunkExpanderTest`(같은 parent 병합, parent 실패 시 child 유지, parent 없는 child 유지), `LlmRerankerTest`(점수 정렬·4점 미만 제외·객체 배열·길이 불일치 폴백·예외 폴백·k 이하 시 LLM 미호출), `ChunkOverlapUtilTest`(문장 경계 200자), `ContextualEnricherTest`(실패 시 원문), `EvidenceSearchServiceTest`(키워드 폴백, 필터, 도메인 보정, 지침 dedupe, 프리픽스 제거 — `QueryEmbedder`·리랭커 가짜), `EvidenceLedgerTest`(번호 유일·재사용·턴 경계), `CitationSanitizerTest`, `LawCitationTableTest`(매핑 조문이 시드에 존재), `LawArticleParserTest`(실측 JSON 픽스처), `MsdsXmlParserTest`(실측 XML 픽스처), `LiveOrCacheTest`(폴백·회로), `MediaControllerTest`(허용 호스트·404 degrade), `CaseTextCleanerTest` 확장(image_url 추출), `PublicCacheSeedLoaderTest`(벡터 라운드트립). `EquipmentMatcherTest`처럼 DB가 필요한 테스트는 `@ActiveProfiles("test")`에 `application-test.yml`을 추가해 로컬 5432/5433을 명시한다(Testcontainers 전환은 범위 밖).

프론트: `EvidenceCard`(kind별, 사진 없음 degrade, origin 배지), `CitationChip`, `useAgentStream`(evidence 누적·복원·recall), `IncidentResult`(similarCases), `CandidateCard`(근거 접기), 연결성 테스트(프롬프트 문서 기준).

---

## 13. 리스크

| 리스크 | 대응 |
|---|---|
| 시드 임베딩 1회 실행(약 15,800건) + 지침 청크 Contextual Enrichment(약 5,000회 Flash 호출) | 임베딩은 배치 100건/호출, enrichment는 5개 병렬·체크포인트 재개. 9/30 저녁 전에 1회 완료. 시간이 모자라면 절단선 ⑤(enrichment 생략) 먼저, 그다음 ③ |
| 공단 사진 URL이 무대에서 막힘 | 프리페치로 디스크 캐시. 프록시는 캐시 우선 |
| 라이브 호출 지연이 도구 체인을 늦춤 | 8초 타임아웃 + 회로 차단. 리허설에서 회로 열림 상태도 1회 연습 |
| 모델이 `[#n]`을 안 쓰거나 잘못 씀 | 카드는 어차피 백엔드가 붙인다. 후처리로 환각 번호 제거. M12로 실측만 기록 |
| V8·V9 순서 충돌 | V8 연결성, V9 근거로 고정. 두 브랜치가 같은 번호를 쓰지 않도록 이 스펙이 번호를 예약 |
| 재해사례 첨부 없음 | 사진은 1040으로. 카드 메타에 "첨부 없음(공단 API 폐기)" 표기하지 않고 그냥 사진 없는 카드 |
| 일정 초과 | 절단선 순서대로. 10/3 저녁 시점에 Phase 5 미완이면 즉시 절단 ① 적용 |

---

## 14. 변경하지 않는 것
`ai/config/` 5종, 도구 6종 개수, SSE 봉투 규약, DTO 1:1 규칙, Flyway 전진 원칙, 데모 모드 원칙, 룰 엔진 등급 판정, 기존 4 라우트, 타임라인 페이지, 가상 사업장 제약.
