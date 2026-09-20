-- 작업계획서 초안을 "대화"에 묶는다.
--
-- 되묻기 때문에 extractWorkPlan은 한 대화에서 여러 번 호출된다. 그게 정상 흐름이다.
-- 초안 식별을 작업명으로 잡으면 모델이 중간에 작업명을 다듬는 순간(예:
-- "천장 페인트 작업" → "공장동 후면 차양부 천장 페인트 작업") 같은 작업의 계획서가
-- 둘로 쪼개지고 슬롯이 흩어져 브리핑이 반쪽만 나온다 (2026-09-20 스모크 테스트 실측).
--
-- 대화 ID는 모델이 만들어내는 값이 아니라 서버가 발급한 값이라 흔들리지 않는다.
-- 한 대화 = 한 초안.

ALTER TABLE work_plan ADD COLUMN conversation_id VARCHAR(64);

-- 최종 방어는 DB다. 앱 로직만 믿지 않는다 (설비 ID와 같은 원칙).
-- 초안일 때만 유일하다 — 제출 뒤 같은 대화에서 다음 작업을 이어 등록할 수 있어야 한다.
CREATE UNIQUE INDEX uq_work_plan_draft_conversation
    ON work_plan (conversation_id)
    WHERE conversation_id IS NOT NULL AND status = 'DRAFT';

CREATE INDEX idx_work_plan_conversation ON work_plan (conversation_id);
