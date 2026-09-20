"""
UC1 스모크 테스트 — 사진 → 빠진 안전조치 탐지.

검증하는 것:
  1. 업로드가 SSE로 진행 상황을 흘리는가 (assess.progress → assess.done)
  2. 후보가 6축 안에서만 나오는가
  3. 등급을 모델이 아니라 룩업테이블이 매기는가 (rule_trace에 "사진 판독 기준"이 있는가)
  4. 후보가 aiAdopted=null로 저장되는가 (사람이 채택해야 값이 생긴다)
  5. 같은 사진을 두 번 올려도 위험요인이 중복 생성되지 않는가
  6. 채택/반려가 채택률 지표에 반영되는가

이미지는 리포 밖 AI Hub Sample을 쓴다. 저장소에 넣지 않는다.
"""
import io
import json
import mimetypes
import os
import sys
import urllib.request
import uuid

BASE = "http://localhost:8080"
SAMPLE_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))),
    "건설 현장 위험 상태 판단 데이터", "Sample", "01.원천데이터", "5대사고유형")

AXES = {"FALL", "CAUGHT", "DROP", "STRUCK", "FIRE", "PPE"}


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=60) as res:
        return json.loads(res.read().decode("utf-8"))


def post_json(path, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body or {}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST")
    with urllib.request.urlopen(req, timeout=60) as res:
        raw = res.read().decode("utf-8")
    return json.loads(raw) if raw else None


def upload(image_path, equipment_id=None):
    """multipart/form-data를 손으로 만든다. 표준 라이브러리만 쓰기 위해서다."""
    boundary = "----saife" + uuid.uuid4().hex
    ctype = mimetypes.guess_type(image_path)[0] or "image/jpeg"

    buf = io.BytesIO()

    def part(name, value):
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        buf.write(str(value).encode())
        buf.write(b"\r\n")

    if equipment_id is not None:
        part("equipmentId", equipment_id)

    buf.write(f"--{boundary}\r\n".encode())
    buf.write(('Content-Disposition: form-data; name="image"; filename="%s"\r\n'
               % os.path.basename(image_path)).encode())
    buf.write(f"Content-Type: {ctype}\r\n\r\n".encode())
    with open(image_path, "rb") as f:
        buf.write(f.read())
    buf.write(f"\r\n--{boundary}--\r\n".encode())

    req = urllib.request.Request(
        BASE + "/api/vision/analyze",
        data=buf.getvalue(),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST")

    events = []
    with urllib.request.urlopen(req, timeout=180) as res:
        event_name = None
        for raw in res:
            line = raw.decode("utf-8").rstrip("\r\n")
            if line.startswith("event:"):
                event_name = line[6:].strip()
            elif line.startswith("data:"):
                payload = line[5:].strip()
                try:
                    events.append((event_name, json.loads(payload)))
                except json.JSONDecodeError:
                    events.append((event_name, payload))
    return events


def pick_images():
    """유형별로 한 장씩. 데이터가 없으면 건너뛴다."""
    picked = []
    if not os.path.isdir(SAMPLE_ROOT):
        return picked
    for kind in sorted(os.listdir(SAMPLE_ROOT)):
        for label in ("비정상", "정상"):
            d = os.path.join(SAMPLE_ROOT, kind, label)
            if not os.path.isdir(d):
                continue
            files = sorted(f for f in os.listdir(d) if f.lower().endswith((".jpg", ".png")))
            if files:
                picked.append((f"{kind}/{label}", os.path.join(d, files[0])))
                break
    return picked


def main():
    images = pick_images()
    if not images:
        print(f"샘플 이미지를 찾지 못했다: {SAMPLE_ROOT}")
        sys.exit(1)

    failures = []
    first_result = None

    section("[1] 사진 판독")
    for label, path in images:
        events = upload(path, equipment_id=1)
        names = [e[0] for e in events]
        done = next((p for n, p in events if n == "assess.done"), None)
        failed = next((p for n, p in events if n == "assess.failed"), None)

        if failed:
            print(f"  {label:14} FAILED — {failed.get('payload', failed)}")
            failures.append(f"{label} 판독 실패")
            continue

        payload = done.get("payload") if isinstance(done, dict) else None
        if payload is None:
            print(f"  {label:14} assess.done 없음 (이벤트: {names})")
            failures.append(f"{label} assess.done 없음")
            continue

        first_result = first_result or payload
        cands = payload["candidates"]
        demo = " [데모모드]" if payload.get("demoMode") else ""
        print(f"  {label:14} 평가#{payload['assessmentId']} 후보 {len(cands)}건{demo}")
        for c in cands:
            known = " (기존 위험요인 재확인)" if c["alreadyKnown"] else " (신규)"
            print(f"      [{c['accidentType']:6}] {c['missingControl']:26} {c['riskLevel']}"
                  f"  adopted={c['adopted']}{known}")
            print(f"          근거: {(c['evidence'] or '')[:64]}")
            print(f"          등급: {(c['ruleTrace'] or '')[:76]}")

            if c["accidentType"] not in AXES:
                failures.append(f"{label}: 6축 밖의 값 {c['accidentType']}")
            if not c["ruleTrace"] or "사진 판독 기준" not in c["ruleTrace"]:
                failures.append(f"{label}: 등급 근거가 룩업테이블에서 나오지 않았다")
            # 신규 후보만 null이어야 한다. 기존 위험요인을 재확인한 건
            # 예전에 사람이 내린 판단을 그대로 가지고 온다 — 그게 맞다
            if not c["alreadyKnown"] and c["adopted"] is not None:
                failures.append(f"{label}: 신규 후보가 이미 채택 상태다 (사람이 정해야 한다)")

        if "assess.progress" not in names:
            failures.append(f"{label}: 진행 이벤트가 없다")

    section("[2] 중복 생성 방지 — 같은 사진을 한 번 더")
    before = {h["hazardId"] for h in first_result["candidates"]} if first_result else set()
    events = upload(images[0][1], equipment_id=1)
    done = next((p for n, p in events if n == "assess.done"), None)
    after = {c["hazardId"] for c in done["payload"]["candidates"]} if done else set()
    print(f"  1회차 위험요인 id: {sorted(before)}")
    print(f"  2회차 위험요인 id: {sorted(after)}")
    if after and not after.issubset(before | after):
        pass
    reused = bool(after & before)
    print(f"  재사용됨: {reused}")
    if after and before and not reused:
        failures.append("같은 사진인데 위험요인이 새로 생겼다 (설비 이력이 부풀어 오른다)")

    section("[3] 채택/반려 → 채택률 지표")
    # 반려 → 채택 순서로 두 전이를 모두 확인한다.
    # 실행 전 상태에 의존하지 않아야 반복 실행에서도 검증이 성립한다
    if not (first_result and first_result["candidates"]):
        failures.append("후보가 하나도 없어 채택 경로를 검증하지 못했다")
    else:
        target = first_result["candidates"][0]["hazardId"]

        rejected = post_json(f"/api/vision/hazard/{target}/reject")
        print(f"  위험요인 {target} 반려 → adopted={rejected['adopted']}")
        if rejected["adopted"] is not False:
            failures.append("반려가 반영되지 않았다")

        rate_mid = get("/api/vision/adoption-rate")
        adopted = post_json(f"/api/vision/hazard/{target}/adopt")
        print(f"  위험요인 {target} 채택 → adopted={adopted['adopted']}")
        if adopted["adopted"] is not True:
            failures.append("채택이 반영되지 않았다")

        rate_after = get("/api/vision/adoption-rate")
        print(f"  채택률: 제안 {rate_after['suggested']} / 채택 {rate_after['adopted']}"
              f" / 비율 {rate_after['rate']}")
        if rate_after["adopted"] != rate_mid["adopted"] + 1:
            failures.append(
                f"채택률 지표가 1 증가하지 않았다 "
                f"({rate_mid['adopted']} → {rate_after['adopted']})")

    section("검증")
    if failures:
        for f in failures:
            print(f"  FAIL {f}")
        sys.exit(1)
    print("  전부 통과")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
