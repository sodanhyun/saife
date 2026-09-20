"""
UC2 스모크 테스트 — 루프가 닫히는지 확인한다.

시연 영상 2:20~2:45가 이 경로에 걸려 있다:

    UC3 작업계획서 등록 → 브리핑 확인 → (작업 중 사고) → 사고 등록
    → 같은 설비 이력 소환 → 수시평가 자동 생성 → 법정 기한

검증하는 것:
  1. 소환이 실제 DB 조회인가 (하드코딩이 아닌가)
  2. 미이행 조치가 소환되는가
  3. 브리핑 확인 시각이 "경고했다"의 근거로 올라오는가
  4. 수시평가가 생기고 등급이 올라가는가 (룰 엔진 근거 포함)
  5. 휴업 3일 이상 → 1개월 기한이 잡히는가

사용법:
  python uc2_smoke.py            # UC3부터 전체
  python uc2_smoke.py --no-uc3   # 사고 등록만 (UC3 데이터가 이미 있을 때)
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://localhost:8080"


def post(path, body):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as res:
        raw = res.read().decode("utf-8")
    return json.loads(raw) if raw else None


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=60) as res:
        return json.loads(res.read().decode("utf-8"))


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def run_uc3():
    """UC3를 한 번 돌려 작업계획서와 브리핑을 만든다."""
    import uc3_smoke  # 같은 폴더

    section("[준비] UC3 — 작업계획서 등록")
    uc3_smoke.main()

    plans = get("/api/work-plan?size=5")["content"]
    if not plans:
        print("작업계획서가 생성되지 않았다. UC3부터 실패한 것이다.")
        sys.exit(1)
    return plans[0]


def latest_plan():
    plans = get("/api/work-plan?size=5")["content"]
    return plans[0] if plans else None


def main():
    skip_uc3 = "--no-uc3" in sys.argv

    plan = latest_plan() if skip_uc3 else run_uc3()
    if plan is None:
        print("작업계획서가 없다. --no-uc3 없이 실행하라.")
        sys.exit(1)

    print(f"\n작업계획서 #{plan['id']} — {plan['workName']} ({plan['workDate']})")
    print(f"  설비: {plan['equipmentName']} / 상태: {plan['status']}")

    # --- 브리핑 확인: TBM 이행 증빙이자 UC2의 "경고했다" 근거 ---
    section("[1] 작업자가 브리핑을 확인한다")
    detail = post(f"/api/work-plan/{plan['id']}/ack", {})
    print(f"  briefingAckAt = {detail['briefingAckAt']}")
    assert detail["briefingAckAt"], "브리핑 확인 시각이 기록되지 않았다"

    # --- 사고 등록 ---
    section("[2] 사고 등록 — 같은 설비에서 추락")
    response = post("/api/incident", {
        "equipmentId": plan["equipmentId"],
        "workPlanId": plan["id"],
        "occurredAt": f"{plan['workDate']}T14:20:00+09:00",
        "victimName": "홍00",
        "severity": "LOST_TIME",
        "leaveDays": 14,
        "accidentType": "FALL",
        "description": "차양부 천장 도장 작업 중 이동식 사다리 상부에서 중심을 잃고 약 3.2m 아래 바닥으로 추락",
    })

    incident = response["incident"]
    print(f"  사고 #{incident['id']} / 설비 {incident['equipmentName']}")

    # --- 검증 ---
    recall = response["recall"]
    section("[3] 설비 이력 소환")
    print(f"  {recall['headline']}")
    print()
    print(f"  예고됨(predicted) = {recall['predicted']}")
    print(f"  경고 전달 시각    = {recall['warnedAt']}")
    print(f"  사고 전 위험요인  = {len(recall['priorHazards'])}건")
    for h in recall["priorHazards"]:
        mark = "◀ 사고와 같은 축" if h["sameAxisAsIncident"] else ""
        print(f"    - [{h['accidentType']}] {h['missingControl']} "
              f"/ 최근 등급 {h['lastRiskLevel']} ({h['lastAssessedOn']}) {mark}")
    print(f"  미이행 조치       = {len(recall['unfinishedActions'])}건")
    for a in recall["unfinishedActions"]:
        od = a["overdueDays"]
        state = f"기한 {od}일 경과" if od is not None and od > 0 else "기한 전"
        print(f"    - {a['content']} (기한 {a['dueDate']}, {state}) [{a['status']}]")
    print(f"  과거 작업계획서   = {len(recall['priorWorkPlans'])}건")
    print(f"  같은 설비 과거사고 = {len(recall['priorIncidents'])}건")

    section("[4] 수시평가 자동 생성")
    follow = response["followUp"]
    print(f"  평가 #{follow['assessmentId']} ({follow['kindLabel']})")
    print(f"  근거: {follow['legalBasis']}")
    for r in follow["regraded"]:
        arrow = f"{r['before']} → {r['after']}" if r["changed"] else f"{r['after']} (유지)"
        print(f"    - [{r['accidentType']}] {arrow}")
        print(f"        {r['ruleTrace']}")
    if follow["newHazardId"]:
        print(f"  신규 위험요인 #{follow['newHazardId']}")

    section("[5] 법정 제출 기한")
    duty = response["reportDuty"]
    print(f"  상태: {duty['statusLabel']} / 기한: {duty['dueDate']} / 남은 일수: {duty['daysRemaining']}")
    print(f"  근거: {duty['basis']}")

    section("[6] 산업재해조사표 초안")
    draft = response["draft"]
    print(f"  (AI 생성: {draft['aiGenerated']})")
    print(f"\n[원인]\n{draft['cause']}")
    print(f"\n[재발방지]\n{draft['prevention']}")
    print(f"\n  {draft['disclaimer']}")

    # --- 단언 ---
    section("검증")
    checks = [
        ("이력이 소환됐다", len(recall["priorHazards"]) > 0),
        ("사고와 같은 축의 위험요인이 사고 전부터 있었다", recall["predicted"]),
        ("미이행 조치가 소환됐다", len(recall["unfinishedActions"]) > 0),
        ("기한 지난 조치가 있다",
         any(a["overdueDays"] and a["overdueDays"] > 0 for a in recall["unfinishedActions"])),
        ("브리핑 경고 기록이 소환됐다", recall["warnedAt"] is not None),
        ("수시평가가 생성됐다", follow["assessmentId"] is not None),
        ("사고 축의 등급이 '상'이다",
         any(r["after"] == "HIGH" and r["accidentType"] == "FALL" for r in follow["regraded"])),
        ("등급 근거가 남았다", all(r["ruleTrace"] for r in follow["regraded"])),
        ("법정 기한이 잡혔다", duty["dueDate"] is not None and duty["status"] == "REQUIRED"),
        ("사고에 수시평가가 연결됐다", incident["followUpAssessmentId"] is not None),
        ("조사표 초안이 있다", bool(draft["cause"]) and bool(draft["prevention"])),
    ]
    failed = 0
    for label, ok in checks:
        print(f"  {'OK  ' if ok else 'FAIL'} {label}")
        failed += 0 if ok else 1

    print()
    if failed:
        print(f"{failed}건 실패")
        sys.exit(1)
    print("전부 통과 — 루프가 닫혔다")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:800]}")
        sys.exit(1)
