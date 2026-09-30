-- =====================================================================
-- Phase 4 — 시드 이야기: 고소작업대(id=6, 공장동 2층 조립구역)
--
-- 이동식 사다리 A(id=1)는 라이브 시연 무대라서 비워 둔다. 첫 실행부터
-- 완결된 논지("평가 → 작업계획서 → 사고 → 재평가가 하나의 설비 ID 위에서
-- 이어진다")를 스스로 말하는 설비가 필요해서 고소작업대 위에 이야기를 쌓는다.
--
-- 설계: docs/SAIFE_연결성_개선_프롬프트.md "## Phase 4 — 시드 이야기" 표.
-- 원 설계는 이 파일을 V8로 부르지만, V9(근거/RAG 스키마)가 먼저 커밋돼야 해서
-- 번호를 V10으로 미뤘다 — 이미 커밋된 V8·V9는 건드리지 않는다
-- (.claude/rules/deployment.md 참고).
--
-- ⚠️ created_at을 사건 순서대로 명시적으로 넣는다. 기본값 now()로 두면
-- 이 마이그레이션 안의 모든 행이 "지금 이 순간" 같은 시각을 갖게 되고,
-- EquipmentHistoryRecaller의 knownAsOf 순환 방지 로직(사고가 만든 수시평가를
-- "사고 전 기록"으로 소환하지 않는다)이 시각 비교로 판단하기 때문에 순서가
-- 무너진다.
--
-- ⚠️ created_at의 기준점은 `now()`가 아니라 hazard 7의 실제 created_at이다.
-- V2는 이 저장소에 최초 적용된 실제 시각에 hazard.created_at을 박아 넣는다
-- (2026-09-21 실측). V10은 그보다 한참 뒤(수 일~수 주 후) 적용되므로,
-- 이야기의 "-75일"·"-50일" 같은 상대 날짜를 `now() - INTERVAL`로 created_at에
-- 그대로 쓰면 **hazard 7 자신의 created_at보다 이른 시각**이 나올 수 있다.
-- `EquipmentHistoryRecaller.toPriorHazards()`는 "hazard 레코드 자체가
-- knownAsOf 이후에 생겼으면 통째로 건너뛴다" — 즉 그 시각에는 이 위험요인이
-- 아직 존재하지 않았다는 뜻이라 순환 방지 검사가 hazard 7을 완전히 지워버린다.
-- 그래서 `assessed_on`/`work_date`/`occurred_at`(이야기가 보여주는 날짜, 화면·
-- 규칙 계산에 쓰인다)은 `CURRENT_DATE - INTERVAL`을 그대로 쓰되, `created_at`
-- (감사 메타데이터, 오직 knownAsOf 순환 방지에만 쓰인다)은 hazard 7의 created_at
-- 이후로 시(時) 단위 오프셋을 줘 서로의 상대 순서만 지킨다.
--
-- 대상 위험요인: hazard id=7 (V2 시드, FALL, "작업발판 안전난간 미설치").
-- 대상 조치: action id=5 (V2 시드, "고소작업대 발판 안전난간 보수", PENDING).
-- =====================================================================

-- ── -75일: 상시평가(순회점검) — hazard 7을 '상'으로 채점 ──────────────
INSERT INTO assessment (id, site_id, kind, trigger_type, assessed_on, participants, status, created_at)
VALUES (4, 1, 'ROUTINE', 'PATROL', CURRENT_DATE - INTERVAL '75 days', '안전담당자, 생산팀장',
        'CONFIRMED', (SELECT created_at FROM hazard WHERE id = 7) + INTERVAL '1 hour');

INSERT INTO assessment_hazard (assessment_id, hazard_id, frequency, severity, risk_level, rule_trace)
VALUES (4, 7, 3, 3, 'HIGH', '작업높이 2.4m (2m 초과) + 안전대 부착설비 없음 → ''상''');

-- ── -75일: 감소대책 — 안전대 착용 지도, -70일에 이행 완료 ────────────
INSERT INTO action (id, hazard_id, assessment_id, content, owner, due_date, status,
                    guide_ref, completed_at, created_at)
VALUES (6, 7, 4, '작업 전 안전대 착용 지도', '안전담당자',
        CURRENT_DATE - INTERVAL '73 days', 'DONE', NULL,
        now() - INTERVAL '70 days', (SELECT created_at FROM hazard WHERE id = 7) + INTERVAL '2 hours');

-- ── -50일: 작업계획서 — 2층 조립구역 조명 교체(조건부 승인) ──────────
INSERT INTO work_plan (id, site_id, process_id, equipment_id, work_name, work_place, work_date,
                       work_hours, method, notes, briefing, briefing_ack_at, status,
                       approval_note, approved_by, approved_at, created_at)
VALUES (1, 1, 4, 6, '2층 조립구역 조명 교체', '공장동 2층 조립구역',
        CURRENT_DATE - INTERVAL '50 days', 4.0,
        '고소작업대로 상승 → 기존 조명 탈거 → 신규 조명 설치 및 결선 → 점등 확인',
        '작업 중 발판 이탈 금지, 안전대 상시 착용',
        '이 설비는 3개월 전 순회점검에서 떨어짐 위험 ''상''이었고, '
            || '작업발판 안전난간 일부가 미보수 상태입니다. 안전대를 반드시 착용하십시오.',
        (CURRENT_DATE - INTERVAL '50 days')::timestamptz + INTERVAL '8 hours 30 minutes',
        'CONDITIONAL', '안전난간 보수 완료 확인 후 작업', '관리부 박OO',
        (CURRENT_DATE - INTERVAL '50 days')::timestamptz + INTERVAL '9 hours',
        (SELECT created_at FROM hazard WHERE id = 7) + INTERVAL '3 hours');

-- ── -45일: 수시평가(사고 직후 자동 생성분을 재현) ─────────────────────
-- incident.follow_up_assessment_id가 이 행을 가리켜야 하므로 incident보다 먼저 만든다.
-- trigger_ref_id=1은 바로 다음에 만들 incident의 id를 미리 안다(이 DB에서 첫 사고이므로
-- incident_id_seq가 1부터 시작한다).
-- created_at은 incident(아래, +4시간)보다 반드시 늦어야 한다(+5시간) — 그래야
-- "사고가 스스로 만든 평가를 사고 전에 알고 있었다"는 순환 논증이 되지 않는다.
INSERT INTO assessment (id, site_id, kind, trigger_type, trigger_ref_id, assessed_on,
                        participants, status, created_at)
VALUES (5, 1, 'OCCASIONAL', 'INCIDENT', 1, CURRENT_DATE - INTERVAL '45 days',
        '안전담당자, 설비팀장', 'CONFIRMED',
        (SELECT created_at FROM hazard WHERE id = 7) + INTERVAL '5 hours');

-- ── -45일: 사고 — 조명 교체 작업 중 발판에서 추락 ─────────────────────
INSERT INTO incident (id, site_id, equipment_id, work_plan_id, occurred_at, victim_name,
                      severity, leave_days, accident_type, description,
                      report_due_date, report_status, follow_up_assessment_id, created_at)
VALUES (1, 1, 6, 1,
        (CURRENT_DATE - INTERVAL '45 days')::timestamptz + INTERVAL '10 hours 20 minutes',
        '이OO', 'LOST_TIME', 5, 'FALL',
        '고소작업대 발판에서 조명 교체 중 난간 미보수 구간으로 추락, 발목 골절',
        (CURRENT_DATE - INTERVAL '45 days') + INTERVAL '1 month',
        'SUBMITTED', 5,
        (SELECT created_at FROM hazard WHERE id = 7) + INTERVAL '4 hours');

INSERT INTO assessment_hazard (assessment_id, hazard_id, frequency, severity, risk_level, rule_trace)
VALUES (5, 7, 3, 3, 'HIGH', '사고 발생으로 빈도 상향 — 작업높이 2.4m + 안전대 부착설비 없음 → ''상''');

-- ── -22일 생성 / -20일 완료: 조치 — 안전난간 보수 완료 ────────────────
INSERT INTO action (id, hazard_id, assessment_id, content, owner, due_date, status,
                    guide_ref, completed_at, created_at)
VALUES (7, 7, 5, '고소작업대 발판 안전난간 보수 완료', '설비팀 김OO',
        CURRENT_DATE - INTERVAL '22 days', 'DONE', 'C-11-2020',
        now() - INTERVAL '20 days', (SELECT created_at FROM hazard WHERE id = 7) + INTERVAL '6 hours');

-- ── V2 시드 action id=5 정리 ───────────────────────────────────────────
-- 원래 내용("고소작업대 발판 안전난간 보수")이 위 완료 조치(id=7)와 모순된다.
-- 이 조치는 "보수 후 재발 방지 점검"으로 성격을 바꾼다. PENDING·기한은 그대로 둔다 —
-- 이게 오늘 할 일의 DUE_ACTION 항목으로 계속 남아야 한다.
UPDATE action
SET content = '고소작업대 안전난간 보수 후 월 1회 점검(재발 방지)'
WHERE id = 5;

-- ── -10일: 재평가(상시) — 난간 보수 완료로 강도 하향 ─────────────────
INSERT INTO assessment (id, site_id, kind, trigger_type, assessed_on, participants, status, created_at)
VALUES (6, 1, 'ROUTINE', 'PATROL', CURRENT_DATE - INTERVAL '10 days', '안전담당자',
        'CONFIRMED', (SELECT created_at FROM hazard WHERE id = 7) + INTERVAL '7 hours');

INSERT INTO assessment_hazard (assessment_id, hazard_id, frequency, severity, risk_level, rule_trace)
VALUES (6, 7, 2, 2, 'MEDIUM', '난간 보수 완료로 강도 하향 — 안전대 부착설비는 여전히 없어 ''중'' 유지');

-- ── 시퀀스 갱신 — 이 파일이 명시적으로 채운 id 다음 값으로 ────────────
SELECT setval('assessment_id_seq', 6, true);
SELECT setval('action_id_seq', 7, true);
SELECT setval('work_plan_id_seq', 1, true);
SELECT setval('incident_id_seq', 1, true);
