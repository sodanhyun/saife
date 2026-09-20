"""
시드 상태로 되돌린다.

회귀 테스트를 반복하려면 매번 같은 출발점이 필요하다. Flyway 시드(V2·V3)가
만든 것까지만 남기고, 실행 중 생긴 것(사고·수시평가·사진 판독 후보·작업계획서)을
지운다. 볼륨을 날리지 않는 이유는 그게 40초쯤 걸리고 시드 재적재까지 기다려야
하기 때문이다.

시드 경계:
  assessment  id <= 3
  hazard      id <= 7
  action      id <= 5
  equipment / process / site / public_case / kosha_guide / msds_cache 는 건드리지 않는다

사용법:
  python reset_demo_data.py
"""
import subprocess
import sys

CONTAINER = "saife-postgres"

SQL = """
BEGIN;

-- 실행 중 생긴 것들
-- 삭제 순서가 있다. incident가 work_plan을 참조하므로 사고를 먼저 지운다
DELETE FROM incident;
DELETE FROM work_plan_hazard;
DELETE FROM work_plan_slot;
DELETE FROM work_plan_worker;
DELETE FROM work_plan;
DELETE FROM conversation_state;
DELETE FROM tool_call;
DELETE FROM conversation;

-- 시드 밖의 평가·위험요인
DELETE FROM assessment_hazard WHERE assessment_id > 3 OR hazard_id > 7;
DELETE FROM assessment WHERE id > 3;
DELETE FROM action WHERE id > 5;
DELETE FROM hazard WHERE id > 7;

-- 시드 위험요인의 채택 상태 복원 (사진 판독 테스트가 바꿔놓는다)
UPDATE hazard SET ai_adopted = TRUE WHERE id <= 7 AND ai_suggested = TRUE;

-- 시드 조치 상태 복원
UPDATE action SET status = 'OVERDUE' WHERE id = 1;
UPDATE action SET status = 'DONE'    WHERE id IN (2, 3);
UPDATE action SET status = 'PENDING' WHERE id IN (4, 5);

SELECT setval('assessment_id_seq', 3);
SELECT setval('hazard_id_seq', 7);
SELECT setval('action_id_seq', 5);
SELECT setval('work_plan_id_seq', 1, false);
SELECT setval('incident_id_seq', 1, false);

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
    "assessment": 3, "hazard": 7, "action": 5,
    "work_plan": 0, "incident": 0, "equipment": 6,
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
