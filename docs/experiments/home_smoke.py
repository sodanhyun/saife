"""
Phase 1 §1-7 스모크 — 설비 홈 카드 API.

`docs/SAIFE_연결성_개선_프롬프트.md`의 "### 1-7 검증"이 정의를 준다. 확인 항목:

  1. `GET /api/dashboard/equipment/cards`가 설비 6건을 돌려준다.
  2. 각 카드의 `unfinishedActionCount`·`overdueActionCount`·`incidentCount`·
     `currentRiskLevel`이 같은 설비의 `GET /api/dashboard/equipment/{id}/timeline`의
     `summary`와 전부 일치한다(카드-타임라인 불변식 — 백엔드 단위 테스트가 이미
     `EquipmentTimelineServiceTest`로 박아 뒀지만, 이 스모크는 실제 두 엔드포인트를
     HTTP로 호출해 응답 계약 수준에서 다시 확인한다).
  3. 정렬이 emphasis 순(CRITICAL → WARNING → NORMAL)인지, 같은 emphasis 안에서는
     `lastEventOn` 내림차순(null 마지막)·id 오름차순인지.
  4. 시드 상태에서 이동식 사다리 A(id=1)가 CRITICAL이고 기한 초과 조치가 1건 이상인지.

같은 표준 라이브러리 HTTP 호출 방식(`baseline_connectivity.py`)을 쓴다 —
이 스크립트는 SSE를 쓰지 않는다(두 엔드포인트 모두 평범한 JSON GET이다).

사용법:
  SAIFE_BASE_URL=http://localhost:8081 python home_smoke.py

전제:
  - 백엔드가 SAIFE_BASE_URL에 떠 있어야 한다(데모 모드/라이브 모드 무관 — 이
    엔드포인트들은 DB 조회만 하고 모델을 부르지 않는다).
  - docs/experiments/reset_demo_data.py를 먼저 돌려 시드 상태로 되돌려 둔다.
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("SAIFE_BASE_URL", "http://localhost:8080")
CARDS_URL = "/api/dashboard/equipment/cards"

EMPHASIS_RANK = {"CRITICAL": 0, "WARNING": 1, "NORMAL": 2}

# 백엔드가 아예 응답하지 않을 때 잡는 예외 3종 — HTTPError(4xx/5xx)는 정상 응답이므로 제외한다.
CONNECTION_ERRORS = (urllib.error.URLError, ConnectionRefusedError, TimeoutError)


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def get_json(path, timeout=30):
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, json.loads(res.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, body


def check(label, ok, detail=""):
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {label}" + (f" — {detail}" if detail else ""))
    return ok


def main():
    print(f"BASE = {BASE}")
    failures = []

    section("[1] GET /api/dashboard/equipment/cards")
    status, cards = get_json(CARDS_URL)
    if not check("HTTP 200", status == 200, f"실제 {status}"):
        failures.append("cards HTTP status")
        print("\n카드 API 자체가 실패해 이후 검증을 진행할 수 없습니다.")
        sys.exit(1)
    if not isinstance(cards, list):
        failures.append("cards가 배열이 아님")
        print(f"\n예상치 못한 응답 형태: {type(cards)}")
        sys.exit(1)
    if not check("카드 6건", len(cards) == 6, f"실제 {len(cards)}건"):
        failures.append(f"카드 수 {len(cards)} != 6")

    for c in cards:
        print(f"    id={c.get('id')} name={c.get('name')} emphasis={c.get('emphasis')} "
              f"unfinished={c.get('unfinishedActionCount')} overdue={c.get('overdueActionCount')} "
              f"incidents={c.get('incidentCount')} lastEventOn={c.get('lastEventOn')}")

    section("[2] 카드-타임라인 불변식 — GET /api/dashboard/equipment/{id}/timeline")
    for c in cards:
        eq_id = c.get("id")
        t_status, timeline = get_json(f"/api/dashboard/equipment/{eq_id}/timeline")
        if not check(f"설비 {eq_id} 타임라인 HTTP 200", t_status == 200, f"실제 {t_status}"):
            failures.append(f"설비 {eq_id} 타임라인 조회 실패")
            continue
        summary = timeline.get("summary", {})
        fields = [
            ("unfinishedActionCount", c.get("unfinishedActionCount"), summary.get("unfinishedActionCount")),
            ("overdueActionCount", c.get("overdueActionCount"), summary.get("overdueActionCount")),
            ("incidentCount", c.get("incidentCount"), summary.get("incidentCount")),
            ("currentRiskLevel", c.get("currentRiskLevel"), summary.get("currentRiskLevel")),
        ]
        for name, card_value, summary_value in fields:
            ok = card_value == summary_value
            if not check(f"설비 {eq_id} {name} 일치", ok, f"card={card_value} summary={summary_value}"):
                failures.append(f"설비 {eq_id} {name} 불일치(card={card_value}, summary={summary_value})")

    section("[3] 정렬 — emphasis(CRITICAL→WARNING→NORMAL) → lastEventOn desc(null 마지막) → id asc")
    ranks = [EMPHASIS_RANK.get(c.get("emphasis"), 99) for c in cards]
    sorted_by_emphasis = ranks == sorted(ranks)
    check("emphasis 오름차순(CRITICAL 먼저)", sorted_by_emphasis, f"실제 순서 {[c.get('emphasis') for c in cards]}")
    if not sorted_by_emphasis:
        failures.append("emphasis 정렬 위반")

    # 같은 emphasis 그룹 안에서 lastEventOn 내림차순(null은 맨 뒤) · id 오름차순인지 확인한다.
    idx = 0
    while idx < len(cards):
        emphasis = cards[idx].get("emphasis")
        group = []
        while idx < len(cards) and cards[idx].get("emphasis") == emphasis:
            group.append(cards[idx])
            idx += 1

        dated = [g for g in group if g.get("lastEventOn") is not None]

        # null lastEventOn은 그룹의 뒤쪽에 몰려 있어야 한다(앞쪽 len(dated)개는 전부 날짜가 있어야 함).
        nulls_at_end = all(g.get("lastEventOn") is not None for g in group[: len(dated)])
        if not check(f"emphasis={emphasis} 그룹 내 lastEventOn=null이 뒤로 몰려 있음", nulls_at_end):
            failures.append(f"emphasis={emphasis} 그룹 null lastEventOn 위치 위반")

        # 날짜가 있는 항목끼리는 lastEventOn 내림차순, 같은 날짜면 id 오름차순이어야 한다.
        expected_dated_desc = sorted(dated, key=lambda g: (g["lastEventOn"], -g["id"]), reverse=True)
        ok = [g["id"] for g in dated] == [g["id"] for g in expected_dated_desc]
        if not check(f"emphasis={emphasis} 그룹 내 lastEventOn 내림차순(동률은 id 오름차순)", ok,
                     f"실제 id 순서 {[g['id'] for g in dated]}"):
            failures.append(f"emphasis={emphasis} 그룹 lastEventOn 정렬 위반")

    section("[4] 시드 상태 — 이동식 사다리 A(id=1)는 CRITICAL, 기한 초과 조치 1건 이상")
    ladder_a = next((c for c in cards if c.get("id") == 1), None)
    if ladder_a is None:
        failures.append("id=1(이동식 사다리 A) 카드가 없음")
        check("id=1 카드 존재", False)
    else:
        check("id=1 name=이동식 사다리 A", ladder_a.get("name") == "이동식 사다리 A", f"실제 {ladder_a.get('name')}")
        if not check("id=1 emphasis=CRITICAL", ladder_a.get("emphasis") == "CRITICAL",
                     f"실제 {ladder_a.get('emphasis')}"):
            failures.append("id=1 emphasis != CRITICAL")
        if not check("id=1 overdueActionCount>=1", (ladder_a.get("overdueActionCount") or 0) >= 1,
                     f"실제 {ladder_a.get('overdueActionCount')}"):
            failures.append("id=1 overdueActionCount < 1")

    section("[5] Phase 4 시드 이야기 — 고소작업대(id=6) incidentCount==1")
    platform = next((c for c in cards if c.get("id") == 6), None)
    if platform is None:
        failures.append("id=6(고소작업대) 카드가 없음")
        check("id=6 카드 존재", False)
    else:
        check("id=6 name=고소작업대", platform.get("name") == "고소작업대", f"실제 {platform.get('name')}")
        if not check("id=6 incidentCount==1", platform.get("incidentCount") == 1,
                     f"실제 {platform.get('incidentCount')}"):
            failures.append("id=6 incidentCount != 1")

    section("[6] Phase 4 시드 이야기 — 타임라인 사건 순서(평가→조치→계획서→사고→수시평가→조치→재평가)")
    t_status, timeline = get_json("/api/dashboard/equipment/6/timeline")
    if not check("설비 6 타임라인 HTTP 200", t_status == 200, f"실제 {t_status}"):
        failures.append("설비 6 타임라인 조회 실패")
    else:
        events = timeline.get("events", [])
        for e in events:
            print(f"    {e.get('at')} {e.get('type'):10} {e.get('title')}")
        # EventType 순서(ASSESSMENT/ACTION/WORK_PLAN/INCIDENT)와 날짜만으로는
        # "평가→조치→계획서→사고→수시평가→조치→재평가"를 못 구분한다(같은 타입이 여러 번 나온다) —
        # 그래서 사건의 detail·title로 V10 시드가 심은 특정 사건을 순서대로 집는다.
        markers = [
            ("ASSESSMENT", "상시"),   # -75일 순회점검(hazard 7을 '상'으로)
            ("ACTION", "안전대 착용 지도"),  # -75일 감소대책
            ("WORK_PLAN", "2층 조립구역 조명 교체"),  # -50일 작업계획서
            ("INCIDENT", "추락"),  # -45일 사고
            ("ASSESSMENT", "수시"),  # -45일 수시평가(사고 직후)
            ("ACTION", "안전난간 보수 완료"),  # -20일 조치 완료
            ("ASSESSMENT", "상시"),  # -10일 재평가
        ]
        idx = 0
        order_ok = True
        for kind, needle in markers:
            found_at = None
            for j in range(idx, len(events)):
                if events[j].get("type") == kind and needle in (events[j].get("title") or ""):
                    found_at = j
                    break
            if found_at is None:
                order_ok = False
                check(f"사건 '{kind}:{needle}' 찾음(이전 사건들 이후)", False)
                break
            idx = found_at + 1
        if order_ok:
            check("7개 사건이 평가→조치→계획서→사고→수시평가→조치→재평가 순서로 나타남", True)
        else:
            failures.append("고소작업대 타임라인 사건 순서가 이야기와 다름")

    section("요약")
    if failures:
        print(f"실패 {len(failures)}건:")
        for f in failures:
            print(f"  - {f}")
        sys.exit(1)
    print("전부 통과.")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except CONNECTION_ERRORS:
        print(f"백엔드가 {BASE}에서 응답하지 않습니다. README의 기동 절차를 먼저 실행하세요.")
        sys.exit(2)
