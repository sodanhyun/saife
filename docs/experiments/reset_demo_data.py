"""
시드 상태로 되돌린다.

회귀 테스트와 시연 녹화를 반복하려면 매번 같은 출발점이 필요하다. Flyway 시드(V2, V3, V10, V12,
V14, V15)가 만든 것까지만 남기고, 실행 중 생긴 것(추가 사고, 수시평가, 순회점검 후보, 작업 전 점검,
대화, 미등록 설비)을 지운다. 볼륨을 날리지 않는 이유는 그게 40초쯤 걸리고 시드 재적재까지 기다려야
하기 때문이다.

시드 경계(V15 반영 후 최대 id):
  equipment         id <= 24  (V2: 1~6, V14: 7~24)
  equipment_change  id <= 5   (V14)
  hazard            id <= 42  (V2/V7: 1~8, V14: 9~42, 32는 비어 있음)
  assessment        id <= 24  (V2: 1~3, V10: 4~6, V14: 7~24)
  action            id <= 54  (V2: 1~5, V10: 6~7, V14: 8~54)
  work_plan         id <= 42  (V10: 1, V15: 2~42)
  incident          id <= 6   (V10: 1, V15: 2~6)
  process / site / public_case / kosha_guide / msds_cache 는 건드리지 않는다

날짜는 되돌리지 않는다. 시드 날짜는 V14/V15가 적용된 날 기준이다. 날짜까지 오늘로 맞추려면
볼륨을 새로 만든다(`docker compose down -v` 후 `up -d --build`, deployment.md 참고).

사용법:
  python reset_demo_data.py
  SAIFE_PG_CONTAINER=saife-clean-postgres python reset_demo_data.py   # 격리 스택 대상

⚠️ 2026-09-29 사고 기록 (Task 6a): 이 스크립트는 `SAIFE_BASE_URL`을 보지 않는다 —
`docker exec`로 **컨테이너 이름**을 직접 때린다. saife-clean 스택(격리된 5434 포트)에
스모크를 돌리면서 "SAIFE_BASE_URL=http://localhost:5174만 바꾸면 된다"고 착각하고
이 스크립트를 그대로 돌렸다가, 매번 공유 세션 DB(`saife-postgres`, 5433 — 동시에
읽기 전용 리뷰어의 비전 테스트가 쓰고 있던 컨테이너)에 DELETE/UPDATE를 실행해버렸다.
그래서 `SAIFE_PG_CONTAINER` 환경변수를 추가했다 — **반드시 기동한 스택의 postgres
컨테이너 이름과 맞춰서 실행할 것.** 기본값은 하위호환을 위해 그대로 `saife-postgres`로
둔다(바꾸면 이 스크립트만 쓰는 다른 세션이 조용히 깨진다).
"""
import os
import subprocess
import sys

CONTAINER = os.environ.get("SAIFE_PG_CONTAINER", "saife-postgres")

MAX_EQUIPMENT = 24
MAX_EQUIPMENT_CHANGE = 5
MAX_HAZARD = 42
MAX_ASSESSMENT = 24
MAX_ACTION = 54
MAX_WORK_PLAN = 42
MAX_INCIDENT = 6

HOLD_WARNING = "작업 보류: 수시평가 완료 전 작업 재개 금지"

SQL = f"""
BEGIN;

-- 실행 중 생긴 것들. 삭제 순서가 있다. incident가 work_plan을 참조하므로 work_plan보다 먼저 지운다.
-- work_plan_evidence(근거 원장 스냅샷, V9)도 work_plan을 참조하므로 같이 먼저 지운다. 안 지우면
-- work_plan_evidence_work_plan_id_fkey 위반으로 리셋 자체가 실패한다.
DELETE FROM incident WHERE id > {MAX_INCIDENT};
DELETE FROM work_plan_evidence WHERE work_plan_id > {MAX_WORK_PLAN};
DELETE FROM work_plan_hazard WHERE work_plan_id > {MAX_WORK_PLAN} OR hazard_id > {MAX_HAZARD};
DELETE FROM work_plan_slot WHERE work_plan_id > {MAX_WORK_PLAN};
DELETE FROM work_plan_worker WHERE work_plan_id > {MAX_WORK_PLAN};
DELETE FROM work_plan WHERE id > {MAX_WORK_PLAN};
DELETE FROM conversation_evidence;
DELETE FROM conversation_state;
DELETE FROM tool_call;
DELETE FROM conversation;

-- 시드 밖의 평가, 위험요인, 조치, 설비
DELETE FROM assessment_hazard WHERE assessment_id > {MAX_ASSESSMENT} OR hazard_id > {MAX_HAZARD};
DELETE FROM action WHERE id > {MAX_ACTION} OR hazard_id > {MAX_HAZARD};
DELETE FROM assessment WHERE id > {MAX_ASSESSMENT};
DELETE FROM hazard WHERE id > {MAX_HAZARD};
DELETE FROM equipment_change WHERE id > {MAX_EQUIPMENT_CHANGE} OR equipment_id > {MAX_EQUIPMENT};
DELETE FROM near_miss WHERE equipment_id > {MAX_EQUIPMENT};
DELETE FROM equipment WHERE id > {MAX_EQUIPMENT};

-- 시드 위험요인의 반영/제외 상태 복원 (순회점검 테스트가 바꿔놓는다).
-- ⚠️ 전부 TRUE로 밀면 안 된다. 6, 8, 27은 사람이 제외한 후보다(V2, V7, V14). 실제 시드 값 그대로 되돌린다.
UPDATE hazard SET ai_suggested = TRUE,  ai_adopted = TRUE
  WHERE id IN (1, 2, 4, 7, 10, 12, 16, 19, 21, 22, 28, 37, 39, 41, 42);
UPDATE hazard SET ai_suggested = TRUE,  ai_adopted = FALSE WHERE id IN (6, 8, 27);
UPDATE hazard SET ai_suggested = FALSE, ai_adopted = NULL
  WHERE id <= {MAX_HAZARD} AND id NOT IN (1, 2, 4, 6, 7, 8, 10, 12, 16, 19, 21, 22, 27, 28, 37, 39, 41, 42);

-- 시드 조치 상태 복원. 미이행 조치는 완료 시각도 지운다(시연에서 이행 처리했을 수 있다)
UPDATE action SET status = 'OVERDUE', completed_at = NULL WHERE id IN (1, 40, 41);
UPDATE action SET status = 'PENDING', completed_at = NULL WHERE id IN (4, 5, 42, 43, 53, 54);
UPDATE action SET status = 'DONE'
  WHERE id <= {MAX_ACTION} AND id NOT IN (1, 4, 5, 40, 41, 42, 43, 53, 54);
UPDATE action SET content = '차양부 천장 작업 시 이동식 비계(안전난간) 사용' WHERE id = 1;
UPDATE action SET content = '고소작업대 안전난간 보수 후 월 1회 점검(재발 방지)' WHERE id = 5;

-- 시드 평가 상태 복원. 사고 6(천장크레인)의 수시평가 24만 작성 중이다
UPDATE assessment SET status = 'CONFIRMED' WHERE id < 24;
UPDATE assessment SET status = 'DRAFT', inspector = NULL, participants = NULL WHERE id = 24;

-- 시드 작업 전 점검 상태 복원(승인, 작업 보류는 시연에서 바뀐다)
UPDATE work_plan SET warning_note = NULL WHERE id <= {MAX_WORK_PLAN} AND id <> 42;
UPDATE work_plan SET status = 'CONDITIONAL' WHERE id IN (1, 26, 35);
UPDATE work_plan SET status = 'APPROVED' WHERE id IN (29, 33, 40, 41);
UPDATE work_plan SET status = 'CLOSED'
  WHERE id <= {MAX_WORK_PLAN} AND id NOT IN (1, 26, 29, 33, 35, 38, 39, 40, 41, 42);
UPDATE work_plan SET status = 'SUBMITTED', approved_by = NULL, approved_at = NULL, approval_note = NULL,
                     briefing_ack_at = NULL WHERE id IN (38, 39);
UPDATE work_plan SET briefing_ack_at = NULL WHERE id IN (40, 41, 42);
UPDATE work_plan SET status = 'HOLD', warning_note = '{HOLD_WARNING}' WHERE id = 42;

-- 시드 사고의 조사표 상태 복원
UPDATE incident SET report_status = 'SUBMITTED' WHERE id IN (1, 5);
UPDATE incident SET report_status = 'NOT_REQUIRED' WHERE id IN (2, 3, 4);
UPDATE incident SET report_status = 'REQUIRED' WHERE id = 6;

SELECT setval('equipment_id_seq', {MAX_EQUIPMENT});
SELECT setval('equipment_change_id_seq', {MAX_EQUIPMENT_CHANGE});
SELECT setval('assessment_id_seq', {MAX_ASSESSMENT});
SELECT setval('hazard_id_seq', {MAX_HAZARD});
SELECT setval('action_id_seq', {MAX_ACTION});
SELECT setval('work_plan_id_seq', {MAX_WORK_PLAN});
SELECT setval('incident_id_seq', {MAX_INCIDENT});

COMMIT;
"""

VERIFY = """
SELECT 'assessment' t, count(*) n FROM assessment
UNION ALL SELECT 'hazard', count(*) FROM hazard
UNION ALL SELECT 'action', count(*) FROM action
UNION ALL SELECT 'work_plan', count(*) FROM work_plan
UNION ALL SELECT 'incident', count(*) FROM incident
UNION ALL SELECT 'equipment', count(*) FROM equipment
UNION ALL SELECT 'public_case', count(*) FROM public_case
ORDER BY 1;
"""

EXPECTED = {
    # V15(데모 규모 시드) 반영 후 시드 상태. hazard는 id 32가 비어 있어 41건이다
    "assessment": 24, "hazard": 41, "action": 54,
    "work_plan": 42, "incident": 6, "equipment": 24,
}


def psql(sql):
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "saife", "-d", "saife",
         "-v", "ON_ERROR_STOP=1", "-t", "-A", "-F", "|", "-c", sql],
        capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        print(result.stderr.strip())
        sys.exit(1)
    return result.stdout.strip()


def main():
    psql(SQL)
    counts = {}
    for line in psql(VERIFY).splitlines():
        if "|" in line:
            name, n = line.split("|", 1)
            counts[name.strip()] = int(n)

    ok = True
    for name, n in sorted(counts.items()):
        expected = EXPECTED.get(name)
        mark = ""
        if expected is not None and n != expected:
            mark = f"  <-- 기대 {expected}"
            ok = False
        print(f"  {name:12} {n}{mark}")

    if not ok:
        print("\n시드 상태와 다르다. 스키마가 바뀌었으면 이 스크립트의 경계값을 고쳐야 한다.")
        sys.exit(1)
    print("\n시드 상태로 복원됨")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
