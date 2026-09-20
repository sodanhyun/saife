-- =====================================================================
-- 가상 사업장 시드
--
-- 대회 제약: 사업장·설비·서식·인물은 전부 가상이다. 회사 자산을 쓰지 않는다.
-- 발표에서 "가상 사업장 기준"이라고 명시한다.
--
-- 이 데이터가 시연의 무대다. 특히 아래 한 줄이 이 출품작의 핵심 대사를 만든다:
--   "이 장소는 3개월 전 순회점검에서 떨어짐 위험 '상'이었고,
--    안전대 부착설비 설치가 미이행 상태입니다."
-- 그 문장이 성립하려면 평가 이력과 미이행 조치가 미리 쌓여 있어야 한다.
-- =====================================================================

INSERT INTO site (id, name, industry_code, worker_count, address, regular_track)
VALUES (1, '가상 정밀금속 사업장', 'C25', 32, '경상남도 창원시 (가상)', true);
SELECT setval('site_id_seq', 1, true);

-- ── 공정 / 장소 ─────────────────────────────────────────────────────
INSERT INTO process (id, site_id, name, work_type, location_tag) VALUES
  (1, 1, '표면처리 라인',   '도장',   '공장동 후면 차양부'),
  (2, 1, '절단·가공 라인',  '기계가공', '공장동 1층 가공구역'),
  (3, 1, '자재 창고',      '운반',   '창고동 1층'),
  (4, 1, '조립 라인',      '조립',   '공장동 2층 조립구역');
SELECT setval('process_id_seq', 4, true);

-- ── 설비 ────────────────────────────────────────────────────────────
-- normalized_name은 Equipment.normalize()와 같은 규칙(공백 제거 + 소문자)이어야 한다.
INSERT INTO equipment (id, site_id, process_id, name, normalized_name, location_tag, object_code, introduced_on) VALUES
  (1, 1, 1, '이동식 사다리 A',   '이동식사다리a',  '공장동 후면 차양부',   'M-0412', '2023-04-11'),
  (2, 1, 1, '도장 부스 1호',     '도장부스1호',    '공장동 후면 차양부',   'M-0771', '2022-09-01'),
  (3, 1, 2, '컨베이어 벨트 2호', '컨베이어벨트2호', '공장동 1층 가공구역',  'M-0233', '2021-06-15'),
  (4, 1, 2, '유압 프레스 3호',   '유압프레스3호',   '공장동 1층 가공구역',  'M-0188', '2020-11-20'),
  (5, 1, 3, '전동 지게차 1호',   '전동지게차1호',   '창고동 1층',          'M-0905', '2023-01-09'),
  (6, 1, 4, '고소작업대',        '고소작업대',      '공장동 2층 조립구역',  'M-0550', '2024-03-02');
SELECT setval('equipment_id_seq', 6, true);

-- ── 위험요인 ────────────────────────────────────────────────────────
-- 시연 주인공은 id=1 (이동식 사다리 A의 추락 위험).
INSERT INTO hazard (id, site_id, equipment_id, process_id, accident_type, missing_control, description, source, ai_suggested, ai_adopted) VALUES
  (1, 1, 1, 1, 'FALL',   '안전대 부착설비 미설치',
      '차양부 천장 작업 시 이동식 사다리 사용. 안전대를 걸 구조물이 없음',            'PHOTO',     true,  true),
  (2, 1, 1, 1, 'PPE',    '안전모 미착용',
      '사다리 작업 중 안전모 미착용 상태 확인',                                      'PHOTO',     true,  true),
  (3, 1, 2, 1, 'FIRE',   '개구부·배기부 미확보',
      '도장 부스 유기용제 증기가 저지대에 축적될 수 있음',                           'MANUAL',    false, null),
  (4, 1, 3, 2, 'CAUGHT', '방호덮개 미설치',
      '컨베이어 구동부 롤러에 방호덮개 없음',                                        'PHOTO',     true,  true),
  (5, 1, 4, 2, 'CAUGHT', '방호덮개 미설치',
      '프레스 금형 교체 구역 방호 미흡',                                             'NEAR_MISS', false, null),
  (6, 1, 5, 3, 'STRUCK', '유도 표식·구획선 미설치',
      '지게차 동선과 보행 통로가 구획되어 있지 않음',                                'PHOTO',     true,  false),
  (7, 1, 6, 4, 'FALL',   '작업발판 안전난간 미설치',
      '고소작업대 발판 단부 난간 일부 결손',                                         'PHOTO',     true,  true);
SELECT setval('hazard_id_seq', 7, true);

-- ── 평가 이력 ───────────────────────────────────────────────────────
-- 3개월 전 상시평가(순회점검) → 브리핑이 인용하는 그 평가다.
INSERT INTO assessment (id, site_id, kind, trigger_type, assessed_on, participants, status) VALUES
  (1, 1, 'ROUTINE', 'PATROL',   CURRENT_DATE - INTERVAL '3 months',  '안전담당자, 생산팀장', 'CONFIRMED'),
  (2, 1, 'ROUTINE', 'PATROL',   CURRENT_DATE - INTERVAL '1 month',   '안전담당자',          'CONFIRMED'),
  (3, 1, 'INITIAL', 'SCHEDULE', CURRENT_DATE - INTERVAL '10 months', '안전담당자, 대표',    'CONFIRMED');
SELECT setval('assessment_id_seq', 3, true);

INSERT INTO assessment_hazard (assessment_id, hazard_id, frequency, severity, risk_level, rule_trace) VALUES
  (1, 1, 3, 3, 'HIGH',   '작업높이 3.2m (2m 초과) + 안전대 부착설비 없음 → ''상'''),
  (1, 2, 2, 2, 'MEDIUM', '보호구 미착용 확인 → ''중''. 상위 위험요인과 결합 시 상향'),
  (1, 4, 3, 3, 'HIGH',   '회전·구동부 방호덮개 미설치 → ''상'''),
  (2, 1, 3, 3, 'HIGH',   '작업높이 3.2m (2m 초과) + 안전대 부착설비 없음 → ''상'''),
  (2, 6, 2, 2, 'MEDIUM', '부딪힘 축 기본값: 통로·차량계 장비 동선 중첩 → ''중'''),
  (2, 7, 3, 3, 'HIGH',   '작업높이 2.4m (2m 초과) + 안전대 부착설비 없음 → ''상'''),
  (3, 3, 2, 3, 'MEDIUM', '유기용제 취급 (증기는 공기보다 무거워 저지대 축적) → ''중''');

-- ── 감소대책 ────────────────────────────────────────────────────────
-- ⚠️ id=1이 미이행 상태다. 이게 UC3 브리핑의 경고를 만든다.
INSERT INTO action (id, hazard_id, assessment_id, content, owner, due_date, status, guide_ref) VALUES
  (1, 1, 1, '차양부 천장에 안전대 부착설비(앵커) 설치', '설비팀 김OO',
      CURRENT_DATE - INTERVAL '1 month', 'OVERDUE', 'C-31-2017'),
  (2, 2, 1, '사다리 작업 시 안전모 착용 지도 및 점검',   '안전담당자',
      CURRENT_DATE - INTERVAL '2 months', 'DONE',    NULL),
  (3, 4, 1, '컨베이어 구동부 방호덮개 제작·설치',        '설비팀 김OO',
      CURRENT_DATE - INTERVAL '2 months', 'DONE',    'M-102-2012'),
  (4, 6, 2, '지게차 동선 구획선 도색 및 보행자 통로 표시', '생산팀장',
      CURRENT_DATE + INTERVAL '14 days',  'PENDING', NULL),
  (5, 7, 2, '고소작업대 발판 안전난간 보수',              '설비팀 김OO',
      CURRENT_DATE + INTERVAL '7 days',   'PENDING', NULL);
SELECT setval('action_id_seq', 5, true);

UPDATE action SET completed_at = now() WHERE status = 'DONE';

-- ── 아차사고 ────────────────────────────────────────────────────────
INSERT INTO near_miss (site_id, equipment_id, description, accident_type, reviewed) VALUES
  (1, 5, '지게차 후진할 때 뒤에 있다가 놀랐음',              'STRUCK', false),
  (1, 4, '프레스 금형 교체 중 손이 가까이 들어간 적 있음',   'CAUGHT', false);

-- ── 회사(가상) 서식 템플릿 ──────────────────────────────────────────
INSERT INTO form_template (site_id, doc_type, name, schema_json, active) VALUES
  (1, 'WORK_PLAN', '위험작업 작업계획서 (가상 사업장 양식)',
   '{"fields":["작업장소","작업명","작업량","작업일시","작업인원","작업순서 및 작업방법","전달사항"]}'::jsonb,
   true),
  (1, 'ASSESSMENT', '위험성평가표 (지침 별지 서식 호환)',
   '{"fields":["유해위험요인","위험성 결정 내용","조치 내용"],"note":"시행규칙 제37조 기록 3요소 순서 고정"}'::jsonb,
   true);
