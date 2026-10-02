-- 사고 보고 항목 보강 (2026-10-03)
-- 1) 상해 종류와 상해 부위: 산업재해조사표(시행규칙 별지 제30호서식) 항목
-- 2) 조사표 제출일: 제출 완료 건은 화면과 목록에 "제출 완료 YYYY-MM-DD"로 표시한다
-- 3) 수시평가 확정 시각: 사고가 만든 수시평가를 사람이 확정한 때. 작업 보류 해제의 기준 시점이다
ALTER TABLE incident ADD COLUMN injury_type VARCHAR(100);
ALTER TABLE incident ADD COLUMN injury_part VARCHAR(100);
ALTER TABLE incident ADD COLUMN report_submitted_on DATE;
ALTER TABLE assessment ADD COLUMN confirmed_at TIMESTAMPTZ;

-- 이미 제출 완료로 기록된 사고의 제출일. 발생일부터 9일째(주말이면 다음 월요일), 기한을 넘지 않게
UPDATE incident
   SET report_submitted_on = LEAST(
         COALESCE(report_due_date, (occurred_at AT TIME ZONE 'Asia/Seoul')::date + 30),
         CASE EXTRACT(ISODOW FROM (occurred_at AT TIME ZONE 'Asia/Seoul')::date + 9)
              WHEN 6 THEN (occurred_at AT TIME ZONE 'Asia/Seoul')::date + 11
              WHEN 7 THEN (occurred_at AT TIME ZONE 'Asia/Seoul')::date + 10
              ELSE (occurred_at AT TIME ZONE 'Asia/Seoul')::date + 9 END)
 WHERE report_status = 'SUBMITTED' AND report_submitted_on IS NULL;

-- 이미 확정된, 사고가 만든 수시평가의 확정 시각: 평가일 16:00(한국 시간), 사고 기록 시각보다 늦게
UPDATE assessment a
   SET confirmed_at = GREATEST(((a.assessed_on + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), a.created_at)
 WHERE a.status = 'CONFIRMED' AND a.trigger_type = 'INCIDENT' AND a.confirmed_at IS NULL;

-- 사고 보고 시드의 상해 종류와 부위(조사표 출력용). 경위 문장에서 확인되는 것만 적는다. 아차사고는 비운다
-- 상해 종류: 문장에 적힌 것 우선, 없으면 발생형태로 짐작하지 않고 경위에서 드러나는 것만
UPDATE incident SET injury_type = '골절'
 WHERE description LIKE '%골절%' AND injury_type IS NULL AND severity <> 'NEAR_MISS';
UPDATE incident SET injury_type = '화상'
 WHERE description LIKE '%화상%' AND injury_type IS NULL AND severity <> 'NEAR_MISS';
UPDATE incident SET injury_type = '열상'
 WHERE (description LIKE '%검지%' OR description LIKE '%파편%') AND injury_type IS NULL AND severity <> 'NEAR_MISS';
-- 상해 부위
UPDATE incident SET injury_part = '발목'
 WHERE description LIKE '%발목%' AND injury_part IS NULL AND severity <> 'NEAR_MISS';
UPDATE incident SET injury_part = '발등'
 WHERE description LIKE '%발등%' AND injury_part IS NULL AND severity <> 'NEAR_MISS';
UPDATE incident SET injury_part = '손등'
 WHERE description LIKE '%손등%' AND injury_part IS NULL AND severity <> 'NEAR_MISS';
UPDATE incident SET injury_part = '손가락'
 WHERE description LIKE '%검지%' AND injury_part IS NULL AND severity <> 'NEAR_MISS';
UPDATE incident SET injury_part = '눈 주위'
 WHERE description LIKE '%눈 주위%' AND injury_part IS NULL AND severity <> 'NEAR_MISS';
