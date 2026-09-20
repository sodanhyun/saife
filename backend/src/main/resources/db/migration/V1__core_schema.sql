-- =====================================================================
-- SAIFE 공통 데이터 코어
--
-- 이 출품작의 논지가 스키마로 렌더링된 파일이다.
-- 위험성평가 · 위험작업 작업계획서 · 산업재해조사표가 서로 다른 서류가 아니라
-- 하나의 equipment.id 위에 걸린 이력이 되게 만드는 것이 전부다.
--
-- 사고가 나면 그 설비의 과거 평가·조치·작업계획이 자동으로 소환되고,
-- 작업 브리핑은 그 장소의 위험요인에서 바로 나오며,
-- 대시보드는 그 데이터를 그대로 한 줄로 그린다.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ── 사업장 ──────────────────────────────────────────────────────────
CREATE TABLE site (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    industry_code   VARCHAR(20),               -- 업종 중분류
    worker_count    INT,                        -- 상시근로자 수 (50인 미만 → 산재예방요율제 대상)
    address         VARCHAR(500),
    regular_track   BOOLEAN NOT NULL DEFAULT FALSE,  -- 상시평가 트랙 운영 여부
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE site IS '사업장. 이번 범위에서는 가상 사업장 1곳만 사용한다.';

-- ── 공정 / 라인 / 장소 ──────────────────────────────────────────────
CREATE TABLE process (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT NOT NULL REFERENCES site(id),
    name            VARCHAR(200) NOT NULL,
    work_type       VARCHAR(100),               -- 작업 유형 (도장, 용접, 조립 …)
    location_tag    VARCHAR(200),               -- 위치 태그. 예: 공장동 후면 차양부
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_process_site ON process(site_id);
CREATE INDEX idx_process_location ON process(site_id, location_tag);

-- ── 설비 ─ 모든 이력이 걸리는 축 ────────────────────────────────────
CREATE TABLE equipment (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT NOT NULL REFERENCES site(id),
    process_id      BIGINT REFERENCES process(id),
    name            VARCHAR(200) NOT NULL,
    normalized_name VARCHAR(200) NOT NULL,      -- 공백·대소문자 정규화. 중복 방지 키
    location_tag    VARCHAR(200),
    object_code     VARCHAR(50),                -- 공단 사업장기계기구 대상물코드
    introduced_on   DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 설비 중복 레코드 방지.
-- 자연어 매칭이 false negative를 내면 같은 설비가 두 ID로 쪼개지고,
-- 그 순간 이 출품작 전체가 기대는 "하나의 설비 ID"가 무너진다.
-- 앱 로직이 아니라 스키마로 막는다.
CREATE UNIQUE INDEX uq_equipment_identity
    ON equipment(site_id, COALESCE(location_tag, ''), normalized_name);

CREATE INDEX idx_equipment_process ON equipment(process_id);

-- 설비 변경 이력 (수시평가 트리거 소스)
CREATE TABLE equipment_change (
    id              BIGSERIAL PRIMARY KEY,
    equipment_id    BIGINT NOT NULL REFERENCES equipment(id),
    change_type     VARCHAR(30) NOT NULL,       -- INTRODUCED / MODIFIED / RELOCATED / REMOVED
    description     TEXT,
    occurred_on     DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_equipment_change_eq ON equipment_change(equipment_id, occurred_on DESC);

-- ── 위험요인 ────────────────────────────────────────────────────────
CREATE TABLE hazard (
    id                BIGSERIAL PRIMARY KEY,
    site_id           BIGINT NOT NULL REFERENCES site(id),
    equipment_id      BIGINT REFERENCES equipment(id),
    process_id        BIGINT REFERENCES process(id),
    accident_type     VARCHAR(30) NOT NULL,     -- 발생형태 6축: FALL/CAUGHT/DROP/STRUCK/FIRE/PPE
    missing_control   VARCHAR(200),             -- 빠진 안전조치. 사진 판독의 실제 출력
    description       TEXT NOT NULL,
    source            VARCHAR(30) NOT NULL,     -- PHOTO / NEAR_MISS / INCIDENT / WORK_PLAN / MANUAL
    photo_path        VARCHAR(500),
    ai_suggested      BOOLEAN NOT NULL DEFAULT FALSE,  -- AI가 후보로 올렸는지
    ai_adopted        BOOLEAN,                  -- 사람이 채택했는지. 채택률 지표의 원천
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_hazard_equipment ON hazard(equipment_id, created_at DESC);
CREATE INDEX idx_hazard_process ON hazard(process_id);
COMMENT ON COLUMN hazard.ai_adopted IS
  '성과 지표(후보 채택률)의 원천. AI가 제안하고 사람이 확정한 비율을 여기서 센다.';

-- ── 위험성평가 ──────────────────────────────────────────────────────
CREATE TABLE assessment (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT NOT NULL REFERENCES site(id),
    kind            VARCHAR(20) NOT NULL,       -- INITIAL(최초)/OCCASIONAL(수시)/REGULAR(정기)/ROUTINE(상시)
    trigger_type    VARCHAR(40),                -- EQUIPMENT_CHANGE / INCIDENT / SCHEDULE / PATROL
    trigger_ref_id  BIGINT,                     -- 트리거가 된 레코드 (사고 id 등)
    assessed_on     DATE NOT NULL,
    participants    TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT / CONFIRMED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assessment_site_date ON assessment(site_id, assessed_on DESC);

-- 평가 ↔ 위험요인 (등급이 여기 붙는다. 같은 위험요인도 평가 시점마다 등급이 다를 수 있다)
CREATE TABLE assessment_hazard (
    id              BIGSERIAL PRIMARY KEY,
    assessment_id   BIGINT NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    hazard_id       BIGINT NOT NULL REFERENCES hazard(id),
    frequency       SMALLINT,                   -- 빈도
    severity        SMALLINT,                   -- 강도
    risk_level      VARCHAR(10) NOT NULL,       -- HIGH(상) / MEDIUM(중) / LOW(하)
    rule_trace      TEXT,                       -- 어떤 룰로 이 등급이 나왔는지. 화면에 보여준다
    UNIQUE (assessment_id, hazard_id)
);
COMMENT ON COLUMN assessment_hazard.rule_trace IS
  '등급 판정은 LLM이 아니라 룰 엔진이 한다. 그 근거를 문자열로 남겨 화면에 그대로 띄운다.';

-- ── 감소대책 / 조치 ─────────────────────────────────────────────────
CREATE TABLE action (
    id              BIGSERIAL PRIMARY KEY,
    hazard_id       BIGINT NOT NULL REFERENCES hazard(id),
    assessment_id   BIGINT REFERENCES assessment(id),
    content         TEXT NOT NULL,
    owner           VARCHAR(100),
    due_date        DATE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / DONE / OVERDUE
    evidence_path   VARCHAR(500),
    guide_ref       VARCHAR(200),               -- KOSHA GUIDE 규정번호 (techGdlnNo)
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_action_hazard ON action(hazard_id);
CREATE INDEX idx_action_status ON action(status, due_date);

-- ── 위험작업 작업계획서 (UC3) ───────────────────────────────────────
CREATE TABLE work_plan (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT NOT NULL REFERENCES site(id),
    process_id      BIGINT REFERENCES process(id),
    equipment_id    BIGINT REFERENCES equipment(id),
    work_name       VARCHAR(200) NOT NULL,
    work_place      VARCHAR(200),
    work_date       DATE NOT NULL,
    work_hours      NUMERIC(4,1),
    method          TEXT,                       -- 작업순서 및 방법
    notes           TEXT,                       -- 전달사항
    briefing        TEXT,                       -- 생성된 위험 브리핑. TBM의 디지털 구현체
    briefing_ack_at TIMESTAMPTZ,                -- 작업자 확인 시각. 구두 TBM보다 강한 증빙
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                    -- DRAFT / SUBMITTED / APPROVED / CONDITIONAL / REJECTED / CLOSED
    approval_note   TEXT,                       -- 조건부 승인 시 조건
    approved_by     VARCHAR(100),
    approved_at     TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_work_plan_equipment ON work_plan(equipment_id, work_date DESC);
CREATE INDEX idx_work_plan_status ON work_plan(site_id, status);
COMMENT ON COLUMN work_plan.briefing_ack_at IS
  '상시평가 트랙의 TBM 요건을 이 컬럼이 충족한다. 구두 회의와 달리 확인 기록이 남는다.';

CREATE TABLE work_plan_worker (
    id              BIGSERIAL PRIMARY KEY,
    work_plan_id    BIGINT NOT NULL REFERENCES work_plan(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    position        VARCHAR(100),
    duty            VARCHAR(200)
);

-- 작업계획 ↔ 위험요인 (브리핑의 근거가 어디서 왔는지)
CREATE TABLE work_plan_hazard (
    work_plan_id    BIGINT NOT NULL REFERENCES work_plan(id) ON DELETE CASCADE,
    hazard_id       BIGINT NOT NULL REFERENCES hazard(id),
    PRIMARY KEY (work_plan_id, hazard_id)
);

-- 되묻기 턴에서 채운 슬롯. 대장 기록과 답변이 다르면 여기 남는다.
CREATE TABLE work_plan_slot (
    id              BIGSERIAL PRIMARY KEY,
    work_plan_id    BIGINT NOT NULL REFERENCES work_plan(id) ON DELETE CASCADE,
    slot_key        VARCHAR(60) NOT NULL,       -- work_height / anchor_installed / product_name …
    answered_value  VARCHAR(500),
    ledger_value    VARCHAR(500),               -- 데이터 코어가 알고 있던 값
    conflicted      BOOLEAN NOT NULL DEFAULT FALSE,
    answered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (work_plan_id, slot_key)
);
COMMENT ON TABLE work_plan_slot IS
  '대장 기록 vs 작업자 답변의 불일치를 기록한다. 불일치는 조치 이행 상태 갱신으로 되먹임된다.';

-- ── 아차사고 / 산재사고 (UC2) ───────────────────────────────────────
CREATE TABLE near_miss (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT NOT NULL REFERENCES site(id),
    equipment_id    BIGINT REFERENCES equipment(id),
    description     TEXT NOT NULL,
    accident_type   VARCHAR(30),
    reported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed        BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE incident (
    id                  BIGSERIAL PRIMARY KEY,
    site_id             BIGINT NOT NULL REFERENCES site(id),
    equipment_id        BIGINT REFERENCES equipment(id),
    work_plan_id        BIGINT REFERENCES work_plan(id),  -- 그날 그 작업계획서가 있었는지
    occurred_at         TIMESTAMPTZ NOT NULL,
    victim_name         VARCHAR(100),
    severity            VARCHAR(30),            -- 휴업일수 구간 등
    leave_days          INT,                    -- 휴업 3일 이상 → 조사표 1개월 이내 제출
    accident_type       VARCHAR(30),
    description         TEXT,
    cause               TEXT,
    prevention          TEXT,
    report_due_date     DATE,                   -- 산업재해조사표 법정 기한
    report_status       VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
                        -- NOT_REQUIRED / REQUIRED / DRAFTED / SUBMITTED
    follow_up_assessment_id BIGINT REFERENCES assessment(id),  -- 자동 생성된 수시평가
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_incident_equipment ON incident(equipment_id, occurred_at DESC);
COMMENT ON COLUMN incident.follow_up_assessment_id IS
  '사고 → 수시평가 자동 생성. 루프가 닫히는 지점이고 시연 영상 2:20~2:45가 증명하는 것.';

-- ── 문서 (서식 출력물) ──────────────────────────────────────────────
CREATE TABLE document (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT NOT NULL REFERENCES site(id),
    doc_type        VARCHAR(30) NOT NULL,       -- ASSESSMENT / WORK_PLAN / INCIDENT_REPORT
    ref_id          BIGINT NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_ref ON document(doc_type, ref_id);

-- 회사별 서식 템플릿 (항목을 설정 가능하게 두어 다른 양식도 같은 엔진으로 처리)
CREATE TABLE form_template (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT NOT NULL REFERENCES site(id),
    doc_type        VARCHAR(30) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    schema_json     JSONB NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 에이전트 대화 · 도구 호출 트레이스 ──────────────────────────────
CREATE TABLE conversation (
    id              VARCHAR(64) PRIMARY KEY,    -- correlationId
    site_id         BIGINT NOT NULL REFERENCES site(id),
    user_name       VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    -- ACTIVE / AWAITING_SLOT / CLOSED / ABANDONED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 되묻기 턴이 HTTP 경계를 넘기 때문에, 중단된 턴의 메시지 배열을 여기 영속화한다.
-- 워커 스레드가 SseEmitter를 붙잡고 사람이 타이핑하기를 기다리면 안 된다.
CREATE TABLE conversation_state (
    conversation_id VARCHAR(64) PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE,
    messages_json   JSONB NOT NULL,
    pending_slot    VARCHAR(60),
    last_seq        INT NOT NULL DEFAULT 0,     -- 재개 후 seq는 이어서 증가
    suspended_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ                 -- 타임아웃 시 ABANDONED 마킹
);

CREATE TABLE tool_call (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    call_order      INT NOT NULL,
    tool_name       VARCHAR(80) NOT NULL,
    params_json     TEXT,
    success         BOOLEAN NOT NULL,
    duration_ms     INT,
    error_message   TEXT,
    called_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_call_conv ON tool_call(conversation_id, call_order);
COMMENT ON TABLE tool_call IS
  '도구 호출 트레이스. 화면의 트레이스 패널과 성과 지표(체인 완주율)가 같은 테이블을 본다.';

-- ── 공공 사례 캐시 (무대에서 외부 API를 호출하지 않기 위한 전제) ────
CREATE TABLE public_case (
    id              BIGSERIAL PRIMARY KEY,
    source          VARCHAR(30) NOT NULL,       -- FATALITY(1040) / DISASTER(1060)
    source_key      VARCHAR(80) NOT NULL,       -- arno / boardno
    business        VARCHAR(50),                -- 업종. DISASTER만 제공
    keyword         TEXT,                       -- 정규화된 한 줄 요약. 매칭 주력
    contents        TEXT,
    accident_type   VARCHAR(30),                -- 파싱으로 분류한 발생형태
    region          VARCHAR(50),
    occurred_on     DATE,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, source_key)
);
CREATE INDEX idx_public_case_type ON public_case(accident_type, business);

CREATE TABLE kosha_guide (
    id                  BIGSERIAL PRIMARY KEY,
    guide_no            VARCHAR(50) NOT NULL UNIQUE,   -- techGdlnNo
    guide_name          VARCHAR(500) NOT NULL,         -- techGdlnNm
    announced_on        DATE,                          -- techGdlnOfancYmd
    file_download_url   VARCHAR(500),                  -- fileDownloadUrl
    fetched_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE msds_cache (
    id              BIGSERIAL PRIMARY KEY,
    chem_id         VARCHAR(20) NOT NULL,
    chem_name_kor   VARCHAR(300),
    cas_no          VARCHAR(40),
    un_no           VARCHAR(20),
    section_code    VARCHAR(10) NOT NULL,       -- 02 / 05 / 07 / 08
    item_code       VARCHAR(20),                -- msdsItemCode
    item_name       VARCHAR(300),               -- msdsItemNameKor
    item_detail     TEXT,                       -- '|'가 줄 구분자
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (chem_id, section_code, item_code)
);
COMMENT ON TABLE msds_cache IS
  'MSDS는 건별 호출이라 쿼터를 먹는 유일한 API다. 필요분만 받아 고정한다.';
