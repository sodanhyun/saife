-- 개선대책 이행 확인 (2026-10-05)
-- 이행 완료를 버튼 한 번이 아니라 증빙과 확인으로 닫는다.
-- 1) 증빙 사진(evidence_path, 기존 컬럼)과 사진 대조 결과(photo_check, JSON 문자열)
-- 2) 이행 내용, 확인자, 개선 후 위험성(고시: 감소대책 실행 후 허용 가능 수준인지 확인)
ALTER TABLE action ADD COLUMN result_note TEXT;
ALTER TABLE action ADD COLUMN verified_by VARCHAR(100);
ALTER TABLE action ADD COLUMN residual_level VARCHAR(10);
ALTER TABLE action ADD COLUMN photo_check TEXT;

COMMENT ON COLUMN action.residual_level IS
  '개선 후 위험성 HIGH/MEDIUM/LOW. HIGH면 이행 확인을 받지 않는다(추가 대책 필요).';

-- 이미 완료로 기록된 조치: 확인자와 개선 후 위험성을 채운다. 증빙 사진은 없던 기록이라 비워 둔다.
-- 공학적 대책과 제거는 하, 관리적 대책과 보호구는 중(사람의 이행에 기대는 대책)
UPDATE action
   SET verified_by = '안전관리자 홍길동',
       residual_level = CASE WHEN priority IN ('ELIMINATION', 'ENGINEERING') THEN 'LOW' ELSE 'MEDIUM' END,
       result_note = content
 WHERE status = 'DONE' AND verified_by IS NULL;

-- 작업자 답변만으로 완료 처리된 조치는 확인 전 상태로 되돌린다(자기보고는 이행 확인이 아니다)
UPDATE action
   SET status = 'PENDING', completed_at = NULL, evidence_path = NULL,
       verified_by = NULL, residual_level = NULL, result_note = NULL
 WHERE evidence_path LIKE '작업자 자기보고%';
