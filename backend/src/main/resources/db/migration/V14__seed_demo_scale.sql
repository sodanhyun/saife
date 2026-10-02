-- =====================================================================
-- 데모 규모 시드 (1/2): 사업장 이름, 공정, 설비, 위험요인, 평가, 감소대책
--
-- 시연 영상은 모든 사용 사례를 실제 규모로 보여줘야 한다. 설비 6대로는 홈, 설비 이력, 오늘 할 일이
-- 비어 보인다. 그래서 소규모 제조 사업장 1년치 기록을 심는다(공정 7, 설비 24, 위험요인 41,
-- 평가 24, 감소대책 54, 작업 전 점검 42, 사고 6).
--
-- 이름은 전부 샘플이다: (주)샘플정밀 데모공장, 홍길동(안전관리자), 관리감독자 김철수(생산반장),
-- 이영희(가공반장), 정민준(용접반장), 최동훈(창고반장), 박민수(설비팀장). 재해자는 성만 남긴 표기(최OO)다.
--
-- 지키는 것:
--  - 날짜는 전부 CURRENT_DATE 기준 상대값이고 평일로 보정한다(pg_temp.saife_wd: 주말, 공휴일 회피.
--    오늘 전은 직전 평일, 오늘 이후는 다음 평일). 월 순회점검만 달 기준(k달 전 n일)이다.
--    이번 달 순회점검과 올해 정기평가는 일부러 비워 둔다. 오늘 할 일에 두 항목이 뜬다
--  - 시각은 업무 시간 안에서 분산한다(평가 09~16시, 조치 완료 09~17시). 같은 시각을 반복하지 않는다
--  - 이동식 사다리 A(id 1)는 라이브 시연 무대다. 위험요인 1(작업발판 미확보, 1개월 전 순회점검 상)과
--    기한 경과 조치 1(이동식 비계 사용)만 미이행으로 남기고, 작업 전 점검을 두지 않는다
--  - 사고 전 기록이 자연스럽게 나오게 조치의 생성, 기한, 완료를 맞춘다. 사고 시점 미이행 조치는
--    사고 전에 생성되고 기한이 지난 상태다(천장크레인 훅 해지장치, 고소작업대 안전난간, 용접구역 걸레)
--  - 판정 근거(rule_trace)는 RiskRuleEngine과 같은 평문 형식, 조문은 law_article 시드에 있는 것만,
--    표기는 "(제42조제4항)" 형식 하나다. 지침 코드는 kosha_guide 시드에 있는 것만 쓴다
--
-- 이 파일은 scratchpad의 생성기(seedgen/gen.py)로 만들었다. 손으로 고칠 때는 오늘 할 일 불변식
-- (TodaySeedSnapshotTest)과 docs/experiments/reset_demo_data.py의 경계값을 같이 본다.
-- =====================================================================

-- 평일 보정 함수(이 마이그레이션 세션 안에서만 쓴다). 주말과 공휴일(2025~2027, 대체공휴일, 근로자의 날)을
-- 피한다. 오늘보다 앞선 날은 직전 평일로, 오늘 이후는 다음 평일로 민다. 날짜 순서는 뒤집히지 않는다
CREATE OR REPLACE FUNCTION pg_temp.saife_wd(d date) RETURNS date LANGUAGE plpgsql STABLE AS $$
DECLARE
  r    date := d;
  step int  := CASE WHEN d < CURRENT_DATE THEN -1 ELSE 1 END;
BEGIN
  WHILE EXTRACT(ISODOW FROM r) >= 6 OR r = ANY ('{2025-01-01,2025-01-27,2025-01-28,2025-01-29,2025-01-30,2025-03-01,2025-03-03,2025-05-01,2025-05-05,2025-05-06,2025-06-03,2025-06-06,2025-08-15,2025-10-03,2025-10-05,2025-10-06,2025-10-07,2025-10-08,2025-10-09,2025-12-25,2026-01-01,2026-02-16,2026-02-17,2026-02-18,2026-03-01,2026-03-02,2026-05-01,2026-05-05,2026-05-24,2026-05-25,2026-06-03,2026-06-06,2026-07-17,2026-08-15,2026-08-17,2026-09-24,2026-09-25,2026-09-26,2026-10-03,2026-10-05,2026-10-09,2026-12-25,2027-01-01,2027-02-06,2027-02-07,2027-02-08,2027-02-09,2027-03-01,2027-05-01,2027-05-05,2027-05-13,2027-06-06,2027-07-17,2027-08-15,2027-08-16,2027-09-14,2027-09-15,2027-09-16,2027-10-03,2027-10-04,2027-10-09,2027-10-11,2027-12-25,2027-12-27}'::date[]) LOOP
    r := r + step;
  END LOOP;
  RETURN r;
END $$;

-- 다음 평일(그날 제외)
CREATE OR REPLACE FUNCTION pg_temp.saife_next(d date) RETURNS date LANGUAGE plpgsql STABLE AS $$
DECLARE
  r date := d + 1;
BEGIN
  WHILE EXTRACT(ISODOW FROM r) >= 6 OR r = ANY ('{2025-01-01,2025-01-27,2025-01-28,2025-01-29,2025-01-30,2025-03-01,2025-03-03,2025-05-01,2025-05-05,2025-05-06,2025-06-03,2025-06-06,2025-08-15,2025-10-03,2025-10-05,2025-10-06,2025-10-07,2025-10-08,2025-10-09,2025-12-25,2026-01-01,2026-02-16,2026-02-17,2026-02-18,2026-03-01,2026-03-02,2026-05-01,2026-05-05,2026-05-24,2026-05-25,2026-06-03,2026-06-06,2026-07-17,2026-08-15,2026-08-17,2026-09-24,2026-09-25,2026-09-26,2026-10-03,2026-10-05,2026-10-09,2026-12-25,2027-01-01,2027-02-06,2027-02-07,2027-02-08,2027-02-09,2027-03-01,2027-05-01,2027-05-05,2027-05-13,2027-06-06,2027-07-17,2027-08-15,2027-08-16,2027-09-14,2027-09-15,2027-09-16,2027-10-03,2027-10-04,2027-10-09,2027-10-11,2027-12-25,2027-12-27}'::date[]) LOOP
    r := r + 1;
  END LOOP;
  RETURN r;
END $$;

-- 직전 평일(그날 제외). 작업 전 점검은 전 평일 오후에 작성한다
CREATE OR REPLACE FUNCTION pg_temp.saife_prev(d date) RETURNS date LANGUAGE plpgsql STABLE AS $$
DECLARE
  r date := d - 1;
BEGIN
  WHILE EXTRACT(ISODOW FROM r) >= 6 OR r = ANY ('{2025-01-01,2025-01-27,2025-01-28,2025-01-29,2025-01-30,2025-03-01,2025-03-03,2025-05-01,2025-05-05,2025-05-06,2025-06-03,2025-06-06,2025-08-15,2025-10-03,2025-10-05,2025-10-06,2025-10-07,2025-10-08,2025-10-09,2025-12-25,2026-01-01,2026-02-16,2026-02-17,2026-02-18,2026-03-01,2026-03-02,2026-05-01,2026-05-05,2026-05-24,2026-05-25,2026-06-03,2026-06-06,2026-07-17,2026-08-15,2026-08-17,2026-09-24,2026-09-25,2026-09-26,2026-10-03,2026-10-05,2026-10-09,2026-12-25,2027-01-01,2027-02-06,2027-02-07,2027-02-08,2027-02-09,2027-03-01,2027-05-01,2027-05-05,2027-05-13,2027-06-06,2027-07-17,2027-08-15,2027-08-16,2027-09-14,2027-09-15,2027-09-16,2027-10-03,2027-10-04,2027-10-09,2027-10-11,2027-12-25,2027-12-27}'::date[]) LOOP
    r := r - 1;
  END LOOP;
  RETURN r;
END $$;

-- ── 사업장 ─────────────────────────────────────────────────────────
UPDATE site SET name = '(주)샘플정밀 데모공장', address = 'OO시 OO구 (샘플 주소)', worker_count = 38 WHERE id = 1;
UPDATE equipment SET introduced_on = DATE '2024-03-04' WHERE id = 6;

-- ── 기존 시드(V2, V7, V10, V12) 정리: 날짜, 시각, 담당자, 판정 근거 ─────────────
-- 새 볼륨에서는 이야기 전체가 이 마이그레이션의 기준일에 맞춰진다
UPDATE assessment SET assessed_on = pg_temp.saife_wd((CURRENT_DATE - INTERVAL '3 months')::date), created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '3 months')::date) + TIME '13:35') AT TIME ZONE 'Asia/Seoul'), participants = '김철수, 장우진', inspector = '홍길동' WHERE id = 1;
UPDATE assessment SET assessed_on = pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date), created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '11:04') AT TIME ZONE 'Asia/Seoul'), participants = '김철수, 강태호, 오지훈', inspector = '최동훈' WHERE id = 2;
UPDATE assessment SET assessed_on = pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date), created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:17') AT TIME ZONE 'Asia/Seoul'), participants = '최동훈, 김철수, 이영희, 박민수', inspector = '홍길동' WHERE id = 3;
UPDATE assessment SET assessed_on = pg_temp.saife_wd((CURRENT_DATE - 75)), created_at = ((pg_temp.saife_wd((CURRENT_DATE - 75)) + TIME '10:06') AT TIME ZONE 'Asia/Seoul'), participants = '박민수, 강태호', inspector = '홍길동' WHERE id = 4;
UPDATE assessment SET assessed_on = pg_temp.saife_wd((CURRENT_DATE - 45)), created_at = ((pg_temp.saife_wd((CURRENT_DATE - 45)) + TIME '16:06') AT TIME ZONE 'Asia/Seoul'), participants = '박민수, 이도현', inspector = '홍길동' WHERE id = 5;
UPDATE assessment SET assessed_on = pg_temp.saife_wd(LEAST(CURRENT_DATE - 10, date_trunc('month', CURRENT_DATE)::date - 1)), created_at = ((pg_temp.saife_wd(LEAST(CURRENT_DATE - 10, date_trunc('month', CURRENT_DATE)::date - 1)) + TIME '11:47') AT TIME ZONE 'Asia/Seoul'), participants = '박민수, 강태호', inspector = '이영희' WHERE id = 6;
UPDATE assessment SET trigger_ref_id = 1 WHERE id = 5;
UPDATE hazard SET created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '10:51') AT TIME ZONE 'Asia/Seoul') WHERE id = 1;
UPDATE hazard SET created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '10:53') AT TIME ZONE 'Asia/Seoul') WHERE id = 2;
UPDATE hazard SET created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:01') AT TIME ZONE 'Asia/Seoul') WHERE id = 3;
UPDATE hazard SET created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '3 months')::date) + TIME '13:13') AT TIME ZONE 'Asia/Seoul') WHERE id = 4;
UPDATE hazard SET created_at = ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)) + TIME '09:39') AT TIME ZONE 'Asia/Seoul') WHERE id = 5;
UPDATE hazard SET created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '10:13') AT TIME ZONE 'Asia/Seoul') WHERE id = 6;
UPDATE hazard SET created_at = ((pg_temp.saife_wd((CURRENT_DATE - 75)) + TIME '09:04') AT TIME ZONE 'Asia/Seoul') WHERE id = 7;
UPDATE hazard SET created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '10:22') AT TIME ZONE 'Asia/Seoul') WHERE id = 8;
UPDATE hazard SET missing_control = '작업대 안전난간 일부 결손', description = '고소작업대 작업대 단부 안전난간 일부 결손' WHERE id = 7;
UPDATE hazard SET description = '도장 부스 앞 작업자 보안경 미착용으로 판독' WHERE id = 8;
UPDATE assessment_hazard SET risk_level = 'HIGH', rule_trace = '발판 높이 3.2m, 최상부 발판 또는 그 하단 디딤대 사용, 넘어짐 방지(아웃트리거, 고정, 지지자) 없음 (제42조제4항)' WHERE assessment_id = 1 AND hazard_id = 1;
UPDATE assessment_hazard SET risk_level = 'MEDIUM', rule_trace = '사다리 작업 중 안전모 미착용 (제32조)' WHERE assessment_id = 1 AND hazard_id = 2;
UPDATE assessment_hazard SET risk_level = 'HIGH', rule_trace = '컨베이어 구동부 롤러 방호덮개 없음 (제87조)' WHERE assessment_id = 1 AND hazard_id = 4;
UPDATE assessment_hazard SET risk_level = 'HIGH', rule_trace = '발판 높이 3.2m, 최상부 발판 또는 그 하단 디딤대 사용, 넘어짐 방지(아웃트리거, 고정, 지지자) 없음 (제42조제4항)' WHERE assessment_id = 2 AND hazard_id = 1;
UPDATE assessment_hazard SET risk_level = 'MEDIUM', rule_trace = '통로와 차량 동선 중첩 (제22조, 제172조)' WHERE assessment_id = 2 AND hazard_id = 6;
UPDATE assessment_hazard SET risk_level = 'HIGH', rule_trace = '작업대 안전난간 일부 결손 (제186조)' WHERE assessment_id = 2 AND hazard_id = 7;
UPDATE assessment_hazard SET risk_level = 'MEDIUM', rule_trace = '보안경 미착용 판독 (제32조)' WHERE assessment_id = 2 AND hazard_id = 8;
UPDATE assessment_hazard SET risk_level = 'MEDIUM', rule_trace = '조색실 인화성 증기 체류, 점화원 관리 기준 없음 (제232조, 제450조)' WHERE assessment_id = 3 AND hazard_id = 3;
UPDATE assessment_hazard SET risk_level = 'HIGH', rule_trace = '작업대 안전난간 일부 결손 (제186조)' WHERE assessment_id = 4 AND hazard_id = 7;
UPDATE assessment_hazard SET risk_level = 'HIGH', rule_trace = '떨어짐 사고 발생, 휴업예상 28일, 사고 전 상' WHERE assessment_id = 5 AND hazard_id = 7;
UPDATE assessment_hazard SET risk_level = 'MEDIUM', rule_trace = '작업대 안전난간 보수 완료, 안전대 착용 (제186조, 제32조)' WHERE assessment_id = 6 AND hazard_id = 7;
UPDATE action SET assessment_id = 1, content = '차양부 천장 작업 시 이동식 비계(안전난간) 사용', owner = '생산반장 김철수', due_date = pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date), status = 'OVERDUE', guide_ref = 'D-C-7-2026', priority = 'ELIMINATION', completed_at = NULL, created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '3 months')::date) + TIME '14:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 1;
UPDATE action SET assessment_id = 1, content = '사다리 작업 시 안전모 착용 지도, 점검', owner = '생산반장 김철수', due_date = pg_temp.saife_wd((CURRENT_DATE - INTERVAL '2 months')::date), status = 'DONE', guide_ref = 'A-G-12-2026', priority = 'PPE', completed_at = ((LEAST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '2 months')::date) - 1)), pg_temp.saife_prev(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '2 months')::date))) + TIME '12:37') AT TIME ZONE 'Asia/Seoul'), created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '3 months')::date) + TIME '13:53') AT TIME ZONE 'Asia/Seoul') WHERE id = 2;
UPDATE action SET assessment_id = 1, content = '컨베이어 구동부 방호덮개 제작, 설치', owner = '설비팀장 박민수', due_date = pg_temp.saife_wd((CURRENT_DATE - INTERVAL '2 months')::date), status = 'DONE', guide_ref = 'B-M-33-2026', priority = 'ENGINEERING', completed_at = ((LEAST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '2 months')::date) - 3)), pg_temp.saife_prev(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '2 months')::date))) + TIME '15:43') AT TIME ZONE 'Asia/Seoul'), created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '3 months')::date) + TIME '13:57') AT TIME ZONE 'Asia/Seoul') WHERE id = 3;
UPDATE action SET assessment_id = 2, content = '지게차 동선 구획선 도색, 보행자 통로 표시', owner = '창고반장 최동훈', due_date = pg_temp.saife_wd((CURRENT_DATE + 14)), status = 'PENDING', guide_ref = 'B-M-11-2025', priority = 'ENGINEERING', completed_at = NULL, created_at = ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '11:19') AT TIME ZONE 'Asia/Seoul') WHERE id = 4;
UPDATE action SET assessment_id = 5, content = '고소작업대 안전난간 월 1회 점검', owner = '설비팀장 박민수', due_date = pg_temp.saife_wd((CURRENT_DATE + 7)), status = 'PENDING', guide_ref = 'M-155-2023', priority = 'ADMINISTRATIVE', completed_at = NULL, created_at = ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 45)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 45)))) + TIME '09:54') AT TIME ZONE 'Asia/Seoul') WHERE id = 5;
UPDATE action SET assessment_id = 4, content = '작업 전 안전대 착용 지도', owner = '안전관리자 홍길동', due_date = pg_temp.saife_wd((CURRENT_DATE - 73)), status = 'DONE', guide_ref = NULL, priority = 'PPE', completed_at = ((pg_temp.saife_wd((CURRENT_DATE - 71)) + TIME '10:51') AT TIME ZONE 'Asia/Seoul'), created_at = ((pg_temp.saife_wd((CURRENT_DATE - 75)) + TIME '10:28') AT TIME ZONE 'Asia/Seoul') WHERE id = 6;
UPDATE action SET assessment_id = 4, content = '고소작업대 작업대 안전난간 보수', owner = '설비팀장 박민수', due_date = pg_temp.saife_wd((CURRENT_DATE - 55)), status = 'DONE', guide_ref = 'M-155-2023', priority = 'ENGINEERING', completed_at = ((pg_temp.saife_wd((CURRENT_DATE - 20)) + TIME '13:15') AT TIME ZONE 'Asia/Seoul'), created_at = ((pg_temp.saife_wd((CURRENT_DATE - 75)) + TIME '10:32') AT TIME ZONE 'Asia/Seoul') WHERE id = 7;
UPDATE incident SET occurred_at = ((pg_temp.saife_wd((CURRENT_DATE - 45)) + TIME '14:35') AT TIME ZONE 'Asia/Seoul'), created_at = ((pg_temp.saife_wd((CURRENT_DATE - 45)) + TIME '16:05') AT TIME ZONE 'Asia/Seoul'), leave_days = 28, description = '2층 조립구역 조명 교체 중 고소작업대 작업대 안전난간 결손 구간으로 약 2.4m 아래 바닥에 떨어짐, 왼쪽 발목 골절', cause = '불안전 상태: 작업대 단부 안전난간 일부가 빠져 있었음' || chr(10) || '불안전 행동: 결손 구간 쪽으로 몸을 내밀어 기존 조명을 탈거함', prevention = '1. 고소작업대 작업대 안전난간 보수 (담당 설비팀장 박민수, 기한 ' || to_char(pg_temp.saife_wd((CURRENT_DATE - 22)), 'YYYY-MM-DD') || ')' || chr(10) || '2. 고소작업대 안전난간 월 1회 점검 (담당 설비팀장 박민수, 기한 ' || to_char(pg_temp.saife_wd((CURRENT_DATE + 7)), 'YYYY-MM-DD') || ')', report_due_date = (pg_temp.saife_wd((CURRENT_DATE - 45)) + INTERVAL '1 month')::date WHERE id = 1;
UPDATE near_miss SET description = '지게차 후진 중 뒤쪽 보행자와 가까워짐, 접촉 없음', reported_at = ((pg_temp.saife_wd((CURRENT_DATE - 130)) + TIME '16:32') AT TIME ZONE 'Asia/Seoul'), reviewed = true WHERE equipment_id = 5;
UPDATE near_miss SET description = '프레스 금형 교체 중 손이 금형 사이로 들어갈 뻔함, 접촉 없음', reported_at = ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 3)) + TIME '11:13') AT TIME ZONE 'Asia/Seoul'), reviewed = true WHERE equipment_id = 4;

-- ── 공정 ───────────────────────────────────────────────────────────
INSERT INTO process (id, site_id, name, work_type, location_tag) VALUES
  (5, 1, '용접, 제관 라인', '용접', '공장동 1층 제관구역'),
  (6, 1, '조색, 위험물 보관', '저장', '공장동 후면 조색실'),
  (7, 1, '건물, 설비 보전', '보수', '공장동 옥상');
SELECT setval('process_id_seq', 7, true);

-- ── 설비 ───────────────────────────────────────────────────────────
-- normalized_name은 Equipment.normalize()와 같은 규칙(공백 제거 + 소문자)
INSERT INTO equipment (id, site_id, process_id, name, normalized_name, location_tag, object_code, introduced_on) VALUES
  (7, 1, 2, '기계식 프레스 1호', '기계식프레스1호', '공장동 1층 가공구역', 'M-0187', DATE '2016-05-10'),
  (8, 1, 2, 'CNC 선반 2호', 'cnc선반2호', '공장동 1층 가공구역', 'M-0241', DATE '2019-03-18'),
  (9, 1, 2, '머시닝센터 1호', '머시닝센터1호', '공장동 1층 가공구역', 'M-1032', pg_temp.saife_wd((CURRENT_DATE - 150))),
  (10, 1, 2, '탁상 연삭기', '탁상연삭기', '공장동 1층 가공구역', 'M-0305', DATE '2018-08-22'),
  (11, 1, 2, '공기압축기 1호', '공기압축기1호', '공장동 1층 가공구역', 'M-0350', DATE '2017-04-03'),
  (12, 1, 2, '집진기 1호', '집진기1호', '공장동 1층 가공구역', 'M-0377', DATE '2019-10-15'),
  (13, 1, 5, 'CO2 용접기 1호', 'co2용접기1호', '공장동 1층 제관구역', 'M-0612', DATE '2020-02-03'),
  (14, 1, 5, 'CO2 용접기 2호', 'co2용접기2호', '공장동 1층 제관구역', 'M-0613', DATE '2022-07-11'),
  (15, 1, 5, '가스 용기 보관대', '가스용기보관대', '공장동 1층 제관구역', 'M-0640', DATE '2020-02-03'),
  (16, 1, 5, '천장크레인 1호', '천장크레인1호', '공장동 1층 제관구역', 'M-0702', DATE '2018-01-22'),
  (17, 1, 3, '전동 지게차 2호', '전동지게차2호', '창고동 1층', 'M-0921', DATE '2024-06-12'),
  (18, 1, 3, '적재 랙 A열', '적재랙a열', '창고동 1층', 'M-0950', DATE '2019-05-02'),
  (19, 1, 4, '이동식 비계 1호', '이동식비계1호', '공장동 2층 조립구역', 'M-1120', pg_temp.saife_wd((CURRENT_DATE - 200))),
  (20, 1, 4, '말비계 1호', '말비계1호', '공장동 2층 조립구역', 'M-1121', pg_temp.saife_wd((CURRENT_DATE - 200))),
  (21, 1, 4, '조립 컨베이어 1호', '조립컨베이어1호', '공장동 2층 조립구역', 'M-0420', DATE '2021-09-01'),
  (22, 1, 6, '유기용제 저장소', '유기용제저장소', '공장동 후면 조색실', 'M-0805', DATE '2019-03-04'),
  (23, 1, 6, '페인트 혼합기', '페인트혼합기', '공장동 후면 조색실', 'M-0812', DATE '2021-02-15'),
  (24, 1, 7, '지붕 작업 구역', '지붕작업구역', '공장동 옥상', NULL, DATE '2015-03-02');
SELECT setval('equipment_id_seq', 24, true);
INSERT INTO equipment_change (id, equipment_id, change_type, description, occurred_on) VALUES
  (1, 7, 'MODIFIED', '광전자식 방호장치 설치', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 60)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date)))),
  (2, 19, 'INTRODUCED', '이동식 비계(안전난간 일체형) 신규 도입', pg_temp.saife_wd((CURRENT_DATE - 200))),
  (3, 20, 'INTRODUCED', '말비계 신규 도입', pg_temp.saife_wd((CURRENT_DATE - 200))),
  (4, 9, 'INTRODUCED', '머시닝센터 신규 도입, 가공구역 배치 변경', pg_temp.saife_wd((CURRENT_DATE - 150))),
  (5, 16, 'MODIFIED', '훅 해지장치 교체', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 20)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 20)))));
SELECT setval('equipment_change_id_seq', 5, true);

-- ── 위험요인 ───────────────────────────────────────────────────────
INSERT INTO hazard (id, site_id, equipment_id, process_id, accident_type, missing_control, description, source, ai_suggested, ai_adopted, created_at) VALUES
  (9, 1, 7, 2, 'CAUGHT', '광전자식 방호장치 미설치', '금형 사이로 손이 들어가는 구간, 양수조작 버튼이 금형 가까이 있어 한 손 조작 가능', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '10:58') AT TIME ZONE 'Asia/Seoul')),
  (10, 1, 7, 2, 'DROP', '금형 적치 불량', '교체용 금형을 작업대 끝단에 2단으로 적치', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + TIME '10:53') AT TIME ZONE 'Asia/Seoul')),
  (11, 1, 8, 2, 'CAUGHT', '척 방호덮개 개방 사용', '척 방호덮개를 연 채 소재를 교체하고 바로 운전', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:03') AT TIME ZONE 'Asia/Seoul')),
  (12, 1, 8, 2, 'PPE', '보안경 미착용', 'CNC 선반 절삭 중 작업자 보안경 미착용', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + TIME '13:06') AT TIME ZONE 'Asia/Seoul')),
  (13, 1, 9, 2, 'CAUGHT', '도어 열림 정지 확인 미흡', '신규 머시닝센터 도어를 연 상태의 정지 기능을 확인한 기록 없음', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - 148)) + TIME '10:37') AT TIME ZONE 'Asia/Seoul')),
  (14, 1, 9, 2, 'STRUCK', '설비 주변 통로 폭 미확보', '머시닝센터 배치 후 설비와 기둥 사이 통로가 좁아 대차 통행과 겹침', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - 148)) + TIME '10:42') AT TIME ZONE 'Asia/Seoul')),
  (15, 1, 10, 2, 'DROP', '숫돌 덮개 조정편 간격 과다', '연삭숫돌과 덮개 조정편 사이가 벌어져 파편이 앞으로 튈 수 있음', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:06') AT TIME ZONE 'Asia/Seoul')),
  (16, 1, 10, 2, 'PPE', '보안경 미착용', '탁상 연삭기 공구 연마 중 보안경 미착용', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + TIME '10:56') AT TIME ZONE 'Asia/Seoul')),
  (17, 1, 11, 2, 'CAUGHT', 'V벨트 풀리 방호덮개 미설치', '공기압축기 V벨트와 풀리가 노출된 상태', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:11') AT TIME ZONE 'Asia/Seoul')),
  (18, 1, 12, 2, 'FIRE', '분진 퇴적', '집진기 하부 호퍼와 덕트에 금속 분진이 쌓여 있음', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:13') AT TIME ZONE 'Asia/Seoul')),
  (19, 1, 12, 2, 'CAUGHT', '배기팬 벨트 덮개 일부 탈락', '집진기 배기팬 벨트 덮개 고정 볼트 2개 탈락', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)) + TIME '12:25') AT TIME ZONE 'Asia/Seoul')),
  (20, 1, 13, 5, 'FIRE', '불티 비산 방지 미흡', '용접 작업 반경 안에 걸레와 종이 상자가 놓여 있음', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:19') AT TIME ZONE 'Asia/Seoul')),
  (21, 1, 13, 5, 'PPE', '용접용 보안면 미착용', '용접 중 보안면 대신 일반 보안경만 착용', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + TIME '12:30') AT TIME ZONE 'Asia/Seoul')),
  (22, 1, 14, 5, 'FIRE', '소화기 미비치', '용접 2번 부스 주변에 소화기와 위치 표시 없음', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + TIME '12:34') AT TIME ZONE 'Asia/Seoul')),
  (23, 1, 15, 5, 'DROP', '용기 전도 방지 미흡', '산소, LPG 용기 고정 체인을 걸지 않고 세워 둠', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:21') AT TIME ZONE 'Asia/Seoul')),
  (24, 1, 16, 5, 'DROP', '줄걸이 용구 손상', '슬링 벨트 가장자리 절상, 폐기 기준과 점검 기록 없음', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:27') AT TIME ZONE 'Asia/Seoul')),
  (25, 1, 16, 5, 'DROP', '인양물 하부 출입 통제 미흡', '인양 중 인양물 아래로 작업자가 지나다님', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:31') AT TIME ZONE 'Asia/Seoul')),
  (26, 1, 5, 3, 'PPE', '좌석 안전띠 미착용', '지게차 운전원 좌석 안전띠 미착용', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:35') AT TIME ZONE 'Asia/Seoul')),
  (27, 1, 18, 3, 'PPE', '안전모 미착용', '적재 랙 앞 작업자 안전모 미착용으로 판독', 'PHOTO', true, false, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + TIME '13:16') AT TIME ZONE 'Asia/Seoul')),
  (28, 1, 16, 5, 'DROP', '훅 해지장치 파손', '천장크레인 훅 해지장치 스프링이 부러져 열린 상태', 'PHOTO', true, true, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '09:54') AT TIME ZONE 'Asia/Seoul')),
  (29, 1, 17, 3, 'STRUCK', '통로 미확보, 통로 표시 없음', '창고동 출입구 보행 통로가 구획되지 않았고 후진 경보기가 작동하지 않음', 'INCIDENT', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - 60)) + TIME '17:32') AT TIME ZONE 'Asia/Seoul')),
  (30, 1, 17, 3, 'DROP', '적재 높이 초과', '팔레트 적재 높이가 운전 시야를 가림', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:37') AT TIME ZONE 'Asia/Seoul')),
  (31, 1, 18, 3, 'DROP', '상단 적재물 결속 미흡', '랙 최상단 박스 적재물을 결속하지 않고 랙 아래로 통행', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:43') AT TIME ZONE 'Asia/Seoul')),
  (33, 1, 19, 4, 'FALL', '바퀴 고정, 안전난간 점검 기록 없음', '이동식 비계 사용 전 바퀴 브레이크와 안전난간 점검 기록 없음', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - 198)) + TIME '12:00') AT TIME ZONE 'Asia/Seoul')),
  (34, 1, 20, 4, 'FALL', '보조부재 미설치', '말비계 양측 보조부재를 걸지 않고 사용', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - 198)) + TIME '12:03') AT TIME ZONE 'Asia/Seoul')),
  (35, 1, 21, 4, 'CAUGHT', '테일 풀리 방호덮개 미설치', '조립 컨베이어 테일 풀리가 노출된 상태', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:47') AT TIME ZONE 'Asia/Seoul')),
  (36, 1, 22, 6, 'FIRE', '용기 개방 보관', '유기용제 용기 뚜껑을 연 채 보관, 저장소 안에 증기 냄새', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:49') AT TIME ZONE 'Asia/Seoul')),
  (37, 1, 22, 6, 'FIRE', '소화기 미비치', '저장소 출입구 소화기가 없거나 압력이 떨어져 있음', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + TIME '13:08') AT TIME ZONE 'Asia/Seoul')),
  (38, 1, 23, 6, 'CAUGHT', '교반 날개 방호덮개 미설치', '페인트 혼합기 교반 날개가 노출된 상태로 운전', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:55') AT TIME ZONE 'Asia/Seoul')),
  (39, 1, 23, 6, 'PPE', '방독마스크 미착용', '조색 중 유기용제를 다루면서 방독마스크 미착용', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + TIME '13:13') AT TIME ZONE 'Asia/Seoul')),
  (40, 1, 24, 7, 'FALL', '안전대 부착설비 미설치', '지붕 위 작업 시 안전대를 걸 구명줄이 없음', 'MANUAL', false, NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '11:58') AT TIME ZONE 'Asia/Seoul')),
  (41, 1, 24, 7, 'FALL', '채광창 덮개 미설치', '지붕 채광창(선라이트) 주변에 덮개와 추락방호망 없음', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8)) + TIME '09:44') AT TIME ZONE 'Asia/Seoul')),
  (42, 1, 2, 1, 'PPE', '방독마스크 미착용', '도장 부스 앞 희석 작업 중 방독마스크 미착용', 'PHOTO', true, true, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + TIME '13:12') AT TIME ZONE 'Asia/Seoul'));
SELECT setval('hazard_id_seq', 42, true);

-- ── 평가 ───────────────────────────────────────────────────────────
INSERT INTO assessment (id, site_id, kind, trigger_type, trigger_ref_id, assessed_on, participants, status, inspector, created_at) VALUES
  (7, 1, 'REGULAR', 'SCHEDULE', NULL, pg_temp.saife_wd(GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60))), '최동훈, 김철수, 이영희, 박민수, 정민준', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd(GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60))) + TIME '14:35') AT TIME ZONE 'Asia/Seoul')),
  (8, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)), '김철수, 장우진', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + TIME '13:34') AT TIME ZONE 'Asia/Seoul')),
  (9, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)), '오지훈, 윤서연', 'CONFIRMED', '이영희', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)) + TIME '10:46') AT TIME ZONE 'Asia/Seoul')),
  (10, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)), '정민준, 강태호', 'CONFIRMED', '박민수', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + TIME '13:05') AT TIME ZONE 'Asia/Seoul')),
  (11, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)), '김철수, 윤서연', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + TIME '13:44') AT TIME ZONE 'Asia/Seoul')),
  (12, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)), '박민수, 오지훈', 'CONFIRMED', '최동훈', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)) + TIME '13:06') AT TIME ZONE 'Asia/Seoul')),
  (13, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)), '이영희, 장우진', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + TIME '11:50') AT TIME ZONE 'Asia/Seoul')),
  (14, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9)), '김철수, 정민준', 'CONFIRMED', '박민수', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9)) + TIME '12:36') AT TIME ZONE 'Asia/Seoul')),
  (15, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '5 months')::date + 11)), '박민수, 이영희', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '5 months')::date + 11)) + TIME '10:34') AT TIME ZONE 'Asia/Seoul')),
  (16, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8)), '최동훈, 김철수, 윤서연', 'CONFIRMED', '정민준', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8)) + TIME '10:30') AT TIME ZONE 'Asia/Seoul')),
  (17, 1, 'ROUTINE', 'PATROL', NULL, pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '2 months')::date + 13)), '김철수, 이영희, 오지훈', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '2 months')::date + 13)) + TIME '10:41') AT TIME ZONE 'Asia/Seoul')),
  (18, 1, 'OCCASIONAL', 'EQUIPMENT_CHANGE', 4, pg_temp.saife_wd((CURRENT_DATE - 148)), '이영희, 장우진', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd((CURRENT_DATE - 148)) + TIME '11:14') AT TIME ZONE 'Asia/Seoul')),
  (19, 1, 'OCCASIONAL', 'EQUIPMENT_CHANGE', 2, pg_temp.saife_wd((CURRENT_DATE - 198)), '김철수, 강태호', 'CONFIRMED', '박민수', ((pg_temp.saife_wd((CURRENT_DATE - 198)) + TIME '12:26') AT TIME ZONE 'Asia/Seoul')),
  (20, 1, 'OCCASIONAL', 'INCIDENT', 2, pg_temp.saife_wd((CURRENT_DATE - 240)), '이영희, 장우진', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd((CURRENT_DATE - 240)) + TIME '11:24') AT TIME ZONE 'Asia/Seoul')),
  (21, 1, 'OCCASIONAL', 'INCIDENT', 3, pg_temp.saife_wd((CURRENT_DATE - 115)), '이영희, 강태호', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd((CURRENT_DATE - 115)) + TIME '15:38') AT TIME ZONE 'Asia/Seoul')),
  (22, 1, 'OCCASIONAL', 'INCIDENT', 4, pg_temp.saife_wd((CURRENT_DATE - 60)), '오지훈, 윤서연', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd((CURRENT_DATE - 60)) + TIME '17:32') AT TIME ZONE 'Asia/Seoul')),
  (23, 1, 'OCCASIONAL', 'INCIDENT', 5, pg_temp.saife_wd((CURRENT_DATE - 150)), '정민준, 강태호, 장우진', 'CONFIRMED', '홍길동', ((pg_temp.saife_wd((CURRENT_DATE - 150)) + TIME '15:58') AT TIME ZONE 'Asia/Seoul')),
  (24, 1, 'OCCASIONAL', 'INCIDENT', 6, pg_temp.saife_wd((CURRENT_DATE - 20)), NULL, 'DRAFT', NULL, ((pg_temp.saife_wd((CURRENT_DATE - 20)) + TIME '11:18') AT TIME ZONE 'Asia/Seoul'));
SELECT setval('assessment_id_seq', 24, true);

INSERT INTO assessment_hazard (assessment_id, hazard_id, frequency, severity, risk_level, rule_trace, acceptable) VALUES
  (3, 1, 2, 2, 'MEDIUM', '발판 높이 미확인, 최상부 디딤대 사용 여부와 넘어짐 방지 확인 필요 (제42조제4항)', NULL),
  (3, 2, 2, 2, 'MEDIUM', '사다리 작업 중 안전모 미착용 (제32조)', NULL),
  (3, 9, 3, 3, 'HIGH', '금형 사이 손 투입 구간, 양수조작 버튼 한 손 조작 가능 (제103조)', NULL),
  (3, 11, 2, 2, 'MEDIUM', '척 방호덮개 연 채 운전, 회전 척 접촉 가능 (제87조)', NULL),
  (3, 15, 2, 2, 'MEDIUM', '숫돌 덮개 조정편 간격 과다, 파편 비산 가능 (제122조)', NULL),
  (3, 17, 3, 3, 'HIGH', '공기압축기 V벨트, 풀리 노출 (제87조제11항)', NULL),
  (3, 18, 2, 2, 'MEDIUM', '호퍼, 덕트 금속 분진 퇴적, 점화원 관리 필요 (제232조)', NULL),
  (3, 20, 2, 2, 'MEDIUM', '용접 불티 비산 반경 안에 가연물 (제241조)', NULL),
  (3, 23, 2, 2, 'MEDIUM', '용기 고정 체인 미체결, 넘어지면 밸브 파손 (제234조)', NULL),
  (3, 24, 3, 3, 'HIGH', '슬링 벨트 가장자리 절상, 폐기 기준 없음 (제169조)', NULL),
  (3, 25, 2, 2, 'MEDIUM', '인양물 아래 작업자 출입 (제146조)', NULL),
  (3, 26, 2, 2, 'MEDIUM', '지게차 운전원 좌석 안전띠 미착용 (제183조)', NULL),
  (3, 30, 2, 2, 'MEDIUM', '팔레트 적재 높이가 운전 시야를 가림 (제173조)', NULL),
  (3, 31, 3, 3, 'HIGH', '랙 최상단 적재물 결속 없음, 랙 아래 통행 (제393조)', NULL),
  (3, 35, 3, 3, 'HIGH', '조립 컨베이어 테일 풀리 노출 (제87조)', NULL),
  (3, 36, 3, 3, 'HIGH', '유기용제 용기 개방 보관, 저장소 안 증기 체류 (제232조)', NULL),
  (3, 38, 3, 3, 'HIGH', '페인트 혼합기 교반 날개 노출 (제87조제8항)', NULL),
  (3, 40, 3, 3, 'HIGH', '지붕 높이 6m, 안전대 부착설비 없음 (제44조)', NULL),
  (1, 12, 1, 2, 'LOW', '절삭 중 보안경 착용 확인 (제32조)', NULL),
  (2, 4, 1, 2, 'LOW', '컨베이어 구동부 방호덮개 설치 확인 (제87조)', NULL),
  (2, 14, 2, 2, 'MEDIUM', '머시닝센터와 기둥 사이 통로 폭 부족, 대차 동선과 겹침 (제22조)', NULL),
  (2, 18, 2, 2, 'MEDIUM', '호퍼, 덕트 분진 재퇴적, 청소 주기 없음 (제232조)', NULL),
  (2, 28, 3, 3, 'HIGH', '훅 해지장치 파손, 인양 중 줄걸이 이탈 가능 (제137조)', NULL),
  (2, 29, 1, 2, 'LOW', '보행 통로 구획, 후진 경보기 작동 확인 (제179조)', NULL),
  (2, 30, 2, 2, 'MEDIUM', '팔레트 적재 높이 제한 표지 부착 (제173조)', true),
  (2, 37, 3, 3, 'HIGH', '저장소 출입구 소화기 압력 미달, 예비 소화기 없음 (제243조)', NULL),
  (6, 11, 1, 2, 'LOW', '척 방호덮개 닫고 운전 확인 (제87조)', NULL),
  (6, 35, 1, 2, 'LOW', '테일 풀리 방호덮개 설치 확인 (제87조)', NULL),
  (7, 2, 2, 2, 'MEDIUM', '사다리 작업 중 안전모 미착용 확인 (제32조)', NULL),
  (7, 3, 2, 2, 'MEDIUM', '조색실 인화성 증기 체류 가능, 환기 설비 없음 (제232조, 제450조)', NULL),
  (7, 5, 1, 2, 'LOW', '금형 교체 시 안전블록 사용 중 (제104조)', NULL),
  (7, 9, 1, 2, 'LOW', '광전자식 방호장치 설치, 작동 양호 (제103조)', NULL),
  (7, 11, 1, 2, 'LOW', '척 방호덮개 닫힘 운전, 작업 표준 게시 (제87조)', NULL),
  (7, 17, 1, 2, 'LOW', 'V벨트, 풀리 방호덮개 설치 상태 양호 (제87조제11항)', NULL),
  (7, 20, 2, 2, 'MEDIUM', '용접 불티 비산 범위에 가연물, 화재감시자 미지정 (제241조, 제241조의2)', NULL),
  (7, 23, 1, 2, 'LOW', '용기 고정 체인 체결, 충전 용기와 빈 용기 구분 보관 중 (제234조)', NULL),
  (7, 24, 2, 2, 'MEDIUM', '줄걸이 용구 월 1회 점검 운영 중, 섬유벨트 마모 진행 (제169조)', NULL),
  (7, 31, 1, 2, 'LOW', '적재물 결속, 상단 적재 제한 표지 부착 (제393조)', NULL),
  (7, 35, 1, 2, 'LOW', '테일 풀리 방호덮개 설치 상태 유지 (제87조)', NULL),
  (7, 36, 2, 2, 'MEDIUM', '용기 개방 보관 사례, 국소배기 없음 (제232조)', NULL),
  (7, 38, 1, 2, 'LOW', '교반부 방호덮개, 덮개 열림 시 정지 장치 작동 (제87조제8항)', NULL),
  (7, 40, 2, 2, 'MEDIUM', '지붕 높이 6m, 구명줄 설치, 안전대 착용 관리 미흡 (제32조, 제44조)', NULL),
  (8, 9, 3, 3, 'HIGH', '광전자식 방호장치 설치 전, 양수조작 버튼 한 손 조작 가능 (제103조)', NULL),
  (8, 12, 2, 2, 'MEDIUM', '절삭 중 보안경 미착용 (제32조)', NULL),
  (8, 42, 2, 2, 'MEDIUM', '유기용제 희석 중 방독마스크 미착용 (제450조)', NULL),
  (9, 5, 3, 3, 'HIGH', '금형 교체 시 안전블록 미사용 (제104조)', NULL),
  (9, 31, 1, 2, 'LOW', '랙 상단 적재물 결속 상태 양호 (제393조)', NULL),
  (10, 21, 2, 2, 'MEDIUM', '용접 중 보안면 대신 보안경 착용 (제32조)', NULL),
  (10, 22, 2, 2, 'MEDIUM', '용접 2번 부스 소화기 없음, 위치 표시 없음 (제243조)', NULL),
  (10, 9, 1, 2, 'LOW', '광전자식 방호장치 작동 확인 (제103조)', NULL),
  (11, 37, 2, 2, 'MEDIUM', '저장소 출입구 소화기 없음 (제243조)', NULL),
  (11, 39, 2, 2, 'MEDIUM', '조색 중 방독마스크 미착용 (제450조)', NULL),
  (11, 27, 2, 2, 'MEDIUM', '안전모 미착용 판독 (제32조)', NULL),
  (12, 26, 1, 2, 'LOW', '운전원 좌석 안전띠 착용 확인 (제183조)', NULL),
  (12, 30, 2, 2, 'MEDIUM', '팔레트 적재 높이 1.5m 초과분 있음 (제173조)', NULL),
  (12, 19, 3, 3, 'HIGH', '집진기 배기팬 벨트 덮개 고정 볼트 탈락 (제87조)', NULL),
  (13, 10, 2, 2, 'MEDIUM', '교체용 금형 작업대 끝단 2단 적치 (제393조)', NULL),
  (13, 16, 2, 2, 'MEDIUM', '연삭 중 보안경 미착용 (제32조)', NULL),
  (14, 42, 1, 2, 'LOW', '희석 작업 방독마스크 착용 확인 (제450조)', NULL),
  (14, 21, 1, 2, 'LOW', '용접 보안면 착용 확인 (제32조)', NULL),
  (14, 20, 2, 2, 'MEDIUM', '용접 불티 반경 안에 유기용제 묻은 걸레 (제241조)', NULL),
  (15, 17, 1, 2, 'LOW', 'V벨트 덮개 고정 상태 양호 (제87조제11항)', NULL),
  (15, 38, 1, 2, 'LOW', '교반 날개 덮개 닫힘 확인 (제87조제8항)', NULL),
  (15, 31, 1, 2, 'LOW', '상단 적재물 결속 유지 (제393조)', NULL),
  (15, 23, 1, 2, 'LOW', '용기 고정 체인 체결 확인 (제234조)', NULL),
  (16, 41, 3, 3, 'HIGH', '지붕 채광창 덮개 없음, 밟으면 떨어짐 (제45조)', NULL),
  (16, 14, 2, 2, 'MEDIUM', '머시닝센터 주변 통로 폭 미확보 (제22조)', NULL),
  (16, 33, 1, 2, 'LOW', '바퀴 고정, 안전난간 점검표 운영 확인 (제68조)', NULL),
  (17, 10, 1, 2, 'LOW', '금형 보관대 지정, 2단 적치 없음 (제393조)', NULL),
  (17, 22, 2, 2, 'MEDIUM', '용접 부스 소화기 비치, 화재감시자 지정 (제241조의2, 제243조)', NULL),
  (17, 39, 1, 2, 'LOW', '조색 중 방독마스크 착용 확인 (제450조)', NULL),
  (17, 15, 1, 2, 'LOW', '덮개 조정편 간격 조정, 비산 방지판 설치 (제122조)', NULL),
  (17, 16, 1, 2, 'LOW', '연삭 중 보안경 착용 확인 (제32조)', NULL),
  (17, 20, 2, 2, 'MEDIUM', '용접 불티 비산 범위 넓음, 화재감시자 배치, 방화포 사용 중 (제241조, 제241조의2)', NULL),
  (17, 21, 1, 2, 'LOW', '용접 보안면 착용 확인 (제32조)', NULL),
  (18, 13, 2, 2, 'MEDIUM', '도어 열림 시 정지 기능 확인 기록 없음, 확인 후 판정', NULL),
  (18, 14, 2, 2, 'MEDIUM', '설비와 기둥 사이 통로 폭 부족 (제22조)', NULL),
  (19, 33, 2, 2, 'MEDIUM', '작업발판 높이 3.6m, 안전난간, 바퀴 고정 확인 (제68조)', NULL),
  (19, 34, 2, 2, 'MEDIUM', '말비계 높이 2.2m, 보조부재 설치 확인 (제67조)', NULL),
  (20, 9, 2, 2, 'MEDIUM', '끼임 사고 발생, 휴업예상 2일, 사고 전 하', NULL),
  (21, 16, 2, 2, 'MEDIUM', '연삭 중 보안경 미착용 (제32조)', NULL),
  (21, 15, 2, 2, 'MEDIUM', '물체에 맞음 사고 발생, 휴업예상 1일, 사고 전 중', NULL),
  (22, 30, 2, 2, 'MEDIUM', '팔레트 적재 높이 1.5m 초과분 있음 (제173조)', NULL),
  (22, 29, 2, 2, 'MEDIUM', '부딪힘 아차사고, 사고 전 평가 없음', NULL),
  (23, 21, 1, 2, 'LOW', '용접 보안면 착용 확인 (제32조)', NULL),
  (23, 20, 3, 3, 'HIGH', '화재 사고 발생, 휴업예상 6일, 사고 전 중', NULL),
  (24, 28, 3, 3, 'HIGH', '물체에 맞음 사고 발생, 휴업예상 42일, 사고 전 상', NULL),
  (24, 25, 3, 3, 'HIGH', '물체에 맞음 사고 발생, 휴업예상 42일, 사고 전 중', NULL),
  (24, 24, 3, 3, 'HIGH', '물체에 맞음 사고 발생, 휴업예상 42일, 사고 전 중', NULL);

-- ── 감소대책 ───────────────────────────────────────────────────────
INSERT INTO action (id, hazard_id, assessment_id, content, owner, due_date, status, guide_ref, priority, completed_at, created_at) VALUES
  (8, 9, 3, '프레스 1호 광전자식 방호장치 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 45)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-M-36-2026', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 60)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '16:08') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:35') AT TIME ZONE 'Asia/Seoul')),
  (9, 17, 3, '공기압축기 V벨트 풀리 방호덮개 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 30)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', NULL, 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 21)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '16:52') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:38') AT TIME ZONE 'Asia/Seoul')),
  (10, 24, 3, '손상 슬링 벨트 폐기, 줄걸이 용구 월 1회 점검표 운영', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-M-12-2025', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 10)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '13:07') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:34') AT TIME ZONE 'Asia/Seoul')),
  (11, 31, 3, '랙 상단 적재물 결속, 상단 2단 적재 제한 표지', '창고반장 최동훈', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 30)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'M-49-2023', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 27)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '16:13') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:47') AT TIME ZONE 'Asia/Seoul')),
  (12, 35, 3, '조립 컨베이어 테일 풀리 방호덮개 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 30)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-M-33-2026', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 33)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '11:31') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:46') AT TIME ZONE 'Asia/Seoul')),
  (13, 36, 3, '유기용제 용기 밀폐 보관, 저장소 국소배기장치 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 60)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'P-34-2012', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 57)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '13:11') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:52') AT TIME ZONE 'Asia/Seoul')),
  (14, 38, 3, '혼합기 교반부 방호덮개와 덮개 열림 시 정지 장치 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 45)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-M-2-2025', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 66)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '15:41') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:44') AT TIME ZONE 'Asia/Seoul')),
  (15, 40, 3, '지붕 용마루 구명줄(안전대 부착설비) 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 60)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'C-59-2022', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 74)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '16:50') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:50') AT TIME ZONE 'Asia/Seoul')),
  (16, 20, 3, '용접구역 가연물 정리, 불티 비산 방지포 비치', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'A-G-11-2025', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 12)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '10:48') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '12:59') AT TIME ZONE 'Asia/Seoul')),
  (17, 1, 3, '이동식 사다리 미끄럼 방지 고무 교체', '생산반장 김철수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'A-G-4-2025', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 9)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '14:06') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:02') AT TIME ZONE 'Asia/Seoul')),
  (18, 26, 3, '지게차 운전원 좌석 안전띠 착용 지도', '창고반장 최동훈', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-M-11-2025', 'PPE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 6)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '11:29') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:14') AT TIME ZONE 'Asia/Seoul')),
  (19, 23, 3, '가스 용기 전도 방지 체인 체결, 충전 용기와 빈 용기 구분 보관', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 21)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', NULL, 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 15)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '12:06') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:16') AT TIME ZONE 'Asia/Seoul')),
  (20, 15, 3, '연삭기 숫돌 덮개 조정편 간격 3mm 이내로 조정', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-M-14-2025', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 8)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '10:26') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:03') AT TIME ZONE 'Asia/Seoul')),
  (21, 11, 3, 'CNC 선반 척 방호덮개 닫고 운전, 작업 표준 게시', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 21)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'M-96-2012', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 20)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '16:58') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:23') AT TIME ZONE 'Asia/Seoul')),
  (22, 18, 3, '집진기 분진 정기 청소, 점화원 반입 금지 표지', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 30)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'D-43-2012', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 29)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '10:19') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:18') AT TIME ZONE 'Asia/Seoul')),
  (23, 25, 3, '인양 작업 시 하부 출입 통제선 설치', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 21)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-M-34-2026', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 26)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '16:57') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:28') AT TIME ZONE 'Asia/Seoul')),
  (24, 30, 3, '팔레트 적재 높이 1.5m 제한 표지', '창고반장 최동훈', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 21)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'M-49-2023', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 18)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '11:14') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:31') AT TIME ZONE 'Asia/Seoul')),
  (25, 3, 3, '도장 부스 배기 팬 가동 확인 후 작업, 부스 안 점화원 반입 금지', '생산반장 김철수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))), 'DONE', 'B-E-17-2026', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + 11)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date))) + TIME '14:03') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '13 months')::date) + TIME '13:19') AT TIME ZONE 'Asia/Seoul')),
  (26, 12, 8, 'CNC 작업 시 보안경 착용 지도', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + 14)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)))), 'DONE', 'A-G-12-2026', 'PPE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + 3)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)))) + TIME '15:28') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + TIME '13:47') AT TIME ZONE 'Asia/Seoul')),
  (27, 42, 8, '도장 부스 앞 방독마스크 비치, 착용 지도', '생산반장 김철수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + 14)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)))), 'DONE', 'E-G-19-2026', 'PPE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + 5)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)))) + TIME '11:41') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9)) + TIME '13:48') AT TIME ZONE 'Asia/Seoul')),
  (28, 5, 9, '금형 교체 시 안전블록 사용, 교체 절차 게시', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)) + 21)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)))), 'DONE', 'B-M-36-2026', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)) + 16)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)))) + TIME '16:44') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8)) + TIME '11:04') AT TIME ZONE 'Asia/Seoul')),
  (29, 21, 10, '용접 작업 시 보안면, 용접용 장갑 착용 지도', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + 7)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)))), 'DONE', 'H-73-2015', 'PPE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + 4)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)))) + TIME '10:47') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + TIME '13:32') AT TIME ZONE 'Asia/Seoul')),
  (30, 22, 10, '용접 2번 부스 소화기 비치, 위치 표지 부착', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + 14)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)))), 'DONE', NULL, 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + 19)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)))) + TIME '09:31') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12)) + TIME '13:16') AT TIME ZONE 'Asia/Seoul')),
  (31, 36, 7, '저장소 출입문 정전기 제거판 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60))) + 30)), pg_temp.saife_next(pg_temp.saife_wd(GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60))))), 'DONE', 'P-34-2012', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60))) + 41)), pg_temp.saife_next(pg_temp.saife_wd(GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60))))) + TIME '13:52') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60))) + TIME '15:03') AT TIME ZONE 'Asia/Seoul')),
  (32, 37, 11, '저장소 출입구 소화기 2대 비치', '안전관리자 홍길동', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + 14)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)))), 'DONE', NULL, 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + 9)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)))) + TIME '16:13') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + TIME '14:03') AT TIME ZONE 'Asia/Seoul')),
  (33, 39, 11, '조색 작업 시 방독마스크 착용 지도, 정화통 교체 기록', '생산반장 김철수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + 14)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)))), 'DONE', 'E-G-19-2026', 'PPE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + 7)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)))) + TIME '15:00') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10)) + TIME '13:57') AT TIME ZONE 'Asia/Seoul')),
  (34, 19, 12, '집진기 배기팬 벨트 덮개 재고정', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)) + 14)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)))), 'DONE', NULL, 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)) + 6)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)))) + TIME '14:13') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7)) + TIME '13:27') AT TIME ZONE 'Asia/Seoul')),
  (35, 10, 13, '금형 보관대 지정, 2단 적치 금지', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + 21)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)))), 'DONE', NULL, 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + 30)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)))) + TIME '15:00') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + TIME '12:05') AT TIME ZONE 'Asia/Seoul')),
  (36, 16, 13, '연삭기 앞 보안경 비치, 착용 지도', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + 7)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)))), 'DONE', 'B-M-14-2025', 'PPE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + 2)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)))) + TIME '11:44') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14)) + TIME '12:01') AT TIME ZONE 'Asia/Seoul')),
  (37, 20, 14, '용접 반경 안 유기용제, 걸레 반입 금지 표지와 정리', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9)) + 14)), pg_temp.saife_next(pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9)))), 'DONE', 'A-G-14-2026', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 2)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))) + TIME '09:45') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9)) + TIME '12:59') AT TIME ZONE 'Asia/Seoul')),
  (38, 33, 19, '이동식 비계 사용 전 바퀴 브레이크, 안전난간 점검표 운영', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 198)) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 198)))), 'DONE', 'D-C-7-2026', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 198)) + 10)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 198)))) + TIME '16:25') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - 198)) + TIME '12:41') AT TIME ZONE 'Asia/Seoul')),
  (39, 34, 19, '말비계 사용 시 양측 보조부재 설치 확인', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 198)) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 198)))), 'DONE', 'D-C-7-2026', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 198)) + 17)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 198)))) + TIME '15:02') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - 198)) + TIME '12:57') AT TIME ZONE 'Asia/Seoul')),
  (40, 41, 16, '지붕 채광창 덮개와 추락방호망 설치', '설비팀장 박민수', pg_temp.saife_wd((CURRENT_DATE - 45)), 'OVERDUE', 'C-59-2022', 'ENGINEERING', NULL, ((pg_temp.saife_wd(((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8)) + TIME '10:46') AT TIME ZONE 'Asia/Seoul')),
  (41, 37, 2, '저장소 소화기 교체, 월 1회 압력 점검', '안전관리자 홍길동', pg_temp.saife_wd((CURRENT_DATE - 12)), 'OVERDUE', NULL, 'ADMINISTRATIVE', NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '11:29') AT TIME ZONE 'Asia/Seoul')),
  (42, 14, 2, '머시닝센터 주변 통로 구획선 도색, 대차 동선 변경', '가공반장 이영희', pg_temp.saife_wd((CURRENT_DATE + 20)), 'PENDING', 'A-G-2-2025', 'ENGINEERING', NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '11:38') AT TIME ZONE 'Asia/Seoul')),
  (43, 18, 2, '집진기 분진 청소 주기 수립, 청소 기록', '설비팀장 박민수', pg_temp.saife_wd((CURRENT_DATE + 28)), 'PENDING', 'D-43-2012', 'ADMINISTRATIVE', NULL, ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '11:23') AT TIME ZONE 'Asia/Seoul')),
  (44, 28, 2, '천장크레인 훅 해지장치 교체', '설비팀장 박민수', pg_temp.saife_wd((CURRENT_DATE - 28)), 'DONE', 'B-M-34-2026', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 20)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 20)))) + TIME '15:34') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - INTERVAL '1 months')::date) + TIME '11:25') AT TIME ZONE 'Asia/Seoul')),
  (45, 13, 18, '도어 열림 시 정지 기능 작동 확인, 작업 표준 게시', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 148)) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 148)))), 'DONE', NULL, 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 148)) + 9)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 148)))) + TIME '10:03') AT TIME ZONE 'Asia/Seoul'), ((pg_temp.saife_wd((CURRENT_DATE - 148)) + TIME '11:35') AT TIME ZONE 'Asia/Seoul')),
  (46, 9, 20, '시운전 중 소재 위치 조정은 수공구 사용, 양수조작 버튼 위치 재설정', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 240)) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 240)))), 'DONE', 'B-M-36-2026', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 240)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 240)))) + 9)), pg_temp.saife_next(GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 240)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 240)))))) + TIME '13:57') AT TIME ZONE 'Asia/Seoul'), ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 240)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 240)))) + TIME '13:39') AT TIME ZONE 'Asia/Seoul')),
  (47, 15, 21, '숫돌 교체, 조정편 간격 재조정, 사용 전 1분 시운전', '가공반장 이영희', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 7)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))), 'DONE', 'B-M-14-2025', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))) + 2)), pg_temp.saife_next(GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))))) + TIME '13:07') AT TIME ZONE 'Asia/Seoul'), ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))) + TIME '12:13') AT TIME ZONE 'Asia/Seoul')),
  (48, 16, 21, '연삭기 투명 비산 방지판 설치', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))), 'DONE', 'B-M-14-2025', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))) + 21)), pg_temp.saife_next(GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))))) + TIME '15:32') AT TIME ZONE 'Asia/Seoul'), ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 115)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 115)))) + TIME '15:10') AT TIME ZONE 'Asia/Seoul')),
  (49, 29, 22, '지게차 후진 경보기, 후방 카메라 수리', '설비팀장 박민수', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 7)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))), 'DONE', 'B-M-11-2025', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))) + 3)), pg_temp.saife_next(GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))))) + TIME '10:18') AT TIME ZONE 'Asia/Seoul'), ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))) + TIME '12:03') AT TIME ZONE 'Asia/Seoul')),
  (50, 29, 22, '창고동 출입구 보행 통로 구획선 도색', '창고반장 최동훈', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))), 'DONE', 'B-M-11-2025', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))) + 17)), pg_temp.saife_next(GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))))) + TIME '10:13') AT TIME ZONE 'Asia/Seoul'), ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 60)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 60)))) + TIME '10:05') AT TIME ZONE 'Asia/Seoul')),
  (51, 20, 23, '용접 작업 시 화재감시자 배치, 방화포 사용', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 7)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))), 'DONE', 'A-G-14-2026', 'ADMINISTRATIVE', ((GREATEST(pg_temp.saife_wd((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))) + 4)), pg_temp.saife_next(GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))))) + TIME '14:48') AT TIME ZONE 'Asia/Seoul'), ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))) + TIME '14:40') AT TIME ZONE 'Asia/Seoul')),
  (52, 20, 23, '용접구역 유기용제 걸레 전용 밀폐 용기 비치', '용접반장 정민준', GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 14)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))), 'DONE', 'A-G-14-2026', 'ENGINEERING', ((GREATEST(pg_temp.saife_wd((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))) + 11)), pg_temp.saife_next(GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))))) + TIME '16:20') AT TIME ZONE 'Asia/Seoul'), ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 150)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 150)))) + TIME '16:08') AT TIME ZONE 'Asia/Seoul')),
  (53, 28, 24, '줄걸이 용구 사용 전 점검표 도입, 인양 전 해지장치 확인', '용접반장 정민준', pg_temp.saife_wd((CURRENT_DATE + 3)), 'PENDING', 'B-M-12-2025', 'ADMINISTRATIVE', NULL, ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 20)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 20)))) + TIME '12:37') AT TIME ZONE 'Asia/Seoul')),
  (54, 25, 24, '인양 작업 신호수 위치 지정, 인양물 주변 출입 금지 교육', '안전관리자 홍길동', pg_temp.saife_wd((CURRENT_DATE + 25)), 'PENDING', 'B-M-34-2026', 'ADMINISTRATIVE', NULL, ((GREATEST(pg_temp.saife_wd((pg_temp.saife_wd((CURRENT_DATE - 20)) + 1)), pg_temp.saife_next(pg_temp.saife_wd((CURRENT_DATE - 20)))) + TIME '10:00') AT TIME ZONE 'Asia/Seoul'));
SELECT setval('action_id_seq', 54, true);

DROP FUNCTION pg_temp.saife_prev(date);
DROP FUNCTION pg_temp.saife_next(date);
DROP FUNCTION pg_temp.saife_wd(date);

