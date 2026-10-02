-- =====================================================================
-- 작업 전 점검 재정의(2026-10-02)에 맞춘 시드 정리.
--
-- 1) 사업장 표기: 대회용 "가상" 표기를 지운다. 서식과 화면에 그대로 찍히는 값이다.
-- 2) 이동식 사다리 A(시연 설비)의 지적 사항을 안전보건규칙 제42조제4항 기준으로 바꾼다.
--    사다리 작업의 판정 기준은 안전대 부착설비가 아니라 작업발판 확보, 최상부 디딤대 사용 금지,
--    넘어짐 방지다. 감소대책 1순위는 이동식 비계(안전난간) 또는 말비계로 작업발판을 확보하는 것이다.
-- 3) 고소작업대의 판정 근거를 제186조(작업대 안전난간) 기준으로 바꾼다.
-- 4) 화면에 노출되는 판정 근거에서 화살표, 따옴표 등급, 대시, 가운뎃점을 지운다.
--    (RiskRuleEngine이 내는 문구와 같은 형식이다)
--
-- 날짜는 건드리지 않는다. V2, V10의 상대 날짜 계산을 그대로 둔다.
-- 기존 마이그레이션은 수정하지 않는다(체크섬). 바꿀 것은 전부 이 파일의 UPDATE로 한다.
-- =====================================================================

-- ── 사업장 ──────────────────────────────────────────────────────────
UPDATE site SET name = '청우정밀 창원공장', address = '경상남도 창원시 성산구' WHERE id = 1;

UPDATE process SET name = '절단, 가공 라인' WHERE id = 2;

-- ── 이동식 사다리 A: 위험요인과 조치 ───────────────────────────────────
UPDATE hazard
SET missing_control = '작업발판 미확보',
    description     = '차양부 천장 도장 시 이동식 사다리 최상부 디딤대에서 작업'
WHERE id = 1;

UPDATE action
SET content   = '차양부 천장 작업 시 이동식 비계(안전난간) 사용',
    owner     = '생산반장 김철수',
    guide_ref = NULL
WHERE id = 1;

UPDATE action SET owner = '안전관리자 이정훈' WHERE id = 2 AND owner = '안전담당자';
UPDATE action SET owner = '설비팀장 박성민'   WHERE id IN (3, 5, 7) AND owner = '설비팀 김OO';
UPDATE action SET owner = '생산팀장 최동훈'   WHERE id = 4 AND owner = '생산팀장';
UPDATE action SET owner = '안전관리자 이정훈' WHERE id = 6 AND owner = '안전담당자';

-- ── 다른 위험요인의 화면 노출 문자열(가운뎃점) ──────────────────────────
UPDATE hazard SET missing_control = '개구부, 배기구 미확보'   WHERE id = 3;
UPDATE hazard SET missing_control = '유도 표식, 구획선 미설치' WHERE id = 6;

-- ── 판정 근거(rule_trace): 평문, 등급은 배지가 보인다 ────────────────────
UPDATE assessment_hazard
SET rule_trace = '발판 높이 3.2m, 최상부 발판 또는 그 하단 디딤대 사용, 넘어짐 방지(아웃트리거, 고정, 지지자) 없음 (제42조④)'
WHERE hazard_id = 1 AND assessment_id IN (1, 2);

UPDATE assessment_hazard SET rule_trace = '안전모 미착용 확인 (제32조)'
WHERE hazard_id = 2 AND assessment_id = 1;

UPDATE assessment_hazard SET rule_trace = '회전, 구동부 방호덮개 없음 (제87조)'
WHERE hazard_id = 4 AND assessment_id = 1;

UPDATE assessment_hazard SET rule_trace = '통로와 차량 동선 중첩 (제22조, 제172조)'
WHERE hazard_id = 6 AND assessment_id = 2;

UPDATE assessment_hazard SET rule_trace = '인화성 증기, 점화원 관리, 실내면 환기와 방독마스크 (제232조, 제450조)'
WHERE hazard_id = 3 AND assessment_id = 3;

UPDATE assessment_hazard SET rule_trace = '보안경 미착용 후보, 확인 결과 안면보호구 착용으로 담당자 제외'
WHERE hazard_id = 8 AND assessment_id = 2;

-- 고소작업대(hazard 7): 제186조 작업대 안전난간 기준
UPDATE assessment_hazard SET rule_trace = '작업대 안전난간 일부 결손 (제186조)'
WHERE hazard_id = 7 AND assessment_id IN (2, 4);

UPDATE assessment_hazard SET rule_trace = '사고 발생으로 위험 실현, 휴업 5일 (3일 이상), 작업대 안전난간 결손 (제186조)'
WHERE hazard_id = 7 AND assessment_id = 5;

UPDATE assessment_hazard SET rule_trace = '작업대 안전난간 보수 완료, 안전대 착용 (제186조, 제32조)'
WHERE hazard_id = 7 AND assessment_id = 6;

-- ── 고소작업대 작업 전 점검(V10 work_plan 1) ────────────────────────────
UPDATE work_plan
SET method      = '고소작업대로 상승, 기존 조명 탈거, 신규 조명 설치와 결선, 점등 확인',
    briefing    = '작업: 2층 조립구역 조명 교체 / 공장동 2층 조립구역' || chr(10)
                  || chr(10) || '위험 포인트' || chr(10)
                  || '1. 작업대 안전난간이 빠진 곳으로 떨어질 수 있습니다' || chr(10)
                  || chr(10) || '지킬 것' || chr(10)
                  || '1. 작업대 안전난간을 보수한 뒤 올라갑니다' || chr(10)
                  || '2. 안전모, 안전대를 착용합니다' || chr(10)
                  || '미이행 조치: 고소작업대 발판 안전난간 보수' || chr(10)
                  || chr(10) || '위험하면 작업을 멈추고 관리감독자에게 알립니다.',
    approved_by = '이정훈'
WHERE id = 1;

-- ── 사업장 서식 템플릿 이름 ─────────────────────────────────────────────
UPDATE form_template
SET name = '작업 전 안전점검표 (TBM)',
    schema_json = '{"fields":["작업명","작업 장소","작업 일자","현장 확인","유해위험요인과 위험성","TBM","참석자 서명","관리감독자 확인"]}'::jsonb
WHERE doc_type = 'WORK_PLAN' AND site_id = 1;

-- ── 이행 완료 시각: 시드 실행 시각이 아니라 기한 하루 전 오후로 ─────────────
-- V2는 completed_at을 now()로 넣어 이력 화면에 이행일이 시드 적용일로 찍혔다
UPDATE action
SET completed_at = (due_date - 1)::timestamptz + INTERVAL '16 hours'
WHERE id IN (2, 3) AND status = 'DONE' AND due_date IS NOT NULL;
