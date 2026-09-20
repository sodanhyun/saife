"""
엣지케이스 점검.

정상 경로는 스모크 테스트가 본다. 여기서는 <b>잘못된 입력과 없는 자원</b>을 친다.
심사위원이 화면을 눌러보다 500 스택트레이스를 보면 그걸로 끝이다.

각 항목은 "무엇을 기대하는가"를 명시한다. 기대가 없으면 통과 여부를 말할 수 없다.
"""
import io
import json
import mimetypes
import sys
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"


def call(method, path, body=None, raw=None, ctype=None):
    """(status, body_text)를 돌려준다. 예외를 삼키지 않고 상태코드로 바꾼다."""
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif raw is not None:
        data = raw
        headers["Content-Type"] = ctype

    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as res:
            return res.status, res.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(f"  {'OK  ' if ok else 'FAIL'} {name}")
    if detail:
        print(f"       {detail}")


def multipart(fields, filename, content, ctype):
    boundary = "----edge" + uuid.uuid4().hex
    buf = io.BytesIO()
    for k, v in fields.items():
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode())
        buf.write(str(v).encode())
        buf.write(b"\r\n")
    buf.write(f"--{boundary}\r\n".encode())
    buf.write(f'Content-Disposition: form-data; name="image"; filename="{filename}"\r\n'.encode())
    buf.write(f"Content-Type: {ctype}\r\n\r\n".encode())
    buf.write(content)
    buf.write(f"\r\n--{boundary}--\r\n".encode())
    return buf.getvalue(), f"multipart/form-data; boundary={boundary}"


def section(t):
    print()
    print("=" * 72)
    print(t)
    print("=" * 72)


def main():
    # ── 없는 자원 ────────────────────────────────────────────────
    section("[1] 없는 자원 — 500 스택트레이스가 뜨면 안 된다")

    for path, label in [
        ("/api/incident/999999", "사고 상세"),
        ("/api/work-plan/999999", "작업계획서 상세"),
        ("/api/dashboard/equipment/999999/timeline", "설비 타임라인"),
        ("/api/vision/result/999999", "판독 결과"),
        ("/form/incident/999999", "산업재해조사표"),
        ("/form/assessment/999999", "위험성평가표"),
        ("/form/work-plan/999999", "작업계획서 서식"),
    ]:
        status, body = call("GET", path)
        ok = status == 404
        check(f"{label} 없는 id → 404", ok, f"실제 {status}" + ("" if ok else f" / {body[:100]}"))

    # ── 잘못된 상태 전이 ─────────────────────────────────────────
    section("[2] 잘못된 상태 전이")

    status, body = call("GET", "/api/work-plan?size=1")
    plans = json.loads(body)["content"] if status == 200 else []
    if plans:
        pid = plans[0]["id"]
        status, body = call("POST", f"/api/work-plan/{pid}/approve", {})
        first_ok = status in (200, 409)
        check("승인 1회차", first_ok, f"실제 {status}")

        status, body = call("POST", f"/api/work-plan/{pid}/approve", {})
        check("이미 승인된 계획서 재승인 → 409 (상태 전이 위반)", status == 409,
              f"실제 {status} / {body[:110]}")

        status, _ = call("POST", f"/api/work-plan/{pid}/ack", {})
        status2, _ = call("POST", f"/api/work-plan/{pid}/ack", {})
        check("브리핑 확인 두 번 → 둘 다 200 (최초 시각 유지)",
              status == 200 and status2 == 200, f"실제 {status}, {status2}")
    else:
        print("  SKIP 작업계획서가 없어 상태 전이는 건너뛴다 (스모크 테스트 뒤에 돌리면 검사된다)")

    # ── 사고 등록 경계값 ─────────────────────────────────────────
    section("[3] 사고 등록 경계값")

    status, body = call("POST", "/api/incident", {
        "equipmentId": 1, "occurredAt": "2026-09-21T10:00:00+09:00",
        "accidentType": "FALL", "description": "휴업일수 미입력"})
    duty = json.loads(body)["reportDuty"] if status == 200 else {}
    check("휴업일수 미입력 → UNDETERMINED (의무 없음으로 단정하지 않는다)",
          duty.get("status") == "UNDETERMINED", f"실제 {duty.get('status')}")

    status, body = call("POST", "/api/incident", {
        "equipmentId": 1, "occurredAt": "2026-09-21T10:00:00+09:00",
        "leaveDays": 2, "accidentType": "FALL", "description": "휴업 2일"})
    duty = json.loads(body)["reportDuty"] if status == 200 else {}
    check("휴업 2일 → NOT_REQUIRED", duty.get("status") == "NOT_REQUIRED",
          f"실제 {duty.get('status')}")

    status, body = call("POST", "/api/incident", {
        "equipmentId": 1, "occurredAt": "2026-09-21T10:00:00+09:00",
        "leaveDays": 3, "accidentType": "FALL", "description": "휴업 3일 경계"})
    duty = json.loads(body)["reportDuty"] if status == 200 else {}
    check("휴업 3일(경계) → REQUIRED", duty.get("status") == "REQUIRED",
          f"실제 {duty.get('status')} / 기한 {duty.get('dueDate')}")

    status, body = call("POST", "/api/incident", {
        "equipmentQuery": "존재하지않는설비ZZZ", "occurredAt": "2026-09-21T10:00:00+09:00",
        "leaveDays": 5, "accidentType": "DROP", "description": "설비 미상"})
    ok = status == 200
    recall = json.loads(body)["recall"] if ok else {}
    check("설비 미상 → 200, 과장 없이 안내", ok and not recall.get("predicted"),
          f"실제 {status} / {recall.get('headline', '')[:60]}")

    status, body = call("POST", "/api/incident", {
        "equipmentId": 1, "occurredAt": "2026-09-21T10:00:00+09:00",
        "leaveDays": 5, "description": "발생형태 없음"})
    ok = status == 200
    check("발생형태 미입력 → 200 (기록은 남는다)", ok, f"실제 {status}")

    status, body = call("POST", "/api/incident", {
        "equipmentId": 1, "leaveDays": 5, "accidentType": "FALL",
        "description": "발생일시 없음"})
    check("발생일시 미입력 → 200 (현재 시각으로 기록)", status == 200, f"실제 {status}")

    status, body = call("POST", "/api/incident", {
        "equipmentId": 1, "occurredAt": "2026-09-21T10:00:00+09:00",
        "leaveDays": -5, "accidentType": "FALL", "description": "음수 휴업일수"})
    check("음수 휴업일수 → 400 (있을 수 없는 값)", status == 400, f"실제 {status}")

    status, body = call("POST", "/api/incident", {
        "equipmentId": 1, "occurredAt": "2026-09-21T10:00:00+09:00",
        "leaveDays": 5, "accidentType": "고장", "description": "없는 발생형태"})
    check("6축 밖의 발생형태 → 400", status == 400, f"실제 {status}")

    # ── 사진 판독 ────────────────────────────────────────────────
    section("[4] 사진 판독")

    body_bytes, ctype = multipart({"equipmentId": 1}, "not-an-image.txt",
                                  b"this is plain text, not an image", "text/plain")
    status, text = call("POST", "/api/vision/analyze", raw=body_bytes, ctype=ctype)
    ok = status in (200, 400) and "Exception" not in text and "500" not in str(status)
    check("이미지가 아닌 파일 → 스택트레이스 없이 처리", ok,
          f"실제 {status} / {text[:110]}")

    body_bytes, ctype = multipart({"equipmentId": 1}, "empty.jpg", b"", "image/jpeg")
    status, text = call("POST", "/api/vision/analyze", raw=body_bytes, ctype=ctype)
    check("빈 파일 → 400", status == 400, f"실제 {status} / {text[:110]}")

    status, text = call("POST", "/api/vision/hazard/999999/adopt", {})
    check("없는 위험요인 채택 → 404", status == 404, f"실제 {status}")

    # ── 목록 페이징 ──────────────────────────────────────────────
    section("[5] 목록 경계")

    status, body = call("GET", "/api/incident?page=9999&size=20")
    ok = status == 200 and json.loads(body)["content"] == []
    check("범위 밖 페이지 → 200 + 빈 목록", ok, f"실제 {status}")

    status, body = call("GET", "/api/incident?page=0&size=0")
    check("size=0 → 400 (0건씩 페이징은 의미가 없다)", status == 400, f"실제 {status}")

    # ── 결과 ─────────────────────────────────────────────────────
    section("결과")
    failed = [r for r in RESULTS if not r[1]]
    print(f"  {len(RESULTS) - len(failed)}/{len(RESULTS)} 통과")
    if failed:
        print()
        for name, _, detail in failed:
            print(f"  FAIL {name} — {detail}")
        sys.exit(1)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
