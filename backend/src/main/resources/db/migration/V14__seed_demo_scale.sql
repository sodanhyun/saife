-- =====================================================================
-- 데모 규모 시드 (1/2): 사업장 이름, 공정, 설비, 위험요인, 평가, 감소대책
--
-- 시연 영상은 모든 사용 사례를 실제 규모로 보여줘야 한다. 설비 6대로는 홈, 설비 이력, 오늘 할 일이
-- 비어 보인다. 그래서 소규모 제조 사업장 1년치 기록을 심는다(공정 7, 설비 24, 위험요인 42,
-- 평가 24, 감소대책 54, 작업 전 점검 42, 사고 6).
--
-- 이름은 전부 샘플이다: (주)샘플정밀 데모공장, 홍길동(안전관리자), 김철수, 이영희, 박민수, 최동훈 등.
-- 재해자는 성만 남긴 표기(최OO)다.
--
-- 지키는 것:
--  - 이동식 사다리 A(id 1)는 라이브 시연 무대다. 위험요인 1(작업발판 미확보, 1개월 전 순회점검 상)과
--    기한 경과 조치 1(이동식 비계 사용)만 미이행으로 남기고, 사고와 오늘 이후 작업 전 점검을 두지 않는다
--  - 고소작업대(id 6)의 V10 이야기(순회점검 상, 조건부 승인, 떨어짐 사고, 수시평가, 재평가 중)는 그대로다
--  - 날짜는 전부 CURRENT_DATE 기준 상대값이다(V10과 같은 방식). 월 순회점검만 달 기준(k달 전 n일)이다.
--    이번 달 순회점검과 올해 정기평가는 일부러 비워 둔다. 오늘 할 일에 월 순회점검, 정기평가 항목이 뜬다
--  - created_at도 사건 순서대로 넣는다. 사고 전 기록 소환(EquipmentHistoryRecaller)은 created_at으로
--    "사고 등록 시점에 알고 있던 것"을 자른다
--  - 판정 근거(rule_trace)는 RiskRuleEngine과 같은 평문 형식이다. 화살표, 따옴표 등급, 대시, 가운뎃점 없음
--
-- 이 파일은 scratchpad의 생성기(gen.py)로 만들었다. 손으로 고칠 때는 오늘 할 일 불변식
-- (TodaySeedSnapshotTest)과 docs/experiments/reset_demo_data.py의 경계값을 같이 본다.
-- =====================================================================

-- ── 사업장, 사람 ──────────────────────────────────────────────────────
UPDATE site SET name = '(주)샘플정밀 데모공장', address = 'OO시 OO구 (샘플 주소)', worker_count = 38 WHERE id = 1;
UPDATE action SET owner = '안전관리자 홍길동' WHERE owner = '안전관리자 이정훈';
UPDATE action SET owner = '설비팀장 박민수' WHERE owner = '설비팀장 박성민';
UPDATE work_plan SET approved_by = '홍길동' WHERE approved_by IN ('이정훈', '관리부 박OO');
UPDATE assessment SET inspector = '홍길동' WHERE inspector = '이정훈' OR (inspector IS NULL AND status = 'CONFIRMED');
UPDATE assessment SET participants = '김철수, 최동훈', inspector = '홍길동' WHERE id = 1;
UPDATE assessment SET participants = '김철수, 이영희', inspector = '홍길동' WHERE id = 2;
UPDATE assessment SET participants = '최동훈, 김철수, 이영희, 박민수', inspector = '홍길동' WHERE id = 3;
UPDATE assessment SET participants = '박민수, 최동훈', inspector = '홍길동' WHERE id = 4;
UPDATE assessment SET participants = '박민수, 최동훈', inspector = '홍길동' WHERE id = 5;
UPDATE assessment SET participants = '박민수, 김철수', inspector = '홍길동' WHERE id = 6;

-- ── 기존 시드(V2, V10) 날짜를 이 마이그레이션의 기준일로 다시 고정한다 ─────────
-- 새 볼륨에서는 같은 값이고, V2/V10이 다른 날 적용된 개발 DB에서는 이야기 전체를 오늘 기준으로 맞춘다
UPDATE assessment SET assessed_on = (CURRENT_DATE - INTERVAL '3 months')::date, created_at = (((CURRENT_DATE - INTERVAL '3 months')::date + TIME '11:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 1;
UPDATE assessment SET assessed_on = (CURRENT_DATE - INTERVAL '1 months')::date, created_at = (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '11:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 2;
UPDATE assessment SET assessed_on = (CURRENT_DATE - INTERVAL '13 months')::date, created_at = (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '11:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 3;
UPDATE assessment SET assessed_on = (CURRENT_DATE - 75), created_at = (((CURRENT_DATE - 75) + TIME '11:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 4;
UPDATE assessment SET assessed_on = (CURRENT_DATE - 45), created_at = (((CURRENT_DATE - 45) + TIME '11:32') AT TIME ZONE 'Asia/Seoul') WHERE id = 5;
UPDATE assessment SET assessed_on = LEAST(CURRENT_DATE - 10, date_trunc('month', CURRENT_DATE)::date - 1), created_at = ((LEAST(CURRENT_DATE - 10, date_trunc('month', CURRENT_DATE)::date - 1) + TIME '11:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 6;
UPDATE hazard SET created_at = (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '09:30') AT TIME ZONE 'Asia/Seoul') WHERE id = 1;
UPDATE hazard SET created_at = (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '09:35') AT TIME ZONE 'Asia/Seoul') WHERE id = 2;
UPDATE hazard SET created_at = (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '09:40') AT TIME ZONE 'Asia/Seoul') WHERE id = 3;
UPDATE hazard SET created_at = (((CURRENT_DATE - INTERVAL '3 months')::date + TIME '09:30') AT TIME ZONE 'Asia/Seoul') WHERE id = 4;
UPDATE hazard SET created_at = ((((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8) + TIME '09:30') AT TIME ZONE 'Asia/Seoul') WHERE id = 5;
UPDATE hazard SET created_at = (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '09:30') AT TIME ZONE 'Asia/Seoul') WHERE id = 6;
UPDATE hazard SET created_at = (((CURRENT_DATE - 75) + TIME '09:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 7;
UPDATE hazard SET created_at = (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '09:40') AT TIME ZONE 'Asia/Seoul') WHERE id = 8;
UPDATE action SET due_date = (CURRENT_DATE - INTERVAL '1 months')::date, completed_at = NULL, created_at = (((CURRENT_DATE - INTERVAL '3 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 1;
UPDATE action SET due_date = (CURRENT_DATE - INTERVAL '2 months')::date, completed_at = ((((CURRENT_DATE - INTERVAL '2 months')::date - 1) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), created_at = (((CURRENT_DATE - INTERVAL '3 months')::date + TIME '15:10') AT TIME ZONE 'Asia/Seoul') WHERE id = 2;
UPDATE action SET due_date = (CURRENT_DATE - INTERVAL '2 months')::date, completed_at = ((((CURRENT_DATE - INTERVAL '2 months')::date - 1) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), created_at = (((CURRENT_DATE - INTERVAL '3 months')::date + TIME '15:20') AT TIME ZONE 'Asia/Seoul') WHERE id = 3;
UPDATE action SET due_date = (CURRENT_DATE + 14), completed_at = NULL, created_at = (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '15:40') AT TIME ZONE 'Asia/Seoul') WHERE id = 4;
UPDATE action SET due_date = (CURRENT_DATE + 7), completed_at = NULL, created_at = (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '15:50') AT TIME ZONE 'Asia/Seoul') WHERE id = 5;
UPDATE action SET due_date = (CURRENT_DATE - 73), completed_at = (((CURRENT_DATE - 70) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), created_at = (((CURRENT_DATE - 75) + TIME '15:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 6;
UPDATE action SET due_date = (CURRENT_DATE - 22), completed_at = (((CURRENT_DATE - 20) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), created_at = (((CURRENT_DATE - 24) + TIME '10:00') AT TIME ZONE 'Asia/Seoul') WHERE id = 7;
UPDATE action SET priority = 'ADMINISTRATIVE' WHERE id = 6;
UPDATE action SET content = '컨베이어 구동부 방호덮개 제작, 설치' WHERE id = 3;
UPDATE work_plan SET work_date = (CURRENT_DATE - 50), briefing_ack_at = (((CURRENT_DATE - 50) + TIME '08:10') AT TIME ZONE 'Asia/Seoul'), approved_at = (((CURRENT_DATE - 51) + TIME '16:40') AT TIME ZONE 'Asia/Seoul'), created_at = (((CURRENT_DATE - 51) + TIME '14:20') AT TIME ZONE 'Asia/Seoul'), approved_by = '홍길동' WHERE id = 1;
UPDATE work_plan SET briefing = replace(briefing, '작업: 2층 조립구역 조명 교체 / 공장동 2층 조립구역', '작업: 2층 조립구역 조명 교체 / 공장동 2층 조립구역 / ' || (CURRENT_DATE - 50)::text) WHERE id = 1;
UPDATE incident SET occurred_at = (((CURRENT_DATE - 45) + TIME '10:20') AT TIME ZONE 'Asia/Seoul'), created_at = (((CURRENT_DATE - 45) + TIME '11:30') AT TIME ZONE 'Asia/Seoul'), report_due_date = ((CURRENT_DATE - 45) + INTERVAL '1 month')::date WHERE id = 1;
UPDATE near_miss SET reported_at = (((CURRENT_DATE - 130) + TIME '17:20') AT TIME ZONE 'Asia/Seoul'), reviewed = true WHERE equipment_id = 5;
UPDATE near_miss SET reported_at = ((((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 3) + TIME '16:10') AT TIME ZONE 'Asia/Seoul'), reviewed = true WHERE equipment_id = 4;

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
  (9, 1, 2, '머시닝센터 1호', '머시닝센터1호', '공장동 1층 가공구역', 'M-1032', (CURRENT_DATE - 150)),
  (10, 1, 2, '탁상 연삭기', '탁상연삭기', '공장동 1층 가공구역', 'M-0305', DATE '2018-08-22'),
  (11, 1, 2, '공기압축기 1호', '공기압축기1호', '공장동 1층 가공구역', 'M-0350', DATE '2017-04-01'),
  (12, 1, 2, '집진기 1호', '집진기1호', '공장동 1층 가공구역', 'M-0377', DATE '2019-10-15'),
  (13, 1, 5, 'CO2 용접기 1호', 'co2용접기1호', '공장동 1층 제관구역', 'M-0612', DATE '2020-02-03'),
  (14, 1, 5, 'CO2 용접기 2호', 'co2용접기2호', '공장동 1층 제관구역', 'M-0613', DATE '2022-07-11'),
  (15, 1, 5, '가스 용기 보관대', '가스용기보관대', '공장동 1층 제관구역', 'M-0640', DATE '2020-02-03'),
  (16, 1, 5, '천장크레인 1호', '천장크레인1호', '공장동 1층 제관구역', 'M-0702', DATE '2018-01-20'),
  (17, 1, 3, '전동 지게차 2호', '전동지게차2호', '창고동 1층', 'M-0921', DATE '2024-06-12'),
  (18, 1, 3, '적재 랙 A열', '적재랙a열', '창고동 1층', 'M-0950', DATE '2019-05-02'),
  (19, 1, 4, '이동식 비계 1호', '이동식비계1호', '공장동 2층 조립구역', 'M-1120', (CURRENT_DATE - 200)),
  (20, 1, 4, '말비계 1호', '말비계1호', '공장동 2층 조립구역', 'M-1121', (CURRENT_DATE - 200)),
  (21, 1, 4, '조립 컨베이어 1호', '조립컨베이어1호', '공장동 2층 조립구역', 'M-0420', DATE '2021-09-01'),
  (22, 1, 6, '유기용제 저장소', '유기용제저장소', '공장동 후면 조색실', 'M-0805', DATE '2019-03-01'),
  (23, 1, 6, '페인트 혼합기', '페인트혼합기', '공장동 후면 조색실', 'M-0812', DATE '2021-02-15'),
  (24, 1, 7, '지붕 작업 구역', '지붕작업구역', '공장동 옥상', NULL, DATE '2015-03-01');
SELECT setval('equipment_id_seq', 24, true);
INSERT INTO equipment_change (id, equipment_id, change_type, description, occurred_on) VALUES
  (1, 7, 'MODIFIED', '광전자식 방호장치 설치', ((CURRENT_DATE - INTERVAL '13 months')::date + 60)),
  (2, 19, 'INTRODUCED', '이동식 비계(안전난간 일체형) 신규 도입', (CURRENT_DATE - 200)),
  (3, 20, 'INTRODUCED', '말비계 신규 도입', (CURRENT_DATE - 200)),
  (4, 9, 'INTRODUCED', '머시닝센터 신규 도입, 가공구역 배치 변경', (CURRENT_DATE - 150)),
  (5, 16, 'MODIFIED', '훅 해지장치 교체', (CURRENT_DATE - 19));
SELECT setval('equipment_change_id_seq', 5, true);

-- ── 위험요인 ───────────────────────────────────────────────────────
INSERT INTO hazard (id, site_id, equipment_id, process_id, accident_type, missing_control, description, source, ai_suggested, ai_adopted, created_at) VALUES
  (9, 1, 7, 2, 'CAUGHT', '광전자식 방호장치 미설치', '금형 사이 손이 들어가는 구간에 방호장치 없이 양수조작식만 사용', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (10, 1, 7, 2, 'DROP', '금형 적치 불량', '교체용 금형을 작업대 끝단에 2단으로 적치', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (11, 1, 8, 2, 'CAUGHT', '척 방호덮개 개방 사용', '척 방호덮개를 연 채 소재를 교체하고 바로 운전', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (12, 1, 8, 2, 'PPE', '보안경 미착용', 'CNC 선반 절삭 중 작업자 보안경 미착용', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (13, 1, 9, 2, 'CAUGHT', '도어 열림 정지 확인 미흡', '신규 머시닝센터 도어를 연 상태의 정지 기능을 확인한 기록 없음', 'MANUAL', false, NULL, (((CURRENT_DATE - 148) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (14, 1, 9, 2, 'STRUCK', '설비 주변 통로 폭 미확보', '머시닝센터 배치 후 설비와 기둥 사이 통로가 좁아 대차 통행과 겹침', 'MANUAL', false, NULL, (((CURRENT_DATE - 148) + TIME '10:05') AT TIME ZONE 'Asia/Seoul')),
  (15, 1, 10, 2, 'DROP', '숫돌 덮개 조정편 간격 과다', '연삭숫돌과 덮개 조정편 사이가 벌어져 파편이 앞으로 튈 수 있음', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (16, 1, 10, 2, 'PPE', '보안경 미착용', '탁상 연삭기 공구 연마 중 보안경 미착용', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + TIME '10:20') AT TIME ZONE 'Asia/Seoul')),
  (17, 1, 11, 2, 'CAUGHT', 'V벨트 풀리 방호덮개 미설치', '공기압축기 V벨트와 풀리가 노출된 상태', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (18, 1, 12, 2, 'FIRE', '분진 퇴적', '집진기 하부 호퍼와 덕트에 금속 분진이 쌓여 있음', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (19, 1, 12, 2, 'CAUGHT', '배기팬 벨트 덮개 일부 탈락', '집진기 배기팬 벨트 덮개 고정 볼트 2개 탈락', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7) + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (20, 1, 13, 5, 'FIRE', '불티 비산 방지 미흡', '용접 작업 반경 안에 걸레와 종이 상자가 놓여 있음', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (21, 1, 13, 5, 'PPE', '용접용 보안면 미착용', '용접 중 보안면 대신 일반 보안경만 착용', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (22, 1, 14, 5, 'FIRE', '소화기 미비치', '용접 2번 부스 주변에 소화기와 위치 표시 없음', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (23, 1, 15, 5, 'DROP', '용기 전도 방지 미흡', '산소, LPG 용기 고정 체인을 걸지 않고 세워 둠', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (24, 1, 16, 5, 'DROP', '줄걸이 용구 손상', '슬링 벨트 가장자리 절상, 폐기 기준과 점검 기록 없음', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (25, 1, 16, 5, 'DROP', '인양물 하부 출입 통제 미흡', '인양 중 인양물 아래로 작업자가 지나다님', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:05') AT TIME ZONE 'Asia/Seoul')),
  (26, 1, 5, 3, 'PPE', '좌석 안전띠 미착용', '지게차 운전원 좌석 안전띠 미착용', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (27, 1, 18, 3, 'PPE', '안전모 미착용', '랙 앞 작업자 안전모 미착용 후보였으나, 확인 결과 안전모 착용 상태이고 그림자를 잘못 읽은 것이라 담당자 제외', 'PHOTO', true, false, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + TIME '10:20') AT TIME ZONE 'Asia/Seoul')),
  (28, 1, 16, 5, 'DROP', '훅 해지장치 파손', '천장크레인 훅 해지장치 스프링이 부러져 열린 상태', 'PHOTO', true, true, (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (29, 1, 17, 3, 'STRUCK', '통로 미확보, 통로 표시 없음', '창고동 출입구 보행 통로가 구획되지 않았고 후진 경보음이 작동하지 않음', 'INCIDENT', false, NULL, (((CURRENT_DATE - 60) + TIME '17:42') AT TIME ZONE 'Asia/Seoul')),
  (30, 1, 17, 3, 'DROP', '적재 높이 초과', '팔레트 적재 높이가 운전 시야를 가림', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (31, 1, 18, 3, 'DROP', '상단 적재물 결속 미흡', '랙 최상단 박스 적재물을 결속하지 않고 랙 아래로 통행', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (33, 1, 19, 4, 'FALL', '바퀴 고정, 안전난간 점검 기록 없음', '이동식 비계 사용 전 바퀴 브레이크와 안전난간 점검 기록 없음', 'MANUAL', false, NULL, (((CURRENT_DATE - 198) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (34, 1, 20, 4, 'FALL', '보조부재 미설치', '말비계 양측 보조부재를 걸지 않고 사용', 'MANUAL', false, NULL, (((CURRENT_DATE - 198) + TIME '10:05') AT TIME ZONE 'Asia/Seoul')),
  (35, 1, 21, 4, 'CAUGHT', '테일 풀리 방호덮개 미설치', '조립 컨베이어 테일 풀리가 노출된 상태', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (36, 1, 22, 6, 'FIRE', '용기 개방 보관', '유기용제 용기 뚜껑을 연 채 보관, 저장소 안에 증기 냄새', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (37, 1, 22, 6, 'FIRE', '소화기 미비치', '저장소 출입구 소화기가 없거나 압력이 떨어져 있음', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (38, 1, 23, 6, 'CAUGHT', '교반 날개 방호덮개 미설치', '페인트 혼합기 교반 날개가 노출된 상태로 운전', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (39, 1, 23, 6, 'PPE', '방독마스크 미착용', '조색 중 유기용제를 다루면서 방독마스크 미착용', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (40, 1, 24, 7, 'FALL', '안전대 부착설비 미설치', '지붕 위 작업 시 안전대를 걸 구명줄이 없음', 'MANUAL', false, NULL, (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (41, 1, 24, 7, 'FALL', '채광창 덮개 미설치', '지붕 채광창(선라이트) 주변에 덮개와 안전방망이 없음', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (42, 1, 2, 1, 'PPE', '방독마스크 미착용', '도장 부스 앞 희석 작업 중 방독마스크 미착용', 'PHOTO', true, true, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + TIME '10:10') AT TIME ZONE 'Asia/Seoul'));
SELECT setval('hazard_id_seq', 42, true);

-- ── 평가 ───────────────────────────────────────────────────────────
INSERT INTO assessment (id, site_id, kind, trigger_type, trigger_ref_id, assessed_on, participants, status, inspector, created_at) VALUES
  (7, 1, 'REGULAR', 'SCHEDULE', NULL, GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60)), '최동훈, 김철수, 이영희, 박민수, 정민준', 'CONFIRMED', '홍길동', ((GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60)) + TIME '16:00') AT TIME ZONE 'Asia/Seoul')),
  (8, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9), '김철수, 이영희', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (9, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8), '최동훈, 오지훈', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (10, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12), '박민수, 정민준', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (11, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10), '김철수, 윤서연', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (12, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7), '박민수, 오지훈', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (13, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14), '이영희, 장우진', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (14, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9), '김철수, 정민준', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (15, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '5 months')::date + 11), '박민수, 이영희', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '5 months')::date + 11) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (16, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8), '최동훈, 김철수, 윤서연', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (17, 1, 'ROUTINE', 'PATROL', NULL, ((date_trunc('month', CURRENT_DATE) - INTERVAL '2 months')::date + 13), '김철수, 이영희, 오지훈', 'CONFIRMED', '홍길동', ((((date_trunc('month', CURRENT_DATE) - INTERVAL '2 months')::date + 13) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (18, 1, 'OCCASIONAL', 'EQUIPMENT_CHANGE', 4, (CURRENT_DATE - 148), '이영희, 박민수', 'CONFIRMED', '홍길동', (((CURRENT_DATE - 148) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (19, 1, 'OCCASIONAL', 'EQUIPMENT_CHANGE', 2, (CURRENT_DATE - 198), '김철수, 박민수', 'CONFIRMED', '홍길동', (((CURRENT_DATE - 198) + TIME '11:00') AT TIME ZONE 'Asia/Seoul')),
  (20, 1, 'OCCASIONAL', 'INCIDENT', 2, (CURRENT_DATE - 240), '이영희, 최동훈', 'CONFIRMED', '홍길동', (((CURRENT_DATE - 240) + TIME '11:42') AT TIME ZONE 'Asia/Seoul')),
  (21, 1, 'OCCASIONAL', 'INCIDENT', 3, (CURRENT_DATE - 115), '이영희, 박민수', 'CONFIRMED', '홍길동', (((CURRENT_DATE - 115) + TIME '15:12') AT TIME ZONE 'Asia/Seoul')),
  (22, 1, 'OCCASIONAL', 'INCIDENT', 4, (CURRENT_DATE - 60), '오지훈, 최동훈', 'CONFIRMED', '홍길동', (((CURRENT_DATE - 60) + TIME '17:32') AT TIME ZONE 'Asia/Seoul')),
  (23, 1, 'OCCASIONAL', 'INCIDENT', 5, (CURRENT_DATE - 150), '정민준, 최동훈, 박민수', 'CONFIRMED', '홍길동', (((CURRENT_DATE - 150) + TIME '16:22') AT TIME ZONE 'Asia/Seoul')),
  (24, 1, 'OCCASIONAL', 'INCIDENT', 6, (CURRENT_DATE - 20), NULL, 'DRAFT', NULL, (((CURRENT_DATE - 20) + TIME '10:52') AT TIME ZONE 'Asia/Seoul'));
SELECT setval('assessment_id_seq', 24, true);

INSERT INTO assessment_hazard (assessment_id, hazard_id, frequency, severity, risk_level, rule_trace, acceptable) VALUES
  (3, 1, 2, 2, 'MEDIUM', '발판 높이 미확인, 디딤대 위치와 넘어짐 방지 확인 필요 (제42조④)', NULL),
  (3, 2, 2, 2, 'MEDIUM', '안전모 미착용 확인 (제32조)', NULL),
  (3, 9, 3, 3, 'HIGH', '금형 사이 손 투입 구간 방호장치 없음, 양수조작식만 사용', NULL),
  (3, 11, 2, 2, 'MEDIUM', '척 방호덮개 개방 사용, 회전부 접촉 가능 (제87조)', NULL),
  (3, 15, 2, 2, 'MEDIUM', '숫돌 덮개 조정편 간격 과다, 파편 비산 가능', NULL),
  (3, 17, 3, 3, 'HIGH', '회전, 구동부 방호덮개 없음 (제87조)', NULL),
  (3, 18, 2, 2, 'MEDIUM', '분진 퇴적, 점화원 관리 필요 (제232조)', NULL),
  (3, 20, 2, 2, 'MEDIUM', '용접 불티 비산 반경 안에 가연물 (제241조)', NULL),
  (3, 23, 2, 2, 'MEDIUM', '용기 고정 체인 미체결, 넘어지면 밸브 파손 위험', NULL),
  (3, 24, 3, 3, 'HIGH', '줄걸이 용구(슬링 벨트) 손상, 폐기 기준 없음', NULL),
  (3, 25, 2, 2, 'MEDIUM', '인양물 아래 작업자 출입 (제146조)', NULL),
  (3, 26, 2, 2, 'MEDIUM', '운전원 좌석 안전띠 미착용', NULL),
  (3, 30, 2, 2, 'MEDIUM', '팔레트 적재 높이 초과, 화물 낙하 가능 (제393조)', NULL),
  (3, 31, 3, 3, 'HIGH', '상단 적재물 결속 없음, 랙 아래 통행 (제393조)', NULL),
  (3, 35, 3, 3, 'HIGH', '회전, 구동부 방호덮개 없음 (제87조)', NULL),
  (3, 36, 3, 3, 'HIGH', '인화성 증기 체류, 용기 개방 보관 (제232조)', NULL),
  (3, 38, 3, 3, 'HIGH', '회전, 구동부 방호덮개 없음 (제87조)', NULL),
  (3, 40, 3, 3, 'HIGH', '작업 높이 6m (2m 이상), 안전대 부착설비 없음 (제44조)', NULL),
  (1, 12, 1, 2, 'LOW', '보안경 착용 확인 (제32조)', NULL),
  (2, 4, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (2, 14, 2, 2, 'MEDIUM', '설비와 기둥 사이 통로 폭 부족, 대차 동선과 겹침 (제22조)', NULL),
  (2, 18, 2, 2, 'MEDIUM', '호퍼, 덕트 분진 재퇴적, 청소 주기 없음 (제232조)', NULL),
  (2, 28, 3, 3, 'HIGH', '훅 해지장치 파손, 인양 중 줄걸이 이탈 가능 (제146조)', NULL),
  (2, 29, 1, 2, 'LOW', '보행 통로 구획, 후진 경보 작동 확인 (제22조)', NULL),
  (2, 30, 2, 2, 'MEDIUM', '팔레트 적재 높이 제한 표지 (제393조)', true),
  (2, 37, 3, 3, 'HIGH', '저장소 출입구 소화기 압력 미달, 예비 소화기 없음 (제243조)', NULL),
  (6, 11, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (6, 35, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (7, 2, 2, 2, 'MEDIUM', '안전모 미착용 확인 (제32조)', NULL),
  (7, 3, 2, 2, 'MEDIUM', '인화성 증기, 점화원 관리, 실내면 환기와 방독마스크 (제232조, 제450조)', NULL),
  (7, 5, 1, 2, 'LOW', '안전블록 사용, 정비 시 운전정지 (제92조)', NULL),
  (7, 9, 1, 2, 'LOW', '광전자식 방호장치 설치, 정비 시 운전정지 (제92조)', NULL),
  (7, 11, 1, 2, 'LOW', '척 방호덮개 닫고 운전, 정비 시 운전정지 (제92조)', NULL),
  (7, 17, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (7, 20, 1, 2, 'LOW', '가연물 정리, 불티 비산 방지포 비치 (제241조)', NULL),
  (7, 23, 1, 2, 'LOW', '용기 고정 체인 체결, 충전 용기와 빈 용기 구분 보관', NULL),
  (7, 24, 2, 2, 'MEDIUM', '줄걸이 용구 월 1회 점검 운영, 손상 시 즉시 폐기', NULL),
  (7, 31, 1, 2, 'LOW', '적재물 결속, 상단 적재 제한 표지 (제393조)', NULL),
  (7, 35, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (7, 36, 2, 2, 'MEDIUM', '용기 밀폐 보관, 국소배기 설치, 점화원 관리 (제232조)', NULL),
  (7, 38, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (7, 40, 2, 2, 'MEDIUM', '작업 높이 6m (2m 이상), 안전대 착용 (제32조, 제44조)', NULL),
  (8, 9, 3, 3, 'HIGH', '광전자식 방호장치 설치 전, 양수조작식만 사용', NULL),
  (8, 12, 2, 2, 'MEDIUM', '보안경 미착용 확인 (제32조)', NULL),
  (8, 42, 2, 2, 'MEDIUM', '유기용제 희석, 방독마스크 착용 필요 (제450조)', NULL),
  (9, 5, 3, 3, 'HIGH', '금형 교체 시 안전블록 미사용', NULL),
  (9, 31, 1, 2, 'LOW', '적재물 결속, 상단 적재 제한 표지 (제393조)', NULL),
  (10, 21, 2, 2, 'MEDIUM', '용접 작업, 보안면과 용접용 장갑 착용 (제32조)', NULL),
  (10, 22, 2, 2, 'MEDIUM', '용접구역 소화기 위치 표시 없음 (제243조)', NULL),
  (10, 9, 1, 2, 'LOW', '광전자식 방호장치 설치 완료 (제92조)', NULL),
  (11, 37, 2, 2, 'MEDIUM', '저장소 출입구 소화기 없음 (제243조)', NULL),
  (11, 39, 2, 2, 'MEDIUM', '유기용제 취급, 방독마스크 착용 필요 (제450조)', NULL),
  (11, 27, 2, 2, 'MEDIUM', '안전모 미착용 후보, 확인 결과 착용으로 담당자 제외', NULL),
  (12, 26, 1, 2, 'LOW', '안전띠 착용 확인', NULL),
  (12, 30, 2, 2, 'MEDIUM', '팔레트 적재 높이 제한 표지 (제393조)', NULL),
  (12, 19, 3, 3, 'HIGH', '회전, 구동부 방호덮개 없음 (제87조)', NULL),
  (13, 10, 2, 2, 'MEDIUM', '교체용 금형 작업대 끝단 2단 적치 (제393조)', NULL),
  (13, 16, 2, 2, 'MEDIUM', '보안경 미착용 확인 (제32조)', NULL),
  (14, 42, 1, 2, 'LOW', '방독마스크 착용 확인 (제450조)', NULL),
  (14, 21, 1, 2, 'LOW', '보안면 착용 확인 (제32조)', NULL),
  (14, 20, 2, 2, 'MEDIUM', '용접 불티 반경 안에 유기용제 묻은 걸레 (제241조)', NULL),
  (15, 17, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (15, 38, 1, 2, 'LOW', '방호덮개 설치, 정비 시 운전정지 (제92조)', NULL),
  (15, 31, 1, 2, 'LOW', '적재물 결속 유지 (제393조)', NULL),
  (15, 23, 1, 2, 'LOW', '용기 고정 체인 체결 확인', NULL),
  (16, 41, 3, 3, 'HIGH', '작업 높이 6m (2m 이상), 채광창 덮개 없음 (제42조)', NULL),
  (16, 14, 2, 2, 'MEDIUM', '설비 주변 통로 폭 미확보 (제22조)', NULL),
  (16, 33, 1, 2, 'LOW', '바퀴 고정, 안전난간 점검 확인 (제42조)', NULL),
  (17, 10, 1, 2, 'LOW', '금형 보관대 지정 (제393조)', NULL),
  (17, 22, 1, 2, 'LOW', '소화기 비치, 위치 표지 확인 (제243조)', NULL),
  (17, 39, 1, 2, 'LOW', '방독마스크 착용 확인 (제450조)', NULL),
  (17, 15, 1, 2, 'LOW', '덮개 조정편 간격 조정, 비산 방지판 설치', NULL),
  (17, 16, 1, 2, 'LOW', '보안경 착용 확인 (제32조)', NULL),
  (17, 20, 1, 2, 'LOW', '화재감시자 배치, 방화포 사용 (제241조)', NULL),
  (17, 21, 1, 2, 'LOW', '보안면 착용 확인 (제32조)', NULL),
  (18, 13, 2, 2, 'MEDIUM', '도어 열림 정지 기능 확인 기록 없음, 확인 후 판정', NULL),
  (18, 14, 2, 2, 'MEDIUM', '설비와 기둥 사이 통로 폭 부족 (제22조)', NULL),
  (19, 33, 2, 2, 'MEDIUM', '작업 높이 3.6m (2m 이상), 바퀴 고정과 안전난간 확인 (제42조)', NULL),
  (19, 34, 2, 2, 'MEDIUM', '말비계 높이 2.2m (2m 이상), 보조부재와 작업발판 폭 확인', NULL),
  (20, 9, 2, 2, 'MEDIUM', '끼임 사고 발생, 휴업예상 2일, 사고 전 하', NULL),
  (21, 15, 2, 2, 'MEDIUM', '물체에 맞음 사고 발생, 휴업예상 1일, 사고 전 중', NULL),
  (21, 16, 2, 2, 'MEDIUM', '사고와 다른 발생형태, 종전 등급 유지', NULL),
  (22, 30, 2, 2, 'MEDIUM', '사고와 다른 발생형태, 종전 등급 유지', NULL),
  (22, 29, 2, 2, 'MEDIUM', '부딪힘 사고 발생, 휴업예상 0일, 사고 전 평가 없음', NULL),
  (23, 20, 3, 3, 'HIGH', '화재 사고 발생, 휴업예상 6일, 사고 전 중', NULL),
  (23, 21, 1, 2, 'LOW', '사고와 다른 발생형태, 종전 등급 유지', NULL),
  (24, 28, 3, 3, 'HIGH', '물체에 맞음 사고 발생, 휴업예상 14일, 사고 전 상', NULL),
  (24, 24, 3, 3, 'HIGH', '물체에 맞음 사고 발생, 휴업예상 14일, 사고 전 중', NULL),
  (24, 25, 3, 3, 'HIGH', '물체에 맞음 사고 발생, 휴업예상 14일, 사고 전 중', NULL);

-- ── 감소대책 ───────────────────────────────────────────────────────
INSERT INTO action (id, hazard_id, assessment_id, content, owner, due_date, status, guide_ref, priority, completed_at, created_at) VALUES
  (8, 9, 3, '프레스 1호 광전자식 방호장치 설치', '설비팀장 박민수', ((CURRENT_DATE - INTERVAL '13 months')::date + 45), 'DONE', 'B-M-36-2026', 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 60) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (9, 17, 3, '공기압축기 V벨트 풀리 방호덮개 설치', '설비팀장 박민수', ((CURRENT_DATE - INTERVAL '13 months')::date + 30), 'DONE', NULL, 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 21) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (10, 24, 3, '손상 슬링 벨트 폐기, 줄걸이 용구 월 1회 점검표 운영', '설비팀장 박민수', ((CURRENT_DATE - INTERVAL '13 months')::date + 14), 'DONE', 'B-M-12-2025', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 10) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (11, 31, 3, '랙 상단 적재물 결속, 상단 2단 적재 제한 표지', '물류담당 오지훈', ((CURRENT_DATE - INTERVAL '13 months')::date + 30), 'DONE', 'M-49-2023', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 27) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (12, 35, 3, '조립 컨베이어 테일 풀리 방호덮개 설치', '설비팀장 박민수', ((CURRENT_DATE - INTERVAL '13 months')::date + 30), 'DONE', 'B-M-33-2026', 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 33) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (13, 36, 3, '유기용제 용기 밀폐 보관, 저장소 국소배기장치 설치', '생산팀장 최동훈', ((CURRENT_DATE - INTERVAL '13 months')::date + 60), 'DONE', 'B-E-17-2026', 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 57) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (14, 38, 3, '혼합기 교반부 방호덮개와 덮개 열림 시 정지 장치 설치', '설비팀장 박민수', ((CURRENT_DATE - INTERVAL '13 months')::date + 45), 'DONE', 'B-M-2-2025', 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 66) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (15, 40, 3, '지붕 용마루 구명줄(안전대 부착설비) 설치', '설비팀장 박민수', ((CURRENT_DATE - INTERVAL '13 months')::date + 60), 'DONE', 'C-59-2022', 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 74) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (16, 20, 3, '용접구역 가연물 정리, 불티 비산 방지포 비치', '용접반장 정민준', ((CURRENT_DATE - INTERVAL '13 months')::date + 14), 'DONE', 'A-G-11-2025', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 12) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (17, 1, 3, '사다리 작업 시 2인 1조, 넘어짐 방지 확인', '생산반장 김철수', ((CURRENT_DATE - INTERVAL '13 months')::date + 14), 'DONE', 'A-G-4-2025', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 9) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (18, 26, 3, '지게차 운전원 안전띠 착용 지도', '물류담당 오지훈', ((CURRENT_DATE - INTERVAL '13 months')::date + 14), 'DONE', 'B-M-11-2025', 'PPE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 6) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (19, 23, 3, '가스 용기 전도 방지 체인 체결, 충전 용기와 빈 용기 구분 보관', '용접반장 정민준', ((CURRENT_DATE - INTERVAL '13 months')::date + 21), 'DONE', 'P-139-2013', 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 15) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (20, 15, 3, '연삭기 숫돌 덮개 조정편 간격 3mm 이내로 조정', '가공반장 이영희', ((CURRENT_DATE - INTERVAL '13 months')::date + 14), 'DONE', 'B-M-14-2025', 'ENGINEERING', ((((CURRENT_DATE - INTERVAL '13 months')::date + 8) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (21, 11, 3, 'CNC 선반 척 방호덮개 닫고 운전, 작업 표준 게시', '가공반장 이영희', ((CURRENT_DATE - INTERVAL '13 months')::date + 21), 'DONE', 'M-96-2012', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 20) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (22, 18, 3, '집진기 분진 정기 청소, 점화원 반입 금지 표지', '설비팀장 박민수', ((CURRENT_DATE - INTERVAL '13 months')::date + 30), 'DONE', 'D-43-2012', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 29) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (23, 25, 3, '인양 작업 시 하부 출입 통제선 설치', '용접반장 정민준', ((CURRENT_DATE - INTERVAL '13 months')::date + 21), 'DONE', 'B-M-34-2026', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 26) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (24, 30, 3, '팔레트 적재 높이 1.5m 제한 표지', '물류담당 오지훈', ((CURRENT_DATE - INTERVAL '13 months')::date + 21), 'DONE', 'M-49-2023', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 18) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (25, 3, 3, '도장 부스 배기 팬 가동 확인 후 작업, 부스 안 점화원 반입 금지', '생산반장 김철수', ((CURRENT_DATE - INTERVAL '13 months')::date + 14), 'DONE', 'B-E-17-2026', 'ADMINISTRATIVE', ((((CURRENT_DATE - INTERVAL '13 months')::date + 11) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '13 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (26, 12, 8, 'CNC 작업 시 보안경 착용 지도', '가공반장 이영희', (((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + 14), 'DONE', NULL, 'PPE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + 3) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (27, 42, 8, '도장 부스 앞 방독마스크 비치, 착용 지도', '생산반장 김철수', (((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + 14), 'DONE', NULL, 'PPE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + 5) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '12 months')::date + 9) + TIME '15:10') AT TIME ZONE 'Asia/Seoul')),
  (28, 5, 9, '금형 교체 시 안전블록 사용, 교체 절차 게시', '가공반장 이영희', (((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8) + 21), 'DONE', 'B-M-36-2026', 'ENGINEERING', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8) + 16) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '11 months')::date + 8) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (29, 21, 10, '용접 작업 시 보안면, 용접용 장갑 착용 지도', '용접반장 정민준', (((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + 7), 'DONE', 'H-73-2015', 'PPE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + 4) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (30, 22, 10, '용접 2번 부스 소화기 비치, 위치 표지 부착', '용접반장 정민준', (((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + 14), 'DONE', NULL, 'ADMINISTRATIVE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + 19) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '10 months')::date + 12) + TIME '15:10') AT TIME ZONE 'Asia/Seoul')),
  (31, 36, 7, '저장소 출입문 정전기 제거판 설치', '생산팀장 최동훈', (GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60)) + 30), 'DONE', 'B-E-17-2026', 'ENGINEERING', (((GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60)) + 41) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((GREATEST((CURRENT_DATE - INTERVAL '11 months')::date, LEAST(date_trunc('year', CURRENT_DATE)::date - 24, CURRENT_DATE - 60)) + TIME '17:00') AT TIME ZONE 'Asia/Seoul')),
  (32, 37, 11, '저장소 출입구 소화기 2대 비치', '생산팀장 최동훈', (((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + 14), 'DONE', NULL, 'ADMINISTRATIVE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + 9) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (33, 39, 11, '조색 작업 시 방독마스크 착용 지도, 정화통 교체 기록', '생산반장 김철수', (((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + 14), 'DONE', NULL, 'PPE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + 7) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '9 months')::date + 10) + TIME '15:10') AT TIME ZONE 'Asia/Seoul')),
  (34, 19, 12, '집진기 배기팬 벨트 덮개 재고정', '설비팀장 박민수', (((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7) + 14), 'DONE', NULL, 'ENGINEERING', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7) + 6) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '8 months')::date + 7) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (35, 10, 13, '금형 보관대 지정, 2단 적치 금지', '가공반장 이영희', (((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + 21), 'DONE', NULL, 'ADMINISTRATIVE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + 30) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (36, 16, 13, '연삭기 앞 보안경 비치, 착용 지도', '가공반장 이영희', (((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + 7), 'DONE', 'B-M-14-2025', 'PPE', (((((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + 2) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '7 months')::date + 14) + TIME '15:10') AT TIME ZONE 'Asia/Seoul')),
  (37, 20, 14, '용접 반경 안 유기용제, 걸레 반입 금지 표지와 정리', '용접반장 정민준', (((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9) + 14), 'DONE', 'A-G-14-2026', 'ADMINISTRATIVE', (((CURRENT_DATE - 148) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), ((((date_trunc('month', CURRENT_DATE) - INTERVAL '6 months')::date + 9) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (38, 33, 19, '이동식 비계 사용 전 바퀴 브레이크, 안전난간 점검표 운영', '생산반장 김철수', ((CURRENT_DATE - 198) + 14), 'DONE', 'D-C-7-2026', 'ADMINISTRATIVE', ((((CURRENT_DATE - 198) + 10) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 198) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (39, 34, 19, '말비계 사용 시 양측 보조부재 설치 확인', '생산반장 김철수', ((CURRENT_DATE - 198) + 14), 'DONE', 'D-C-7-2026', 'ADMINISTRATIVE', ((((CURRENT_DATE - 198) + 17) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 198) + TIME '15:10') AT TIME ZONE 'Asia/Seoul')),
  (40, 41, 16, '지붕 채광창 덮개와 안전방망 설치', '설비팀장 박민수', (CURRENT_DATE - 45), 'OVERDUE', 'C-59-2022', 'ENGINEERING', NULL, ((((date_trunc('month', CURRENT_DATE) - INTERVAL '4 months')::date + 8) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (41, 37, 2, '저장소 소화기 교체, 월 1회 압력 점검', '생산팀장 최동훈', (CURRENT_DATE - 12), 'OVERDUE', NULL, 'ADMINISTRATIVE', NULL, (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (42, 14, 2, '머시닝센터 주변 통로 구획선 도색, 대차 동선 변경', '가공반장 이영희', (CURRENT_DATE + 20), 'PENDING', NULL, 'ENGINEERING', NULL, (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '15:10') AT TIME ZONE 'Asia/Seoul')),
  (43, 18, 2, '집진기 분진 청소 주기 수립, 청소 기록', '설비팀장 박민수', (CURRENT_DATE + 28), 'PENDING', 'D-43-2012', 'ADMINISTRATIVE', NULL, (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '15:20') AT TIME ZONE 'Asia/Seoul')),
  (44, 28, 2, '천장크레인 훅 해지장치 교체', '설비팀장 박민수', (CURRENT_DATE - 28), 'DONE', 'B-M-34-2026', 'ENGINEERING', (((CURRENT_DATE - 19) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - INTERVAL '1 months')::date + TIME '15:30') AT TIME ZONE 'Asia/Seoul')),
  (45, 13, 18, '도어 열림 시 정지 기능 작동 확인, 작업 표준 게시', '가공반장 이영희', ((CURRENT_DATE - 148) + 14), 'DONE', 'M-1-2013', 'ADMINISTRATIVE', ((((CURRENT_DATE - 148) + 9) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 148) + TIME '15:00') AT TIME ZONE 'Asia/Seoul')),
  (46, 9, 20, '시운전 중 소재 위치 조정은 수공구 사용, 양수조작 버튼 위치 재설정', '가공반장 이영희', ((CURRENT_DATE - 240) + 14), 'DONE', 'B-M-36-2026', 'ENGINEERING', ((((CURRENT_DATE - 240) + 10) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 239) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (47, 15, 21, '숫돌 교체, 조정편 간격 재조정, 사용 전 1분 시운전', '가공반장 이영희', ((CURRENT_DATE - 115) + 7), 'DONE', 'B-M-14-2025', 'ENGINEERING', ((((CURRENT_DATE - 115) + 3) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 114) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (48, 16, 21, '연삭기 투명 비산 방지판 설치', '설비팀장 박민수', ((CURRENT_DATE - 115) + 14), 'DONE', 'B-M-14-2025', 'ENGINEERING', ((((CURRENT_DATE - 115) + 22) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 114) + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (49, 29, 22, '지게차 후진 경보음, 후방 카메라 수리', '설비팀장 박민수', ((CURRENT_DATE - 60) + 7), 'DONE', 'B-M-11-2025', 'ENGINEERING', ((((CURRENT_DATE - 60) + 4) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 59) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (50, 29, 22, '창고동 출입구 보행 통로 구획선 도색', '물류담당 오지훈', ((CURRENT_DATE - 60) + 14), 'DONE', 'B-M-11-2025', 'ENGINEERING', ((((CURRENT_DATE - 60) + 18) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 59) + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (51, 20, 23, '용접 작업 시 화재감시자 배치, 방화포 사용', '용접반장 정민준', ((CURRENT_DATE - 150) + 7), 'DONE', 'A-G-14-2026', 'ADMINISTRATIVE', ((((CURRENT_DATE - 150) + 5) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 149) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (52, 20, 23, '용접구역 유기용제 걸레 전용 밀폐 용기 비치', '생산팀장 최동훈', ((CURRENT_DATE - 150) + 14), 'DONE', 'A-G-14-2026', 'ENGINEERING', ((((CURRENT_DATE - 150) + 12) + TIME '16:00') AT TIME ZONE 'Asia/Seoul'), (((CURRENT_DATE - 149) + TIME '10:10') AT TIME ZONE 'Asia/Seoul')),
  (53, 28, 24, '줄걸이 용구 사용 전 점검표 도입, 인양 전 해지장치 확인', '용접반장 정민준', (CURRENT_DATE + 3), 'PENDING', 'B-M-12-2025', 'ADMINISTRATIVE', NULL, (((CURRENT_DATE - 19) + TIME '10:00') AT TIME ZONE 'Asia/Seoul')),
  (54, 25, 24, '인양 작업 신호수 지정, 인양물 하부 출입 금지 교육', '생산팀장 최동훈', (CURRENT_DATE + 25), 'PENDING', 'B-M-34-2026', 'ADMINISTRATIVE', NULL, (((CURRENT_DATE - 19) + TIME '10:10') AT TIME ZONE 'Asia/Seoul'));
SELECT setval('action_id_seq', 54, true);
