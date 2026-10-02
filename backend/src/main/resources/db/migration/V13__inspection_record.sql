-- 순회점검(근로자 참여) 기록 항목. 시행규칙 제37조의4가 요구하는 기록 항목을 채운다.
--   ① 실시 시기와 담당자          → assessment.assessed_on, assessment.inspector (새 컬럼)
--   ② 참여한 근로자               → assessment.participants (기존 컬럼, 이름을 쉼표로 잇는다)
--   ③ 위험성 수준 결정 결과        → assessment_hazard.risk_level + acceptable (새 컬럼, 허용 가능 여부)
--      개선대책 수립 내용, 이행 결과 → action (+ priority 새 컬럼, 고시 제12조 감소대책 우선순위)

ALTER TABLE assessment ADD COLUMN inspector VARCHAR(100);
COMMENT ON COLUMN assessment.inspector IS '평가 담당자(순회점검 점검자). 시행규칙 제37조의4 제1호';

ALTER TABLE assessment_hazard ADD COLUMN acceptable BOOLEAN;
COMMENT ON COLUMN assessment_hazard.acceptable IS
  '허용 가능 여부. null이면 사람이 정하지 않았고 등급 기본값(상, 중은 불가 / 하는 가능)을 쓴다';

ALTER TABLE action ADD COLUMN priority VARCHAR(20);
COMMENT ON COLUMN action.priority IS
  '감소대책 우선순위(고시 제12조): ELIMINATION(제거, 대체) / ENGINEERING(공학적) / ADMINISTRATIVE(관리적) / PPE(보호구)';

-- 시드 기록 보정. 기존 확정 평가의 담당자는 사업장 안전관리자다
UPDATE assessment SET inspector = '이정훈' WHERE inspector IS NULL AND status = 'CONFIRMED';

-- 시드 조치의 우선순위. 설비를 바꾸거나 설치하는 대책은 공학적, 지도와 점검은 보호구 또는 관리적
UPDATE action SET priority = 'ENGINEERING'    WHERE priority IS NULL AND id IN (1, 3, 5, 7);
UPDATE action SET priority = 'PPE'            WHERE priority IS NULL AND id IN (2, 6);
UPDATE action SET priority = 'ADMINISTRATIVE' WHERE priority IS NULL AND id = 4;
