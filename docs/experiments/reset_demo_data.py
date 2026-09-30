"""
시드 상태로 되돌린다.

회귀 테스트를 반복하려면 매번 같은 출발점이 필요하다. Flyway 시드(V2·V3·V10)가
만든 것까지만 남기고, 실행 중 생긴 것(추가 사고·수시평가·사진 판독 후보·작업계획서)을
지운다. 볼륨을 날리지 않는 이유는 그게 40초쯤 걸리고 시드 재적재까지 기다려야
하기 때문이다.

시드 경계 — V10(고소작업대 이야기 시드) 반영 후 최대 id:
  assessment  id <= 6   (V2: 1~3, V10: 4~6 — 상시평가·수시평가·재평가)
  hazard      id <= 8   (V10은 새 hazard를 만들지 않는다 — hazard 7을 재사용)
  action      id <= 7   (V2: 1~5, V10: 6~7 — 안전대 착용 지도·안전난간 보수 완료)
  work_plan   id <= 1   (V10: 2층 조립구역 조명 교체)
  incident    id <= 1   (V10: 고소작업대 추락 사고, report_status=SUBMITTED)
  equipment / process / site / public_case / kosha_guide / msds_cache 는 건드리지 않는다

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

SQL = """
BEGIN;

-- 실행 중 생긴 것들 — V10 시드 행(work_plan id=1, incident id=1)은 남기고
-- 그 이후 id만 지운다. 삭제 순서가 있다. incident가 work_plan을 참조하므로
-- work_plan보다 먼저 지운다. work_plan_evidence(근거 원장 스냅샷, V9)도
-- work_plan을 참조하므로 같이 먼저 지운다 — 안 지우면
-- work_plan_evidence_work_plan_id_fkey 위반으로 리셋 자체가 실패한다.
DELETE FROM incident WHERE id > 1;
DELETE FROM work_plan_evidence WHERE work_plan_id > 1;
DELETE FROM work_plan_hazard WHERE work_plan_id > 1;
DELETE FROM work_plan_slot WHERE work_plan_id > 1;
DELETE FROM work_plan_worker WHERE work_plan_id > 1;
DELETE FROM work_plan WHERE id > 1;
DELETE FROM conversation_evidence;
DELETE FROM conversation_state;
DELETE FROM tool_call;
DELETE FROM conversation;

-- 시드 밖의 평가·위험요인
DELETE FROM assessment_hazard WHERE assessment_id > 6 OR hazard_id > 8;
DELETE FROM assessment WHERE id > 6;
DELETE FROM action WHERE id > 7;
DELETE FROM hazard WHERE id > 8;

-- 시드 위험요인의 채택 상태 복원 (사진 판독 테스트가 바꿔놓는다).
--
-- ⚠️ 전부 TRUE로 밀면 안 된다. V2 시드는 일부러 섞어놨다 — 6번은 사람이 반려한
--    후보다. 전부 채택으로 만들면 화면의 채택률이 100%가 되고, 심사위원에게
--    100%는 신뢰가 아니라 의심을 부른다 ("AI가 다 맞았다고?").
--    8번은 V7이 넣은 PPE 반려 건이다 — 게이트 반영으로 6번(STRUCK)이 집계에서
--    빠지면서 통과 축에도 반려 건이 필요해졌다.
--    실제 시드 값 그대로 되돌린다.
UPDATE hazard SET ai_suggested = TRUE,  ai_adopted = TRUE  WHERE id IN (1, 2, 4, 7);
UPDATE hazard SET ai_suggested = TRUE,  ai_adopted = FALSE WHERE id IN (6, 8);
UPDATE hazard SET ai_suggested = FALSE, ai_adopted = NULL  WHERE id IN (3, 5);

-- 시드 조치 상태 복원 (V2: 1~5, V10: 6~7)
UPDATE action SET status = 'OVERDUE' WHERE id = 1;
UPDATE action SET status = 'DONE'    WHERE id IN (2, 3, 6, 7);
UPDATE action SET status = 'PENDING' WHERE id IN (4, 5);

-- V10 시드 행의 텍스트·상태도 원상 복구한다 (수동 테스트가 건드렸을 수 있다)
UPDATE action SET content = '고소작업대 안전난간 보수 후 월 1회 점검(재발 방지)' WHERE id = 5;
UPDATE incident SET report_status = 'SUBMITTED' WHERE id = 1;
UPDATE work_plan SET status = 'CONDITIONAL' WHERE id = 1;

SELECT setval('assessment_id_seq', 6);
SELECT setval('hazard_id_seq', 8);
SELECT setval('action_id_seq', 7);
SELECT setval('work_plan_id_seq', 1);
SELECT setval('incident_id_seq', 1);

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
    # V10(고소작업대 이야기) 반영 후 시드 경계. work_plan·incident는 이제
    # 0건이 아니라 V10이 심은 1건씩이 "시드 상태"다.
    "assessment": 6, "hazard": 8, "action": 7,
    "work_plan": 1, "incident": 1, "equipment": 6,
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
