# 근거 계층 A1 — 스키마·이미지 URL 보존·라이브 클라이언트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 근거 계층의 바닥을 깐다 — V9 스키마와 엔티티, 사고 사진 URL 보존, 라이브 우선/캐시 폴백 헬퍼, MSDS·법제처 라이브 클라이언트와 조문 캐시.

**Architecture:** 새 패키지 `io.saife.evidence`에 `LiveOrCache`(회로 차단 포함), `MsdsLiveClient`, `LawArticleService`를 둔다. 크롤러와 시드 로더는 `image_url`·`source_url`을 보존하도록 최소 수정한다. 모든 외부 호출은 실패 시 예외를 밖으로 내지 않고 캐시로 내려간다.

**Tech Stack:** Spring Boot 3.4 · Java 21 · Flyway · JPA(엔티티) + JdbcTemplate · JDK `HttpClient`/`XMLInputFactory` · Caffeine(이미 의존성 있음) · JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-28-evidence-rag-and-connectivity-design.md` §2, §4, §6.1~6.3

## Global Constraints

- 루트 패키지 `io.saife`. 소스 주석 한국어. 커밋은 Conventional Commits + 한국어 본문.
- `ddl-auto: validate`: 엔티티와 Flyway 마이그레이션을 **같은 커밋**에 넣는다. 마이그레이션은 전진만(V8은 연결성이 예약, 이 계획은 **V9**).
- Lombok `@Getter @Builder @RequiredArgsConstructor @Slf4j`. 수동 getter/setter 금지. 순환 의존은 `ObjectProvider<T>`.
- 외부 호출 타임아웃 8초. 실패·키 없음은 예외가 아니라 캐시 폴백. 로그는 `log.warn` 한 줄.
- `.env` 커밋 금지. `application.yml`에 실제 키를 기본값으로 넣지 않는다(빈 문자열만).
- `ai/config/` 5개 클래스는 건드리지 않는다.
- 새 의존성은 `org.apache.pdfbox:pdfbox:3.0.5` 하나만(A2에서 추가). 이 계획에서는 추가 없음.
- 테스트: 단위 테스트는 Mockito, DB 필요 테스트는 `@SpringBootTest` + `@ActiveProfiles("test")` + `src/test/resources/application-test.yml`(로컬 5433, 이 계획 Task 1에서 생성).

## Review Focus

1. 사고사망 원문에 `<img>`가 두 개 이상이거나 `src="..."`(쌍따옴표)인 경우 — 첫 번째 URL만, 따옴표 종류와 무관하게 추출돼야 한다. → Task 2 `CaseTextCleanerTest.imageUrl_쌍따옴표와_복수_태그`.
2. 라이브 호출이 8초를 넘겨 타임아웃되면 캐시가 있을 때 캐시로, 없을 때 빈 결과로 끝나야 하고 호출자는 예외를 보지 않는다. → Task 3 `LiveOrCacheTest.타임아웃이면_캐시`.
3. 회로가 열린 60초 동안은 라이브를 아예 시도하지 않아야 한다(도구마다 8초씩 기다리는 사고 방지). → Task 3 `LiveOrCacheTest.연속_3회_실패_후_회로_열림`.
4. MSDS 응답 XML에 `<item>`이 없거나 `resultCode`가 `00`이 아닌 경우 캐시로 내려가야 한다. → Task 4 `MsdsXmlParserTest.빈_응답은_빈_리스트`.
5. 법제처 조문 JSON에서 `항`이 없는 조(조문내용만 있음)와 `조문여부=전문`(장 제목)을 구분해 후자는 저장하지 않아야 한다. → Task 5 `LawArticleParserTest.장_제목은_건너뛴다`.

---

### Task 1: V9 마이그레이션 + 엔티티 + 테스트 프로파일

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__evidence_schema.sql`
- Create: `backend/src/main/java/io/saife/evidence/domain/LawArticle.java`
- Create: `backend/src/main/java/io/saife/evidence/domain/EvidenceChunk.java`
- Create: `backend/src/main/java/io/saife/evidence/domain/ConversationEvidence.java`
- Create: `backend/src/main/java/io/saife/evidence/domain/WorkPlanEvidence.java`
- Create: `backend/src/main/java/io/saife/evidence/repository/LawArticleRepository.java`
- Create: `backend/src/main/java/io/saife/evidence/repository/ConversationEvidenceRepository.java`
- Create: `backend/src/main/java/io/saife/evidence/repository/WorkPlanEvidenceRepository.java`
- Modify: `backend/src/main/java/io/saife/publicapi/domain/PublicCase.java` (필드 2개 추가)
- Modify: `backend/src/main/java/io/saife/publicapi/domain/MsdsCache.java` (필드 1개 추가)
- Modify: `backend/src/main/resources/application.yml` (`initialize-schema: false`, `saife.media-dir`)
- Create: `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/io/saife/evidence/SchemaSmokeTest.java`

**Interfaces:**
- Produces: 엔티티 `LawArticle(lawId, lawName, articleNo, articleSub, paragraphNo, title, text, effectiveOn, sourceUrl)`, `EvidenceChunk(kind, refId, refKey, chunkLevel, parentId, searchable, sectionTitle, title, text, metadataJson)`, `ConversationEvidence(conversationId, turnNo, evidenceNo, payloadJson)`, `WorkPlanEvidence(workPlanId, evidenceNo, payloadJson)`. `PublicCase.imageUrl`, `PublicCase.sourceUrl`, `MsdsCache.pictograms`.

- [ ] **Step 1: 테스트 프로파일 파일 작성**

`backend/src/test/resources/application-test.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:${SAIFE_TEST_DB_PORT:5433}/saife
    username: saife
    password: saife
  ai:
    google:
      genai:
        api-key: ""
        embedding:
          api-key: ""
saife:
  demo-mode: true
```
(기존 `EquipmentMatcherTest`가 이미 `@ActiveProfiles("test")`를 쓰므로 이 파일이 생기면 로컬 5433의 시드 DB로 붙는다. 5432에 DB가 있는 환경은 `SAIFE_TEST_DB_PORT=5432`.)

- [ ] **Step 2: 실패하는 스키마 스모크 테스트 작성**

`backend/src/test/java/io/saife/evidence/SchemaSmokeTest.java`:
```java
package io.saife.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** V9가 만든 테이블·컬럼이 실제로 있는지. 엔티티 validate가 통과했다는 뜻이기도 하다 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaSmokeTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void evidence_chunk_테이블과_tsv_생성컬럼이_있다() {
        Integer n = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'evidence_chunk' and column_name in ('embedding','tsv','searchable','parent_id')
                """, Integer.class);
        assertThat(n).isEqualTo(4);
    }

    @Test
    void public_case에_image_url이_있다() {
        Integer n = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'public_case' and column_name in ('image_url','source_url')
                """, Integer.class);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void law_article_유니크가_있다() {
        Integer n = jdbc.queryForObject("""
                select count(*) from pg_indexes where tablename = 'law_article'
                """, Integer.class);
        assertThat(n).isGreaterThanOrEqualTo(2);
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.SchemaSmokeTest`
Expected: FAIL (컬럼 수 0 또는 컨텍스트 로드 실패)

- [ ] **Step 4: V9 마이그레이션 작성**

`backend/src/main/resources/db/migration/V9__evidence_schema.sql`:
```sql
-- 근거 계층(Evidence/RAG). 설계: docs/superpowers/specs/2026-09-28-evidence-rag-and-connectivity-design.md §4

ALTER TABLE public_case
  ADD COLUMN image_url  TEXT,
  ADD COLUMN source_url TEXT;
COMMENT ON COLUMN public_case.image_url  IS '1040 원문의 첫 <img src>. 없으면 NULL';
COMMENT ON COLUMN public_case.source_url IS '1060: 포털 목록 페이지. 1040: NULL(딥링크 없음)';

ALTER TABLE msds_cache ADD COLUMN pictograms VARCHAR(200);
COMMENT ON COLUMN msds_cache.pictograms IS 'GHS 그림문자 코드. 예: GHS02,GHS07,GHS08';

CREATE TABLE law_article (
  id            BIGSERIAL PRIMARY KEY,
  law_id        VARCHAR(20)  NOT NULL,
  law_name      VARCHAR(100) NOT NULL,
  article_no    INTEGER      NOT NULL,
  article_sub   INTEGER      NOT NULL DEFAULT 0,
  paragraph_no  INTEGER      NOT NULL DEFAULT 0,
  title         VARCHAR(300),
  text          TEXT         NOT NULL,
  effective_on  DATE,
  source_url    TEXT,
  fetched_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  UNIQUE (law_id, article_no, article_sub, paragraph_no)
);
CREATE INDEX ix_law_article_lookup ON law_article (law_name, article_no, article_sub);

CREATE TABLE evidence_chunk (
  id            BIGSERIAL PRIMARY KEY,
  kind          VARCHAR(20)  NOT NULL,
  ref_id        BIGINT       NOT NULL,
  ref_key       VARCHAR(120) NOT NULL,
  chunk_level   VARCHAR(10)  NOT NULL DEFAULT 'child',
  parent_id     BIGINT       REFERENCES evidence_chunk(id),
  searchable    BOOLEAN      NOT NULL DEFAULT true,
  section_title VARCHAR(300),
  title         VARCHAR(500) NOT NULL,
  text          TEXT         NOT NULL,
  metadata      JSONB        NOT NULL DEFAULT '{}'::jsonb,
  embedding     vector(768),
  tsv           tsvector GENERATED ALWAYS AS (
                  to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(section_title,'') || ' ' || text)
                ) STORED,
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
  evidence_no     INTEGER     NOT NULL,
  payload         JSONB       NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (conversation_id, evidence_no)
);
CREATE INDEX ix_conversation_evidence_conv ON conversation_evidence (conversation_id, turn_no);

CREATE TABLE work_plan_evidence (
  work_plan_id BIGINT  NOT NULL REFERENCES work_plan(id),
  evidence_no  INTEGER NOT NULL,
  payload      JSONB   NOT NULL,
  PRIMARY KEY (work_plan_id, evidence_no)
);
```

- [ ] **Step 5: 엔티티 작성**

`PublicCase.java`에 추가(기존 필드 아래, `fetchedAt` 위):
```java
    /** 1040 원문의 첫 사고 사진 URL. 크롤러가 태그를 지우기 전에 뽑아 둔다 */
    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    /** 원문 페이지. 1060은 포털 목록, 1040은 딥링크가 없어 NULL */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;
```

`MsdsCache.java`에 추가:
```java
    /** GHS 그림문자 코드. 'GHS02,GHS07,GHS08' — 02 항목 응답에서 추출 */
    @Column(length = 200)
    private String pictograms;
```

`evidence/domain/LawArticle.java`:
```java
package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.*;

/** 법제처 조문 캐시. 항(項) 단위 한 행. 항이 없는 조는 paragraphNo=0 한 행 */
@Entity
@Table(name = "law_article")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LawArticle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "law_id", nullable = false, length = 20) private String lawId;
    @Column(name = "law_name", nullable = false, length = 100) private String lawName;
    @Column(name = "article_no", nullable = false) private int articleNo;
    @Column(name = "article_sub", nullable = false) private int articleSub;
    @Column(name = "paragraph_no", nullable = false) private int paragraphNo;
    @Column(length = 300) private String title;
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(name = "effective_on") private LocalDate effectiveOn;
    @Column(name = "source_url", columnDefinition = "text") private String sourceUrl;
    @Column(name = "fetched_at", nullable = false) private OffsetDateTime fetchedAt;

    @PrePersist void onCreate() { if (fetchedAt == null) fetchedAt = OffsetDateTime.now(); }

    /** 화면·인용용 제목. 예: 산업안전보건법 제36조(위험성평가의 실시) ① */
    public String citation() {
        String sub = articleSub > 0 ? "의" + articleSub : "";
        String para = paragraphNo > 0 ? " " + circled(paragraphNo) : "";
        String t = title == null ? "" : "(" + title + ")";
        return "%s 제%d조%s%s%s".formatted(lawName, articleNo, sub, t, para);
    }

    private static String circled(int n) {
        return n >= 1 && n <= 20 ? String.valueOf((char) ('①' + n - 1)) : "(" + n + ")";
    }
}
```

`evidence/domain/EvidenceChunk.java` (embedding·tsv는 JPA 매핑 없음. validate는 컬럼 존재만 본다):
```java
package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;

/** 검색 청크. 벡터(embedding)와 tsv는 JdbcTemplate로만 다룬다 — 엔티티는 텍스트·메타만 */
@Entity
@Table(name = "evidence_chunk")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EvidenceChunk {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20) private String kind;
    @Column(name = "ref_id", nullable = false) private Long refId;
    @Column(name = "ref_key", nullable = false, length = 120) private String refKey;
    @Column(name = "chunk_level", nullable = false, length = 10) private String chunkLevel;
    @Column(name = "parent_id") private Long parentId;
    @Column(nullable = false) private boolean searchable;
    @Column(name = "section_title", length = 300) private String sectionTitle;
    @Column(nullable = false, length = 500) private String title;
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(nullable = false, columnDefinition = "jsonb") private String metadata;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist void onCreate() { if (updatedAt == null) updatedAt = OffsetDateTime.now(); }
}
```

`evidence/domain/ConversationEvidence.java`:
```java
package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;

/** 대화에서 제시한 근거. 번호는 대화 전체에서 유일하다 */
@Entity
@Table(name = "conversation_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ConversationEvidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "conversation_id", nullable = false, length = 40) private String conversationId;
    @Column(name = "turn_no", nullable = false) private int turnNo;
    @Column(name = "evidence_no", nullable = false) private int evidenceNo;
    @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = OffsetDateTime.now(); }
}
```

`evidence/domain/WorkPlanEvidence.java`:
```java
package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.*;

/** 작업계획서에 붙은 근거(브리핑 참고 자료). createWorkPlan 시점의 원장 스냅샷 */
@Entity
@Table(name = "work_plan_evidence")
@IdClass(WorkPlanEvidence.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WorkPlanEvidence {
    @Id @Column(name = "work_plan_id") private Long workPlanId;
    @Id @Column(name = "evidence_no") private int evidenceNo;
    @Column(nullable = false, columnDefinition = "jsonb") private String payload;

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long workPlanId;
        private int evidenceNo;
    }
}
```

리포지토리 3개(모두 `evidence/repository/`):
```java
package io.saife.evidence.repository;
import io.saife.evidence.domain.LawArticle;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LawArticleRepository extends JpaRepository<LawArticle, Long> {
    List<LawArticle> findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(String lawName, int articleNo, int articleSub);
    Optional<LawArticle> findByLawIdAndArticleNoAndArticleSubAndParagraphNo(String lawId, int articleNo, int articleSub, int paragraphNo);
    long countByLawId(String lawId);
}
```
```java
package io.saife.evidence.repository;
import io.saife.evidence.domain.ConversationEvidence;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface ConversationEvidenceRepository extends JpaRepository<ConversationEvidence, Long> {
    List<ConversationEvidence> findByConversationIdOrderByEvidenceNo(String conversationId);
    @Query("select coalesce(max(e.evidenceNo), 0) from ConversationEvidence e where e.conversationId = :cid")
    int maxEvidenceNo(@Param("cid") String conversationId);
    @Query("select coalesce(max(e.turnNo), 0) from ConversationEvidence e where e.conversationId = :cid")
    int maxTurnNo(@Param("cid") String conversationId);
    Optional<ConversationEvidence> findByConversationIdAndEvidenceNo(String conversationId, int evidenceNo);
}
```
```java
package io.saife.evidence.repository;
import io.saife.evidence.domain.WorkPlanEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkPlanEvidenceRepository extends JpaRepository<WorkPlanEvidence, WorkPlanEvidence.Key> {
    List<WorkPlanEvidence> findByWorkPlanIdOrderByEvidenceNo(Long workPlanId);
}
```

- [ ] **Step 6: application.yml 수정**

`spring.ai.vectorstore.pgvector.initialize-schema: true` → `false`. `saife:` 아래에 추가:
```yaml
  media-dir: ${SAIFE_MEDIA_DIR:./data/media}   # 사진·PDF 디스크 캐시. /data/는 gitignore
  external:
    timeout-ms: 8000
    circuit-failures: 3
    circuit-open-ms: 60000
```

- [ ] **Step 7: 테스트 실행 — 통과 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.SchemaSmokeTest`
Expected: PASS 3건 (Flyway가 V9를 적용하고 validate 통과)

- [ ] **Step 8: 커밋**

```bash
git add backend/src/main/resources/db/migration/V9__evidence_schema.sql backend/src/main/java/io/saife/evidence backend/src/main/java/io/saife/publicapi/domain backend/src/main/resources/application.yml backend/src/test
git commit -m "feat(evidence): V9 근거 스키마와 엔티티 — evidence_chunk·law_article·근거 원장 테이블

- public_case.image_url/source_url, msds_cache.pictograms
- evidence_chunk: parent-child, searchable, tsv 생성 컬럼, HNSW 부분 인덱스(768)
- Spring AI vector_store 자동 생성 끔, 테스트 프로파일 추가"
```

---

### Task 2: 사고 사진 URL 보존 — 클리너·크롤러·시드 로더

**Files:**
- Modify: `backend/src/main/java/io/saife/publicapi/service/CaseTextCleaner.java`
- Modify: `backend/src/main/java/io/saife/publicapi/service/PublicApiCrawler.java` (`saveCase`)
- Modify: `backend/src/main/java/io/saife/publicapi/service/PublicCacheSeedLoader.java` (`loadCases`)
- Test: `backend/src/test/java/io/saife/publicapi/service/CaseTextCleanerTest.java` (기존 파일에 추가)

**Interfaces:**
- Produces: `CaseTextCleaner.imageUrlOf(String rawHtml) → String|null`, 상수 `CaseTextCleaner.DISASTER_LIST_URL`.

- [ ] **Step 1: 실패하는 테스트 추가**

`CaseTextCleanerTest.java`에 추가:
```java
    @Test
    void imageUrl_첫_img_src를_뽑는다() {
        String raw = "<p><img src='https://portal.kosha.or.kr/api/compn24/auth/stdtboard/getImage.do?bbsId=B1&pstNo=P1&bbsAtcflNo=E1' style='width: 931px;'></p><p>본문</p>";
        assertThat(CaseTextCleaner.imageUrlOf(raw))
                .isEqualTo("https://portal.kosha.or.kr/api/compn24/auth/stdtboard/getImage.do?bbsId=B1&pstNo=P1&bbsAtcflNo=E1");
    }

    @Test
    void imageUrl_쌍따옴표와_복수_태그() {
        String raw = "<img src=\"https://portal.kosha.or.kr/a.png\"><img src='https://portal.kosha.or.kr/b.png'>";
        assertThat(CaseTextCleaner.imageUrlOf(raw)).isEqualTo("https://portal.kosha.or.kr/a.png");
    }

    @Test
    void imageUrl_base64나_외부호스트는_버린다() {
        assertThat(CaseTextCleaner.imageUrlOf("<img src='data:image/png;base64,AAAA'>")).isNull();
        assertThat(CaseTextCleaner.imageUrlOf("<img src='https://evil.example/x.png'>")).isNull();
        assertThat(CaseTextCleaner.imageUrlOf(null)).isNull();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.publicapi.service.CaseTextCleanerTest`
Expected: FAIL — `imageUrlOf` 없음(컴파일 에러)

- [ ] **Step 3: 구현**

`CaseTextCleaner.java`에 추가:
```java
    /** 사진은 공단 포털 호스트에서만 받는다. 프록시가 SSRF에 쓰이지 않게 여기서부터 막는다 */
    private static final Pattern IMG_SRC =
            Pattern.compile("<img[^>]*\\ssrc\\s*=\\s*['\"]?(https://portal\\.kosha\\.or\\.kr/[^'\"\\s>]+)", Pattern.CASE_INSENSITIVE);

    /** 국내재해사례(1060)는 게시글 딥링크가 없다. 포털 목록 페이지가 원문 링크다 */
    public static final String DISASTER_LIST_URL =
            "https://portal.kosha.or.kr/archive/disaster-case/accident-case";

    /**
     * 원문 HTML의 첫 사고 사진 URL.
     *
     * <p>1040 원문은 전 건에 {@code <img src='https://portal.kosha.or.kr/api/compn24/auth/stdtboard/getImage.do?...'>}가
     * 있다(2026-09-28 실측 300/300). {@link #clean}이 태그를 지우기 <b>전에</b> 불러야 한다.
     */
    public static String imageUrlOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = IMG_SRC.matcher(raw);
        return m.find() ? m.group(1) : null;
    }
```

`PublicApiCrawler.saveCase`에서 `String contents = ...` 앞에 `String rawContents = text(item, "contents");`를 두고:
```java
        String rawContents = text(item, "contents");
        String keyword = text(item, "keyword");
        String contents = CaseTextCleaner.clean(rawContents);
        String imageUrl = "FATALITY".equals(source) ? CaseTextCleaner.imageUrlOf(rawContents) : null;
        String sourceUrl = "DISASTER".equals(source) ? CaseTextCleaner.DISASTER_LIST_URL : null;
```
그리고 빌더에 `.imageUrl(imageUrl).sourceUrl(sourceUrl)` 추가.

`PublicCacheSeedLoader.loadCases`의 빌더에 추가:
```java
                        .imageUrl(text(n, "imageUrl"))
                        .sourceUrl(text(n, "sourceUrl"))
```

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test --tests io.saife.publicapi.service.CaseTextCleanerTest`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/io/saife/publicapi backend/src/test/java/io/saife/publicapi
git commit -m "feat(publicapi): 사고사망 원문의 사진 URL을 보존한다

클리너가 태그를 지우기 전에 첫 <img src>를 image_url로 뽑는다. 공단 포털 호스트만 허용.
재해사례는 포털 목록 페이지를 source_url로 둔다(첨부 API 1070 폐기)."
```

---

### Task 3: `LiveOrCache` — 라이브 우선·캐시 폴백·회로 차단

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/live/Origin.java`
- Create: `backend/src/main/java/io/saife/evidence/live/Fetched.java`
- Create: `backend/src/main/java/io/saife/evidence/live/LiveOrCache.java`
- Test: `backend/src/test/java/io/saife/evidence/live/LiveOrCacheTest.java`

**Interfaces:**
- Produces:
  - `enum Origin { LIVE, CACHE, KEYWORD_FALLBACK }`
  - `record Fetched<T>(T value, Origin origin, OffsetDateTime fetchedAt, String note)` + `static <T> Fetched<T> empty(String note)` + `boolean isEmpty()`
  - `LiveOrCache.fetch(String host, boolean keyPresent, Callable<T> live, Supplier<Optional<T>> cache, Consumer<T> store) → Fetched<T>`
  - `LiveOrCache.isOpen(String host) → boolean`, `openHosts() → Set<String>`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LiveOrCacheTest {

    private LiveOrCache newHelper() {
        return new LiveOrCache(Duration.ofMillis(200), 3, Duration.ofSeconds(60));
    }

    @Test
    void 라이브_성공이면_LIVE이고_캐시에_저장한다() {
        LiveOrCache h = newHelper();
        AtomicInteger stored = new AtomicInteger();
        Fetched<String> f = h.fetch("h1", true, () -> "live", Optional::empty, v -> stored.incrementAndGet());
        assertThat(f.value()).isEqualTo("live");
        assertThat(f.origin()).isEqualTo(Origin.LIVE);
        assertThat(stored.get()).isEqualTo(1);
    }

    @Test
    void 키가_없으면_라이브를_부르지_않고_캐시() {
        LiveOrCache h = newHelper();
        AtomicInteger calls = new AtomicInteger();
        Fetched<String> f = h.fetch("h1", false, () -> { calls.incrementAndGet(); return "live"; },
                () -> Optional.of("cached"), v -> {});
        assertThat(calls.get()).isZero();
        assertThat(f.origin()).isEqualTo(Origin.CACHE);
        assertThat(f.value()).isEqualTo("cached");
    }

    @Test
    void 타임아웃이면_캐시() {
        LiveOrCache h = newHelper();
        Fetched<String> f = h.fetch("h1", true, () -> { Thread.sleep(1000); return "late"; },
                () -> Optional.of("cached"), v -> {});
        assertThat(f.origin()).isEqualTo(Origin.CACHE);
        assertThat(f.value()).isEqualTo("cached");
    }

    @Test
    void 라이브도_캐시도_없으면_empty() {
        LiveOrCache h = newHelper();
        Fetched<String> f = h.fetch("h1", true, () -> { throw new RuntimeException("boom"); },
                Optional::empty, v -> {});
        assertThat(f.isEmpty()).isTrue();
        assertThat(f.note()).contains("boom");
    }

    @Test
    void 연속_3회_실패_후_회로_열림() {
        LiveOrCache h = newHelper();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            h.fetch("h1", true, () -> { calls.incrementAndGet(); throw new RuntimeException("x"); }, Optional::empty, v -> {});
        }
        assertThat(h.isOpen("h1")).isTrue();
        h.fetch("h1", true, () -> { calls.incrementAndGet(); return "v"; }, Optional::empty, v -> {});
        assertThat(calls.get()).isEqualTo(3);   // 4번째는 시도조차 안 한다
        assertThat(h.isOpen("h2")).isFalse();   // 호스트별
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.live.LiveOrCacheTest`
Expected: FAIL (클래스 없음)

- [ ] **Step 3: 구현**

`Origin.java`:
```java
package io.saife.evidence.live;
/** 카드에 찍히는 출처 상태. LIVE=실시간 조회, CACHE=로컬 캐시, KEYWORD_FALLBACK=벡터 없이 키워드 검색 */
public enum Origin { LIVE, CACHE, KEYWORD_FALLBACK }
```

`Fetched.java`:
```java
package io.saife.evidence.live;

import java.time.OffsetDateTime;

/** 외부 조회 결과 + 출처. value가 null이면 빈 결과다 */
public record Fetched<T>(T value, Origin origin, OffsetDateTime fetchedAt, String note) {
    public static <T> Fetched<T> empty(String note) {
        return new Fetched<>(null, Origin.CACHE, OffsetDateTime.now(), note);
    }
    public boolean isEmpty() { return value == null; }
}
```

`LiveOrCache.java`:
```java
package io.saife.evidence.live;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 라이브 우선 + 캐시 폴백 + 호스트별 회로 차단.
 *
 * <p>순서: 키 있음 && 회로 닫힘 → 라이브(타임아웃) → 성공 시 store, LIVE.
 * 실패·타임아웃·키 없음 → 캐시, CACHE. 캐시도 없으면 empty(note).
 *
 * <p>회로: 같은 호스트에서 연속 N회 실패하면 open 동안 라이브를 건너뛴다.
 * 무대에서 네트워크가 죽었을 때 도구마다 8초씩 기다리는 사고를 막는다.
 * 호출자는 예외를 보지 않는다.
 */
@Slf4j
@Component
public class LiveOrCache {
    private record Circuit(int failures, long openedUntilMs) {}

    private final Duration timeout;
    private final int failureThreshold;
    private final Duration openFor;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "live-or-cache");
        t.setDaemon(true);
        return t;
    });

    public LiveOrCache(@Value("${saife.external.timeout-ms:8000}") long timeoutMs,
                       @Value("${saife.external.circuit-failures:3}") int failureThreshold,
                       @Value("${saife.external.circuit-open-ms:60000}") long openMs) {
        this(Duration.ofMillis(timeoutMs), failureThreshold, Duration.ofMillis(openMs));
    }

    LiveOrCache(Duration timeout, int failureThreshold, Duration openFor) {
        this.timeout = timeout;
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
    }

    public <T> Fetched<T> fetch(String host, boolean keyPresent, Callable<T> live,
                                Supplier<Optional<T>> cache, Consumer<T> store) {
        String note;
        if (!keyPresent) {
            note = "키 없음";
        } else if (isOpen(host)) {
            note = "회로 열림(" + host + ")";
        } else {
            Future<T> future = pool.submit(live);
            try {
                T value = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                onSuccess(host);
                if (value != null) {
                    try { store.accept(value); } catch (Exception e) { log.warn("[LIVE] 캐시 저장 실패 host={}: {}", host, e.getMessage()); }
                    return new Fetched<>(value, Origin.LIVE, OffsetDateTime.now(), null);
                }
                note = "라이브 응답 비어 있음";
            } catch (TimeoutException e) {
                future.cancel(true);
                onFailure(host);
                note = "타임아웃 " + timeout.toMillis() + "ms";
            } catch (Exception e) {
                onFailure(host);
                Throwable c = e.getCause() != null ? e.getCause() : e;
                note = "라이브 실패: " + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
            }
            log.warn("[LIVE] host={} → 캐시 폴백 ({})", host, note);
        }
        Optional<T> cached = Optional.empty();
        try { cached = cache.get(); } catch (Exception e) { log.warn("[LIVE] 캐시 조회 실패 host={}: {}", host, e.getMessage()); }
        final String n = note;
        return cached.map(v -> new Fetched<>(v, Origin.CACHE, OffsetDateTime.now(), n))
                .orElseGet(() -> Fetched.empty(n));
    }

    public boolean isOpen(String host) {
        Circuit c = circuits.get(host);
        return c != null && c.openedUntilMs() > System.currentTimeMillis();
    }

    public Set<String> openHosts() {
        return circuits.entrySet().stream().filter(e -> isOpen(e.getKey())).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void onSuccess(String host) { circuits.remove(host); }

    private void onFailure(String host) {
        circuits.compute(host, (k, c) -> {
            int f = (c == null ? 0 : c.failures()) + 1;
            long until = f >= failureThreshold ? System.currentTimeMillis() + openFor.toMillis() : 0;
            if (until > 0) log.warn("[LIVE] 회로 열림 host={} {}초", host, openFor.toSeconds());
            return new Circuit(f, until);
        });
    }
}
```
(Caffeine 대신 `ConcurrentHashMap`으로 충분하다. 호스트가 4개뿐이다.)

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.live.LiveOrCacheTest`
Expected: PASS 5건

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/io/saife/evidence/live backend/src/test/java/io/saife/evidence/live
git commit -m "feat(evidence): LiveOrCache — 라이브 우선, 캐시 폴백, 호스트별 회로 차단"
```

---

### Task 4: MSDS 라이브 클라이언트 (`getChemList001` → `getChemDetail0N1`)

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/live/MsdsXmlParser.java`
- Create: `backend/src/main/java/io/saife/evidence/live/MsdsLiveClient.java`
- Modify: `backend/src/main/java/io/saife/publicapi/repository/MsdsCacheRepository.java` (메서드 1개)
- Test: `backend/src/test/java/io/saife/evidence/live/MsdsXmlParserTest.java`
- Test fixtures: `backend/src/test/resources/fixtures/msds-list.xml`, `msds-detail-02.xml`

**Interfaces:**
- Produces:
  - `MsdsXmlParser.parseList(String xml) → List<ChemHit(chemId, chemNameKor, casNo, unNo)>`
  - `MsdsXmlParser.parseDetail(String xml) → List<DetailItem(msdsItemCode, msdsItemNameKor, itemDetail, lev, upMsdsItemCode)>`
  - `MsdsXmlParser.pictogramsOf(List<DetailItem>) → String` (예 `GHS02,GHS07`)
  - `MsdsLiveClient.resolve(String productName) → Fetched<MsdsBundle(chemId, chemNameKor, casNo, unNo, pictograms, sections: Map<String,List<String>>)>` — 섹션 키 `02/05/07/08`, 값은 `|`로 나눈 줄 목록

- [ ] **Step 1: 픽스처 작성**

`fixtures/msds-list.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?><response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header><body><items><item><casNo>108-88-3</casNo><chemId>001032</chemId><chemNameKor>톨루엔</chemNameKor><enNo>203-625-9</enNo><keNo>KE-33936</keNo><unNo>1294</unNo></item></items><numOfRows>3</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount></body></response>
```
`fixtures/msds-detail-02.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?><response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header><body><items><item><itemDetail>인화성 액체 : 구분2|피부 부식성/피부 자극성 : 구분2</itemDetail><lev>1</lev><msdsItemCode>B02</msdsItemCode><msdsItemNameKor>유해성·위험성 분류</msdsItemNameKor><ordrIdx>1022</ordrIdx><upMsdsItemCode>B</upMsdsItemCode></item><item><itemDetail>GHS02.gif|GHS07.gif|GHS08.gif</itemDetail><lev>2</lev><msdsItemCode>B04</msdsItemCode><msdsItemNameKor>그림문자</msdsItemNameKor><upMsdsItemCode>B03</upMsdsItemCode></item><item><itemDetail>위험</itemDetail><lev>2</lev><msdsItemCode>B05</msdsItemCode><msdsItemNameKor>신호어</msdsItemNameKor><upMsdsItemCode>B03</upMsdsItemCode></item></items></body></response>
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MsdsXmlParserTest {

    private String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name), StandardCharsets.UTF_8);
    }

    @Test
    void 목록에서_chemId를_읽는다() throws Exception {
        List<MsdsXmlParser.ChemHit> hits = MsdsXmlParser.parseList(fixture("msds-list.xml"));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chemId()).isEqualTo("001032");
        assertThat(hits.get(0).chemNameKor()).isEqualTo("톨루엔");
        assertThat(hits.get(0).casNo()).isEqualTo("108-88-3");
    }

    @Test
    void 상세에서_항목과_그림문자를_읽는다() throws Exception {
        List<MsdsXmlParser.DetailItem> items = MsdsXmlParser.parseDetail(fixture("msds-detail-02.xml"));
        assertThat(items).hasSize(3);
        assertThat(items.get(0).itemDetail()).startsWith("인화성 액체");
        assertThat(MsdsXmlParser.pictogramsOf(items)).isEqualTo("GHS02,GHS07,GHS08");
    }

    @Test
    void 빈_응답은_빈_리스트() {
        String noItems = "<response><header><resultCode>00</resultCode></header><body><items/></body></response>";
        assertThat(MsdsXmlParser.parseList(noItems)).isEmpty();
        String error = "<response><header><resultCode>30</resultCode><resultMsg>SERVICE KEY IS NOT REGISTERED</resultMsg></header></response>";
        assertThat(MsdsXmlParser.parseDetail(error)).isEmpty();
        assertThat(MsdsXmlParser.parseList("not xml at all")).isEmpty();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.live.MsdsXmlParserTest`
Expected: FAIL (클래스 없음)

- [ ] **Step 4: 파서 구현**

`MsdsXmlParser.java`:
```java
package io.saife.evidence.live;

import java.io.StringReader;
import java.util.*;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * 공단 MSDS API XML 파서. 의존성 없이 JDK StAX만 쓴다.
 *
 * <p>응답 형태: {@code <response><header><resultCode>00</resultCode>...</header><body><items><item>...</item></items></body></response>}.
 * resultCode가 00이 아니거나 파싱이 깨지면 빈 리스트 — 호출자는 캐시로 내려간다.
 */
public final class MsdsXmlParser {
    private MsdsXmlParser() {}

    public record ChemHit(String chemId, String chemNameKor, String casNo, String unNo) {}
    public record DetailItem(String msdsItemCode, String msdsItemNameKor, String itemDetail, String lev, String upMsdsItemCode) {}

    public static List<ChemHit> parseList(String xml) {
        List<ChemHit> out = new ArrayList<>();
        for (Map<String, String> item : items(xml)) {
            String id = item.get("chemId");
            if (id != null && !id.isBlank()) {
                out.add(new ChemHit(id, item.get("chemNameKor"), item.get("casNo"), item.get("unNo")));
            }
        }
        return out;
    }

    public static List<DetailItem> parseDetail(String xml) {
        List<DetailItem> out = new ArrayList<>();
        for (Map<String, String> item : items(xml)) {
            out.add(new DetailItem(item.get("msdsItemCode"), item.get("msdsItemNameKor"),
                    item.get("itemDetail"), item.get("lev"), item.get("upMsdsItemCode")));
        }
        return out;
    }

    /** 02 항목의 '그림문자' 행에서 GHS 코드만 뽑는다. 'GHS02.gif|GHS07.gif' → 'GHS02,GHS07' */
    public static String pictogramsOf(List<DetailItem> items) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (DetailItem it : items) {
            if (it.itemDetail() == null) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("GHS0[1-9]").matcher(it.itemDetail());
            while (m.find()) codes.add(m.group());
        }
        return codes.isEmpty() ? null : String.join(",", codes);
    }

    /** <item> 하나를 태그명→텍스트 맵으로. resultCode≠00이면 빈 목록 */
    private static List<Map<String, String>> items(String xml) {
        List<Map<String, String>> out = new ArrayList<>();
        if (xml == null || xml.isBlank()) return out;
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = f.createXMLStreamReader(new StringReader(xml));
            Map<String, String> current = null;
            String field = null;
            StringBuilder text = new StringBuilder();
            String resultCode = null;
            boolean inResultCode = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if ("item".equals(name)) { current = new HashMap<>(); }
                    else if (current != null) { field = name; text.setLength(0); }
                    else if ("resultCode".equals(name)) { inResultCode = true; text.setLength(0); }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (field != null || inResultCode) text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if ("item".equals(name) && current != null) { out.add(current); current = null; }
                    else if (current != null && name.equals(field)) { current.put(field, text.toString().trim()); field = null; }
                    else if (inResultCode && "resultCode".equals(name)) { resultCode = text.toString().trim(); inResultCode = false; }
                }
            }
            if (resultCode != null && !"00".equals(resultCode)) return List.of();
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.live.MsdsXmlParserTest`
Expected: PASS 3건

- [ ] **Step 6: 라이브 클라이언트 구현**

`MsdsCacheRepository.java`에 추가:
```java
    List<MsdsCache> findByChemId(String chemId);
```

`MsdsLiveClient.java`:
```java
package io.saife.evidence.live;

import io.saife.publicapi.domain.MsdsCache;
import io.saife.publicapi.repository.MsdsCacheRepository;
import io.saife.publicapi.service.MsdsResolver;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MSDS 라이브 조회 — 목록(getChemList001)으로 chemId 확정 후 상세 4개 항목 병렬 호출.
 *
 * <p>실패·키 없음이면 {@code msds_cache}로 내려간다. 제품명 → 성분 추정은 기존 {@link MsdsResolver}를 그대로 쓴다.
 */
@Slf4j
@Service
public class MsdsLiveClient {
    public static final List<String> BRIEFING_SECTIONS = List.of("02", "05", "07", "08");
    private static final String HOST = "apis.data.go.kr/msds";

    public record MsdsBundle(String chemId, String chemNameKor, String casNo, String unNo,
                             String pictograms, Map<String, List<String>> sections) {}

    private final MsdsCacheRepository repository;
    private final MsdsResolver resolver;
    private final LiveOrCache liveOrCache;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String listPath;
    private final String detailPath;
    private final String key;

    public MsdsLiveClient(MsdsCacheRepository repository, MsdsResolver resolver, LiveOrCache liveOrCache,
                          @Value("${public-api.kosha.base-url}") String baseUrl,
                          @Value("${public-api.kosha.msds.list-path}") String listPath,
                          @Value("${public-api.kosha.msds.detail-path}") String detailPath,
                          @Value("${public-api.kosha.msds.key:}") String key) {
        this.repository = repository; this.resolver = resolver; this.liveOrCache = liveOrCache;
        this.baseUrl = baseUrl; this.listPath = listPath; this.detailPath = detailPath; this.key = key;
    }

    public Fetched<MsdsBundle> resolve(String productName) {
        if (productName == null || productName.isBlank()) return Fetched.empty("제품명 없음");
        List<String> names = new ArrayList<>();
        names.add(productName.trim());
        names.addAll(resolver.guessIngredients(productName.trim()));
        return liveOrCache.fetch(HOST, key != null && !key.isBlank(),
                () -> fetchLive(names),
                () -> fromCache(names),
                this::store);
    }

    // ---- 라이브 ----
    private MsdsBundle fetchLive(List<String> names) throws Exception {
        for (String name : names) {
            List<MsdsXmlParser.ChemHit> hits = MsdsXmlParser.parseList(get(baseUrl + listPath
                    + "?serviceKey=" + enc(key) + "&searchWrd=" + enc(name) + "&searchCnd=0&numOfRows=5&pageNo=1"));
            if (hits.isEmpty()) continue;
            MsdsXmlParser.ChemHit hit = hits.get(0);
            Map<String, List<MsdsXmlParser.DetailItem>> details = new LinkedHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String section : BRIEFING_SECTIONS) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        String url = baseUrl + detailPath.replace("{section}", section)
                                + "?serviceKey=" + enc(key) + "&chemId=" + enc(hit.chemId());
                        details.put(section, MsdsXmlParser.parseDetail(get(url)));
                    } catch (Exception e) {
                        details.put(section, List.of());
                    }
                }));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            if (details.values().stream().allMatch(List::isEmpty)) continue;
            Map<String, List<String>> sections = new LinkedHashMap<>();
            details.forEach((s, items) -> sections.put(s, items.stream()
                    .filter(i -> i.itemDetail() != null && !i.itemDetail().isBlank())
                    .flatMap(i -> Arrays.stream(i.itemDetail().split("\\|"))).map(String::trim)
                    .filter(x -> !x.isBlank()).toList()));
            String pictograms = MsdsXmlParser.pictogramsOf(details.getOrDefault("02", List.of()));
            return new MsdsBundle(hit.chemId(), hit.chemNameKor(), hit.casNo(), hit.unNo(), pictograms, sections);
        }
        return null;
    }

    private String get(String url) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(7)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ---- 캐시 ----
    private Optional<MsdsBundle> fromCache(List<String> names) {
        for (String name : names) {
            String chemId = resolver.resolveChemId(name);
            if (chemId == null) continue;
            List<MsdsCache> rows = repository.findByChemId(chemId);
            if (rows.isEmpty()) continue;
            Map<String, List<String>> sections = new LinkedHashMap<>();
            for (MsdsCache r : rows) {
                if (r.getItemDetail() == null) continue;
                sections.computeIfAbsent(r.getSectionCode(), k -> new ArrayList<>())
                        .addAll(Arrays.stream(r.getItemDetail().split("\\|")).map(String::trim).filter(x -> !x.isBlank()).toList());
            }
            MsdsCache first = rows.get(0);
            String pictograms = rows.stream().map(MsdsCache::getPictograms).filter(Objects::nonNull).findFirst().orElse(null);
            return Optional.of(new MsdsBundle(chemId, first.getChemNameKor(), first.getCasNo(), first.getUnNo(), pictograms, sections));
        }
        return Optional.empty();
    }

    @Transactional
    protected void store(MsdsBundle b) {
        // 같은 chemId·섹션은 지우고 다시 넣는다 (UNIQUE(chem_id, section_code, item_code))
        List<MsdsCache> old = repository.findByChemId(b.chemId());
        repository.deleteAll(old);
        b.sections().forEach((section, lines) -> {
            if (lines.isEmpty()) return;
            repository.save(MsdsCache.builder()
                    .chemId(b.chemId()).chemNameKor(b.chemNameKor()).casNo(b.casNo()).unNo(b.unNo())
                    .sectionCode(section).itemCode("LIVE-" + section)
                    .itemName(sectionName(section)).itemDetail(String.join("|", lines))
                    .pictograms(b.pictograms()).fetchedAt(OffsetDateTime.now()).build());
        });
    }

    private static String sectionName(String s) {
        return switch (s) {
            case "02" -> "유해성·위험성"; case "05" -> "폭발·화재시 대처방법";
            case "07" -> "취급 및 저장방법"; case "08" -> "노출방지 및 개인보호구";
            default -> "항목 " + s;
        };
    }
}
```
`store`는 `protected @Transactional` 자기 호출이 아니라 `LiveOrCache`가 람다로 부르므로 프록시가 적용되지 않는다. 그래서 `@Transactional` 대신 각 `save`의 개별 커밋에 맡기고 애노테이션을 **제거**한다(주석에 이유를 남긴다). 최종 코드에서 `@Transactional` 줄을 삭제하고 `public void store`로 둔다.

- [ ] **Step 7: 컴파일 확인 및 커밋**

Run: `cd backend && ./gradlew compileJava test --tests io.saife.evidence.live.*`
Expected: BUILD SUCCESSFUL, 테스트 PASS

```bash
git add backend/src/main/java/io/saife/evidence/live backend/src/main/java/io/saife/publicapi/repository backend/src/test
git commit -m "feat(evidence): MSDS 라이브 클라이언트 — 목록→상세 4항목 병렬, XML 파서, GHS 픽토그램, 캐시 폴백"
```

---

### Task 5: 법제처 클라이언트·조문 캐시·축별 고정 인용표

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/live/LawArticleParser.java`
- Create: `backend/src/main/java/io/saife/evidence/live/LawClient.java`
- Create: `backend/src/main/java/io/saife/evidence/service/LawArticleService.java`
- Create: `backend/src/main/java/io/saife/evidence/service/LawCitationTable.java`
- Create: `backend/src/main/java/io/saife/evidence/EvidenceAdminController.java`
- Test: `backend/src/test/java/io/saife/evidence/live/LawArticleParserTest.java`
- Test: `backend/src/test/java/io/saife/evidence/service/LawCitationTableTest.java`
- Test fixture: `backend/src/test/resources/fixtures/law-article-36.json`

**Interfaces:**
- Produces:
  - `LawArticleParser.parse(String lawId, String lawName, String json, String detailLink) → List<LawArticle>`
  - `LawClient.findLawMst(String lawName) → Optional<LawMeta(lawId, mst, detailLink, lawName)>`, `LawClient.fetchLawJson(String mst) → String`
  - `LawArticleService.crawlAll() → Map<String,Integer>`(법령명→저장 수), `LawArticleService.get(String lawName, int articleNo, int articleSub) → Fetched<List<LawArticle>>`
  - `LawCitationTable.forAxis(AccidentType) → List<Citation(lawName, articleNo, articleSub, why)>`, `LawCitationTable.common()`, `LawCitationTable.afterIncident()`
  - `POST /api/admin/law/crawl`

- [ ] **Step 1: 픽스처 작성**

`fixtures/law-article-36.json` (실측 응답 축약본):
```json
{"법령":{"법령키":"0017662026021921374","기본정보":{"법령명_한글":"산업안전보건법","법령ID":"001766","시행일자":"20260601"},
 "조문":{"조문단위":[
  {"조문번호":"36","조문시행일자":"20260601","조문키":"0036001","조문제목":"위험성평가의 실시","조문여부":"조문",
   "항":[{"항번호":"①","항내용":"① 사업주는 건설물, 기계ㆍ기구ㆍ설비 ... 이행(이하 \"위험성평가\"라 한다) 하여야 한다. <개정 2026.2.19>"},
         {"항번호":"②","항내용":"② 사업주는 위험성평가 시 고용노동부령으로 정하는 바에 따라 ... 참여시켜야 한다."}]},
  {"조문번호":"37","조문시행일자":"20260801","조문키":"0037000","조문내용":"                    제4장 유해ㆍ위험 방지 조치","조문여부":"전문"},
  {"조문번호":"38","조문가지번호":"2","조문시행일자":"20260601","조문키":"0038002","조문제목":"안전조치 특례","조문여부":"조문","조문내용":"제38조의2 사업주는 ... 하여야 한다."}
 ]}}}
```

- [ ] **Step 2: 실패하는 파서 테스트**

```java
package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.evidence.domain.LawArticle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LawArticleParserTest {

    @Test
    void 항단위로_나누고_장_제목은_건너뛴다() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/fixtures/law-article-36.json"), StandardCharsets.UTF_8);
        List<LawArticle> out = LawArticleParser.parse("001766", "산업안전보건법", json, "/DRF/lawService.do?OC=<oc>&target=law&MST=283449&type=HTML");
        // 36조 ①② + 38조의2 (조문내용) = 3행. 37 '전문'은 제외
        assertThat(out).hasSize(3);
        LawArticle p1 = out.get(0);
        assertThat(p1.getArticleNo()).isEqualTo(36);
        assertThat(p1.getParagraphNo()).isEqualTo(1);
        assertThat(p1.getTitle()).isEqualTo("위험성평가의 실시");
        assertThat(p1.getText()).startsWith("① 사업주는");
        assertThat(p1.getEffectiveOn().toString()).isEqualTo("2026-06-01");
        assertThat(p1.getSourceUrl()).startsWith("https://www.law.go.kr/DRF/lawService.do");
        LawArticle sub = out.get(2);
        assertThat(sub.getArticleNo()).isEqualTo(38);
        assertThat(sub.getArticleSub()).isEqualTo(2);
        assertThat(sub.getParagraphNo()).isZero();
        assertThat(sub.citation()).isEqualTo("산업안전보건법 제38조의2(안전조치 특례)");
    }

    @Test
    void 깨진_JSON은_빈_리스트() {
        assertThat(LawArticleParser.parse("x", "y", "{not json", null)).isEmpty();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.live.LawArticleParserTest`
Expected: FAIL

- [ ] **Step 4: 파서·클라이언트 구현**

`LawArticleParser.java`:
```java
package io.saife.evidence.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.domain.LawArticle;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 법제처 lawService(target=law, type=JSON) 응답 → 항 단위 {@link LawArticle}.
 *
 * <p>{@code 조문여부=전문}은 편·장 제목이라 건너뛴다. 항이 있으면 항마다 한 행, 없으면 조문내용 한 행(paragraphNo=0).
 */
public final class LawArticleParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private LawArticleParser() {}

    public static List<LawArticle> parse(String lawId, String lawName, String json, String detailLink) {
        List<LawArticle> out = new ArrayList<>();
        try {
            JsonNode units = MAPPER.readTree(json).path("법령").path("조문").path("조문단위");
            if (units.isObject()) units = MAPPER.createArrayNode().add(units);
            for (JsonNode u : units) {
                if ("전문".equals(u.path("조문여부").asText(""))) continue;
                int no = u.path("조문번호").asInt(0);
                if (no == 0) continue;
                int sub = u.path("조문가지번호").asInt(0);
                String title = u.path("조문제목").asText(null);
                LocalDate eff = date(u.path("조문시행일자").asText(null));
                String url = sourceUrl(detailLink, no, sub);
                JsonNode paras = u.path("항");
                if (paras.isArray() && !paras.isEmpty()) {
                    int idx = 0;
                    for (JsonNode p : paras) {
                        idx++;
                        String text = p.path("항내용").asText("").trim();
                        if (text.isBlank()) continue;
                        out.add(LawArticle.builder().lawId(lawId).lawName(lawName).articleNo(no).articleSub(sub)
                                .paragraphNo(idx).title(title).text(text).effectiveOn(eff).sourceUrl(url).build());
                    }
                } else {
                    String text = u.path("조문내용").asText("").trim();
                    if (text.isBlank()) continue;
                    out.add(LawArticle.builder().lawId(lawId).lawName(lawName).articleNo(no).articleSub(sub)
                            .paragraphNo(0).title(title).text(text).effectiveOn(eff).sourceUrl(url).build());
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static LocalDate date(String ymd) {
        try { return ymd == null ? null : LocalDate.parse(ymd, YMD); } catch (Exception e) { return null; }
    }

    /** 법제처 상세 링크(HTML)에 조문 앵커를 붙인다. 링크가 없으면 통합검색 URL */
    static String sourceUrl(String detailLink, int no, int sub) {
        String base = detailLink == null ? null : (detailLink.startsWith("http") ? detailLink : "https://www.law.go.kr" + detailLink);
        String anchor = "#J" + no + (sub > 0 ? ":" + sub : "") + ":0";
        return base != null ? base + anchor : "https://www.law.go.kr/lsSc.do?menuId=1&query=" + no;
    }
}
```

`LawClient.java`:
```java
package io.saife.evidence.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 법제처 OPEN API. serviceKey가 아니라 OC(기관코드)를 쓴다 */
@Component
public class LawClient {
    public record LawMeta(String lawId, String mst, String detailLink, String lawName) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String oc;

    public LawClient(@Value("${public-api.law.base-url}") String baseUrl,
                     @Value("${public-api.law.oc:}") String oc) {
        this.baseUrl = baseUrl; this.oc = oc;
    }

    public boolean keyPresent() { return oc != null && !oc.isBlank(); }

    /** 법령명으로 현행 법령의 MST·ID·상세링크를 찾는다. 정확히 같은 이름을 우선 */
    public Optional<LawMeta> findLawMst(String lawName) throws Exception {
        String url = baseUrl + "/lawSearch.do?OC=" + oc + "&target=law&type=JSON&query=" + enc(lawName);
        JsonNode laws = MAPPER.readTree(get(url)).path("LawSearch").path("law");
        if (laws.isObject()) laws = MAPPER.createArrayNode().add(laws);
        JsonNode best = null;
        for (JsonNode l : laws) {
            if (lawName.equals(l.path("법령명한글").asText("")) && "현행".equals(l.path("현행연혁코드").asText(""))) { best = l; break; }
            if (best == null && "현행".equals(l.path("현행연혁코드").asText(""))) best = l;
        }
        if (best == null) return Optional.empty();
        return Optional.of(new LawMeta(best.path("법령ID").asText(), best.path("법령일련번호").asText(),
                best.path("법령상세링크").asText(null), best.path("법령명한글").asText()));
    }

    /** 법령 전체 조문 JSON (MST 기준 1회 호출) */
    public String fetchLawJson(String mst) throws Exception {
        return get(baseUrl + "/lawService.do?OC=" + oc + "&target=law&type=JSON&MST=" + enc(mst));
    }

    /** 조문 1개 (런타임 시행일 확인용). JO는 6자리: 조번호 4자리 + 가지번호 2자리 */
    public String fetchArticleJson(String lawName, int articleNo, int articleSub) throws Exception {
        String jo = String.format("%04d%02d", articleNo, articleSub);
        return get(baseUrl + "/lawService.do?OC=" + oc + "&target=law&type=JSON&LM=" + enc(lawName) + "&JO=" + jo);
    }

    private String get(String url) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(7)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
        return res.body();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```

- [ ] **Step 5: 파서 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests io.saife.evidence.live.LawArticleParserTest`
Expected: PASS 2건

- [ ] **Step 6: 인용표 테스트 작성(실패)**

```java
package io.saife.evidence.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import org.junit.jupiter.api.Test;

class LawCitationTableTest {
    @Test
    void 모든_축에_최소_1건_공통_2건() {
        for (AccidentType axis : AccidentType.values()) {
            assertThat(LawCitationTable.forAxis(axis)).isNotEmpty();
        }
        assertThat(LawCitationTable.common()).hasSize(2);
        assertThat(LawCitationTable.afterIncident()).extracting(LawCitationTable.Citation::articleNo).contains(73, 36);
    }

    @Test
    void 추락은_안전보건규칙_42조가_먼저() {
        LawCitationTable.Citation c = LawCitationTable.forAxis(AccidentType.FALL).get(0);
        assertThat(c.lawName()).isEqualTo(LawCitationTable.RULES);
        assertThat(c.articleNo()).isEqualTo(42);
    }
}
```

- [ ] **Step 7: 인용표·서비스·관리자 엔드포인트 구현**

`LawCitationTable.java`:
```java
package io.saife.evidence.service;

import io.saife.core.domain.AccidentType;
import java.util.List;

/**
 * 발생형태 → 법 조문 고정 매핑. 룰 엔진처럼 Java 상수다 — 무대에서 재현 가능해야 한다.
 *
 * <p>조문 번호가 실제 시드에 존재하는지는 {@code LawArticleServiceIT}가 검증한다. 번호가 틀리면 테스트가 잡는다.
 */
public final class LawCitationTable {
    private LawCitationTable() {}

    public static final String ACT = "산업안전보건법";
    public static final String ENFORCEMENT_RULE = "산업안전보건법 시행규칙";
    public static final String RULES = "산업안전보건기준에 관한 규칙";

    public record Citation(String lawName, int articleNo, int articleSub, String why) {}

    public static List<Citation> forAxis(AccidentType axis) {
        return switch (axis) {
            case FALL -> List.of(
                    new Citation(RULES, 42, 0, "추락의 방지 — 작업발판·안전난간·추락방호망"),
                    new Citation(RULES, 43, 0, "개구부 등의 방호 조치"),
                    new Citation(RULES, 44, 0, "안전대의 부착설비"));
            case CAUGHT -> List.of(
                    new Citation(RULES, 87, 0, "원동기·회전축 등의 위험 방지 — 덮개·울"),
                    new Citation(RULES, 92, 0, "정비 등의 작업 시의 운전정지"));
            case DROP -> List.of(
                    new Citation(RULES, 14, 0, "낙하물에 의한 위험의 방지"),
                    new Citation(RULES, 173, 0, "화물의 적재"));
            case STRUCK -> List.of(
                    new Citation(RULES, 172, 0, "접촉의 방지 — 차량계 하역운반기계"),
                    new Citation(RULES, 179, 0, "전조등 및 후미등"));
            case FIRE -> List.of(
                    new Citation(RULES, 239, 0, "위험물 등이 있는 장소에서 화기 등의 사용 금지"),
                    new Citation(RULES, 241, 0, "화재위험작업 시의 준수사항"),
                    new Citation(RULES, 422, 0, "관리대상 유해물질 취급 시 환기"));
            case PPE -> List.of(
                    new Citation(RULES, 32, 0, "보호구의 지급"));
        };
    }

    /** 위험성평가 기록 의무 — 모든 작업계획서에 붙는다 */
    public static List<Citation> common() {
        return List.of(
                new Citation(ACT, 36, 0, "위험성평가의 실시"),
                new Citation(ENFORCEMENT_RULE, 37, 0, "위험성평가 실시내용 및 결과의 기록·보존"));
    }

    /** 사고 후 — 조사표 제출과 수시평가 */
    public static List<Citation> afterIncident() {
        return List.of(
                new Citation(ENFORCEMENT_RULE, 73, 0, "산업재해 발생 보고 — 휴업 3일 이상 시 1개월 이내"),
                new Citation(ACT, 36, 0, "재해 발생 작업의 재개 전 수시평가"));
    }
}
```

`LawArticleService.java`:
```java
package io.saife.evidence.service;

import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.live.LawArticleParser;
import io.saife.evidence.live.LawClient;
import io.saife.evidence.live.LiveOrCache;
import io.saife.evidence.repository.LawArticleRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조문 캐시 + 라이브 확인. 시드 크롤은 3개 법령 전체를 MST 1회 호출로 가져온다 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LawArticleService {
    public static final List<String> LAWS = List.of(
            LawCitationTable.ACT, LawCitationTable.ENFORCEMENT_RULE, LawCitationTable.RULES);
    private static final String HOST = "law.go.kr";

    private final LawClient client;
    private final LawArticleRepository repository;
    private final LiveOrCache liveOrCache;

    /** 관리자: 3개 법령 전체 조문 수집. 법령명 → 저장(upsert)된 행 수 */
    public Map<String, Integer> crawlAll() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String law : LAWS) {
            try {
                LawClient.LawMeta meta = client.findLawMst(law).orElseThrow(() -> new IllegalStateException("법령을 찾지 못함: " + law));
                List<LawArticle> parsed = LawArticleParser.parse(meta.lawId(), meta.lawName(), client.fetchLawJson(meta.mst()), meta.detailLink());
                out.put(law, upsertAll(parsed));
                log.info("[LAW] {} {}조문 저장", law, parsed.size());
            } catch (Exception e) {
                log.error("[LAW] {} 수집 실패: {}", law, e.getMessage());
                out.put(law, -1);
            }
        }
        return out;
    }

    @Transactional
    public int upsertAll(List<LawArticle> parsed) {
        int n = 0;
        for (LawArticle a : parsed) {
            repository.findByLawIdAndArticleNoAndArticleSubAndParagraphNo(a.getLawId(), a.getArticleNo(), a.getArticleSub(), a.getParagraphNo())
                    .ifPresent(repository::delete);
            repository.save(a);
            n++;
        }
        return n;
    }

    /**
     * 조문 조회 — 라이브로 최신 시행일을 확인하고, 실패하면 캐시.
     * 라이브 성공 시 캐시를 갱신한다(항이 개정됐을 수 있다).
     */
    public Fetched<List<LawArticle>> get(String lawName, int articleNo, int articleSub) {
        return liveOrCache.fetch(HOST, client.keyPresent(),
                () -> {
                    List<LawArticle> cached = repository.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(lawName, articleNo, articleSub);
                    String lawId = cached.isEmpty() ? null : cached.get(0).getLawId();
                    String link = cached.isEmpty() ? null : cached.get(0).getSourceUrl();
                    List<LawArticle> live = LawArticleParser.parse(lawId == null ? "?" : lawId, lawName,
                            client.fetchArticleJson(lawName, articleNo, articleSub), link == null ? null : link.replaceAll("#.*$", ""));
                    return live.isEmpty() ? null : live;
                },
                () -> {
                    List<LawArticle> cached = repository.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(lawName, articleNo, articleSub);
                    return cached.isEmpty() ? Optional.empty() : Optional.of(cached);
                },
                live -> { if (!"?".equals(live.get(0).getLawId())) upsertAll(live); });
    }
}
```

`EvidenceAdminController.java` (A2·A3에서 엔드포인트가 더 붙는다):
```java
package io.saife.evidence;

import io.saife.evidence.service.LawArticleService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 근거 계층 운영 도구. 시연 중에 부르지 않는다 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class EvidenceAdminController {
    private final LawArticleService lawArticleService;

    /** 3개 법령 전체 조문 수집 (OC 필요) */
    @PostMapping("/law/crawl")
    public ResponseEntity<Map<String, Integer>> crawlLaw() {
        return ResponseEntity.ok(lawArticleService.crawlAll());
    }
}
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.*"`
Expected: PASS (파서 2, 인용표 2, LiveOrCache 5, MSDS 3, 스키마 3)

- [ ] **Step 9: 실제 수집 1회 실행 (키 있음) — 시드 생성의 전 단계**

Run (백엔드를 5433 DB로 띄운 뒤):
```bash
curl -X POST http://localhost:8080/api/admin/law/crawl
```
Expected: `{"산업안전보건법":N1,"산업안전보건법 시행규칙":N2,"산업안전보건기준에 관한 규칙":N3}` 모두 양수. 그 뒤 psql로 `LawCitationTable`의 조문이 있는지 확인:
```sql
select law_name, article_no, count(*) from law_article
 where (law_name='산업안전보건기준에 관한 규칙' and article_no in (14,32,42,43,44,87,92,172,173,179,239,241,422))
    or (law_name='산업안전보건법' and article_no in (36,38))
    or (law_name='산업안전보건법 시행규칙' and article_no in (37,73))
 group by 1,2 order by 1,2;
```
없는 번호가 있으면 `LawCitationTable`을 **실제 조문으로 고친다**(번호가 추정이다). 고친 뒤 이 SQL을 `LawArticleServiceIT`(`@SpringBootTest @ActiveProfiles("test")`, 조문이 시드된 DB 전제)에 그대로 옮겨 `LawCitationTable`의 모든 인용이 `repository.findByLawNameAndArticleNoAndArticleSub...`로 1건 이상 나오는지 단언한다.

- [ ] **Step 10: 커밋**

```bash
git add backend/src/main/java/io/saife/evidence backend/src/test
git commit -m "feat(evidence): 법제처 조문 캐시 — 3법령 전체 크롤, 항 단위 파서, 축별 고정 인용표, 라이브 시행일 확인"
```

---

## Self-Review (작성자 확인)

- 스펙 §2·§4·§6.1~6.3 커버: V9(Task 1), image_url 보존(Task 2), LiveOrCache(Task 3), MSDS(Task 4), 법제처+인용표(Task 5). §6.4 크롤러 PDF·§6.5 미디어·§6.6 관리자 export·§5 검색은 A2·A3 계획.
- 플레이스홀더 없음. `LawCitationTable`의 조문 번호는 추정임을 명시하고 Task 5 Step 9에서 실측으로 고정한다.
- 타입 일관성: `Fetched<T>`, `Origin`은 A2·B에서 그대로 쓴다. `MsdsLiveClient.MsdsBundle.sections` 키는 `"02"` 등 2자리 문자열.
- Review Focus 5건 모두 테스트가 있다(Task 2·3·4·5).
