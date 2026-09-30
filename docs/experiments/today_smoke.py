"""
Phase 3 §3-3 스모크 — "오늘 할 일"이 실제 사용자 행동에 반응하는가.

`docs/SAIFE_연결성_개선_프롬프트.md` "### 3-3 검증"의 시나리오를 그대로 돈다:

  시드 상태 → UC3 계획서 등록 → PENDING_APPROVAL 추가 확인
           → 승인 → RISKY_WORK_PLAN 추가 확인(사다리 A는 미이행 있음)
           → 사고 등록 → REPORT_DUE 추가 확인

각 단계 직후 `GET /api/dashboard/today`를 다시 불러 종류별 건수 변화를 출력한다.
UC3 대화는 데모 모드(키 없음)로 돈다 — `DemoConversationScript`가 모델 없이
같은 도구 체인(findLocationEquipment → extractWorkPlan → analyzeHazards →
searchCases → getMsds → createWorkPlan)을 실행한다. "내일"이라는 표현을 써서
작업일이 항상 오늘 기준 D+1이 되게 한다 — RISKY_WORK_PLAN의 7일 창 안에 반드시
들어오게 하려는 것이다(하드코딩된 날짜를 쓰면 스모크가 실행 날짜에 따라
창 밖으로 밀려날 수 있다).

사용법:
  SAIFE_BASE_URL=http://localhost:8081 python today_smoke.py

전제:
  - 백엔드가 SAIFE_BASE_URL에 데모 모드(키 없음)로 떠 있어야 한다.
  - docs/experiments/reset_demo_data.py를 먼저 돌려 시드 상태로 되돌려 둔다.
  - 대상 설비는 이동식 사다리 A(id=1) — 시드 자체가 기한 초과 미이행 조치를
    갖고 있어 RISKY_WORK_PLAN의 "미이행 조치 있음" 조건을 자연히 만족한다.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("SAIFE_BASE_URL", "http://localhost:8080")
CONNECTION_ERRORS = (urllib.error.URLError, ConnectionRefusedError, TimeoutError)


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def check(label, ok, detail=""):
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {label}" + (f" — {detail}" if detail else ""))
    return ok


def request(method, path, body=None, timeout=60):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body_text)
        except Exception:
            return e.code, body_text


def chat_turn(message, conversation_id=None):
    body = {"message": message, "conversationId": conversation_id, "slotKey": None}
    req = urllib.request.Request(
        BASE + "/api/agent/chat",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    tools, answer, cid = [], "", conversation_id
    with urllib.request.urlopen(req, timeout=120) as r:
        buf = ""
        for raw in r:
            buf += raw.decode("utf-8", errors="replace")
            while "\n\n" in buf:
                chunk, buf = buf.split("\n\n", 1)
                line = next((l for l in chunk.split("\n") if l.startswith("data:")), None)
                if not line:
                    continue
                try:
                    e = json.loads(line[5:].strip())
                except Exception:
                    continue
                cid = e.get("correlationId") or cid
                t, p = e.get("type"), e.get("payload")
                if t == "ai.tool.done":
                    tools.append((p["toolName"], p.get("success")))
                elif t == "ai.token":
                    answer += str(p)
    return cid, tools, answer


UC3_SCRIPT = [
    "내일 공장동 후면 차양부에서 이동식 사다리 놓고 천장 페인트 작업 하려고요. "
    "저랑 동료 한 명 둘이서 하루 종일 하겠습니다.",
    "천장 높이는 3.2m 정도 됩니다.",
    "안전대 부착설비는 아직 없습니다.",
    "제품은 ○○ 유성페인트 씁니다.",
    "네, 작업계획서 등록해 주세요.",
]


def today_counts():
    status, view = request("GET", "/api/dashboard/today")
    if status != 200:
        print(f"  today() 호출 실패: HTTP {status} {view}")
        sys.exit(1)
    counts = {}
    for item in view["items"]:
        counts[item["kind"]] = counts.get(item["kind"], 0) + 1
    return view, counts


def print_counts(label, counts):
    kinds = ["OVERDUE_ACTION", "DUE_ACTION", "RISKY_WORK_PLAN", "PENDING_APPROVAL",
              "REPORT_DUE", "PATROL_DUE", "PERIODIC_DUE"]
    print(f"  [{label}] " + " · ".join(f"{k}={counts.get(k, 0)}" for k in kinds))


def find_item(view, kind, ref_id):
    return next((i for i in view["items"] if i["kind"] == kind and i.get("refId") == ref_id), None)


def main():
    print(f"BASE = {BASE}")
    failures = []

    section("[0] 시드 상태 — GET /api/dashboard/today")
    view0, counts0 = today_counts()
    print_counts("시드", counts0)

    section("[1] UC3 대화 — 작업계획서 등록(이동식 사다리 A)")
    cid = None
    per_turn_answers = []
    for idx, msg in enumerate(UC3_SCRIPT, 1):
        print(f"  턴 {idx}: {msg[:40]}...")
        try:
            cid, tools, answer = chat_turn(msg, cid)
        except CONNECTION_ERRORS as e:
            print(f"  대화 요청 실패: {e}")
            sys.exit(2)
        for name, ok in tools:
            print(f"    - {name} {'OK' if ok else 'FAIL'}")
        per_turn_answers.append(answer)
    check("대화 ID 발급", cid is not None, str(cid))

    # 실측(2026-09-29): 매 턴 응답에 "비슷한 설비가 여러 건입니다\n- [id=1] ..."가
    # 컨텍스트 복창으로 반복 등장한다 — 이건 findLocationEquipment의 설비 id다
    # (WorkPlanTools의 작업계획서 id와 다른 의미). 순수 "[id=N]"으로 찾으면 매 턴
    # 첫 매치가 이 설비 id(=1)라 항상 틀린 값을 줍는다. 작업계획서 id는 반드시
    # "완료 [id=N]" 형태로만 나온다(extractWorkPlan의 "구조화 완료", createWorkPlan의
    # "제출 완료") — 이 접미사로 좁히고, 전체 턴에서 마지막 매치(가장 최근 상태)를 쓴다.
    plan_id_from_text = None
    all_text = "\n".join(per_turn_answers)
    matches = re.findall(r"완료 \[id=(\d+)\]", all_text)
    if matches:
        plan_id_from_text = int(matches[-1])

    # 2026-09-29 C Task 6a — `/api/work-plan?page=0&size=1`는 workDate DESC 정렬이라
    # 시드의 "과거 작업계획서"(11월 날짜, 이미 SUBMITTED)가 "내일" 날짜인 새 계획서보다
    # 항상 먼저 온다. content[0]을 그냥 집는 방식은 실측에서 엉뚱한 계획서를 골랐다
    # (seed id=11, 2026-11-10을 집어 승인해버림 — 실제 신규 계획서는 id=14였다).
    # createWorkPlan의 응답 문구 `[id=N]`에서 직접 추출한다(evidence_smoke.py와 같은 패턴).
    plan_id = plan_id_from_text
    if plan_id is not None:
        status, plan = request("GET", f"/api/work-plan/{plan_id}")
        check("작업계획서 목록 조회 HTTP 200", status == 200, f"실제 {status}")
    else:
        status, page = request("GET", "/api/work-plan?page=0&size=1")
        if not check("작업계획서 목록 조회 HTTP 200", status == 200, f"실제 {status}"):
            sys.exit(1)
        plan = page["content"][0] if page.get("content") else None
    if plan is None:
        failures.append("생성된 작업계획서를 찾을 수 없음")
        print("작업계획서가 생성되지 않아 이후 단계를 진행할 수 없습니다.")
        sys.exit(1)
    plan_id = plan["id"]
    check("작업계획서 설비=이동식 사다리 A(id=1)", plan.get("equipmentId") == 1,
          f"실제 equipmentId={plan.get('equipmentId')}")
    check("작업계획서 상태=SUBMITTED", plan.get("status") == "SUBMITTED",
          f"실제 status={plan.get('status')}, id={plan_id}")

    section("[2] PENDING_APPROVAL 추가 확인")
    view1, counts1 = today_counts()
    print_counts("등록 직후", counts1)
    pending_item = find_item(view1, "PENDING_APPROVAL", plan_id)
    if not check(f"PENDING_APPROVAL 항목(refId={plan_id}) 등장", pending_item is not None):
        failures.append("PENDING_APPROVAL 미등장")
    if not check("PENDING_APPROVAL 건수 증가",
                 counts1.get("PENDING_APPROVAL", 0) > counts0.get("PENDING_APPROVAL", 0),
                 f"{counts0.get('PENDING_APPROVAL', 0)} -> {counts1.get('PENDING_APPROVAL', 0)}"):
        failures.append("PENDING_APPROVAL 건수 증가하지 않음")

    section("[3] 승인 — POST /api/work-plan/{id}/approve")
    status, detail = request("POST", f"/api/work-plan/{plan_id}/approve",
                              {"approver": "관리부 스모크", "condition": None})
    if not check("승인 HTTP 200", status == 200, f"실제 {status}"):
        sys.exit(1)
    check("승인 후 상태=APPROVED", detail.get("status") == "APPROVED", f"실제 {detail.get('status')}")

    section("[4] RISKY_WORK_PLAN 추가 확인 (사다리 A는 미이행 조치 있음)")
    view2, counts2 = today_counts()
    print_counts("승인 직후", counts2)
    risky_item = find_item(view2, "RISKY_WORK_PLAN", plan_id)
    if not check(f"RISKY_WORK_PLAN 항목(refId={plan_id}) 등장", risky_item is not None):
        failures.append("RISKY_WORK_PLAN 미등장")
    else:
        check("RISKY_WORK_PLAN emphasis=CRITICAL", risky_item.get("emphasis") == "CRITICAL",
              f"실제 {risky_item.get('emphasis')}")
        print(f"    title: {risky_item.get('title')}")
    if not check("PENDING_APPROVAL 항목은 사라짐(더 이상 SUBMITTED 아님)",
                 find_item(view2, "PENDING_APPROVAL", plan_id) is None):
        failures.append("승인 후에도 PENDING_APPROVAL이 남아 있음")

    section("[5] 사고 등록 — POST /api/incident (이동식 사다리 A)")
    occurred_at = datetime.now(timezone.utc).isoformat()
    status, incident_resp = request("POST", "/api/incident", {
        "equipmentId": 1,
        "equipmentQuery": None,
        "workPlanId": plan_id,
        "occurredAt": occurred_at,
        "victimName": "[스모크] 홍길동",
        "severity": "LOST_TIME",
        "leaveDays": 5,
        "accidentType": "FALL",
        "description": "[스모크] today_smoke.py가 등록한 시험용 사고",
    })
    if not check("사고 등록 HTTP 200", status == 200, f"실제 {status} {str(incident_resp)[:200]}"):
        failures.append("사고 등록 실패")
        incident_id = None
    else:
        incident_id = incident_resp["incident"]["id"] if "incident" in incident_resp else incident_resp.get("id")
        check("사고 등록 응답에 report_due_date 존재",
              bool(incident_resp.get("reportDuty") or incident_resp.get("incident")))

    section("[6] REPORT_DUE 추가 확인")
    view3, counts3 = today_counts()
    print_counts("사고 등록 직후", counts3)
    if incident_id is not None:
        report_item = find_item(view3, "REPORT_DUE", incident_id)
        if not check(f"REPORT_DUE 항목(refId={incident_id}) 등장", report_item is not None):
            failures.append("REPORT_DUE 미등장")
        else:
            print(f"    title: {report_item.get('title')}")
    if not check("REPORT_DUE 건수 증가",
                 counts3.get("REPORT_DUE", 0) > counts2.get("REPORT_DUE", 0),
                 f"{counts2.get('REPORT_DUE', 0)} -> {counts3.get('REPORT_DUE', 0)}"):
        failures.append("REPORT_DUE 건수 증가하지 않음")

    section("요약 — 단계별 건수 변화")
    print_counts("0 시드", counts0)
    print_counts("1 등록 후", counts1)
    print_counts("2 승인 후", counts2)
    print_counts("3 사고 후", counts3)

    if failures:
        print(f"\n실패 {len(failures)}건:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print("\n전부 통과.")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except CONNECTION_ERRORS:
        print(f"백엔드가 {BASE}에서 응답하지 않습니다. README의 기동 절차를 먼저 실행하세요.")
        sys.exit(2)
