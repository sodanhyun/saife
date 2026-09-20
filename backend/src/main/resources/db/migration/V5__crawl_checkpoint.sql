-- 공공 API 수집 체크포인트.
--
-- 쿼터가 KST 자정에 리셋되므로 중간 실패가 하루를 날린다. 어디까지 받았는지를
-- 매 페이지마다 커밋해 두고, 다음 실행이 그 지점부터 이어받는다.
-- 규칙: .claude/rules/public-api-integration.md

CREATE TABLE crawl_checkpoint (
    dataset         VARCHAR(30) PRIMARY KEY,    -- FATALITY / GUIDE / DISASTER / MSDS
    last_page       INT NOT NULL DEFAULT 0,     -- 성공적으로 저장을 마친 마지막 페이지
    total_count     INT,                        -- API가 알려준 전체 건수
    saved_count     INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'IDLE',  -- IDLE/RUNNING/DONE/FAILED
    last_error      TEXT,
    started_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN crawl_checkpoint.last_page IS
    '저장까지 끝난 페이지. 다음 실행은 last_page+1부터 받는다';
