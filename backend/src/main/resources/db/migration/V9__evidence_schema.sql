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
