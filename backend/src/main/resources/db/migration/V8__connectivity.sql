-- 연결성 개선(설계서 §5.2, §7). 컬럼만 먼저 만든다. 고소작업대 이야기 시드는 V10.
ALTER TABLE work_plan ADD COLUMN warning_note TEXT;
COMMENT ON COLUMN work_plan.warning_note IS '사고 연쇄가 붙인 경고. "이 설비에서 {날짜} {발생형태} 사고 — 재개 전 수시평가 #N 확인"';
