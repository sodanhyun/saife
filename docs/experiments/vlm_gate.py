"""
VLM 선검증 게이트 — 축당 5장 = 30장.

채택 규칙은 ../vlm-gate-rules-20260921.md에 **결과를 보기 전에** 확정했다.
이 스크립트는 그 규칙을 집행할 뿐 기준을 정하지 않는다.

  python vlm_gate.py --build-sets   두 세트 동결 (게이트 30 / 평가셋 30, 겹치지 않음)
  python vlm_gate.py --run          게이트 30장 판독 → gate_findings.json
  python vlm_gate.py --score        판정 기록(adjudication.json)을 읽어 축별 통과 계산
  python vlm_gate.py --run-eval     평가셋 판독 → eval_findings.json
  python vlm_gate.py --score-eval   평가셋 판정(eval_adjudication.json)으로 재확인

세트는 한 번 만들면 다시 만들지 않는다. 재실행할 때마다 이미지가 바뀌면
게이트가 의미를 잃는다.
"""
import io
import json
import mimetypes
import os
import random
import sys
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))  # SAIFE/

GATE_SET = os.path.join(HERE, "gate_set.json")
EVAL_SET = os.path.join(HERE, "eval_set.json")
FINDINGS = os.path.join(HERE, "gate_findings.json")
EVAL_FINDINGS = os.path.join(HERE, "eval_findings.json")
ADJUDICATION = os.path.join(HERE, "adjudication.json")
EVAL_ADJUDICATION = os.path.join(HERE, "eval_adjudication.json")

CONSTRUCTION = os.path.join(
    ROOT, "건설 현장 위험 상태 판단 데이터", "Sample", "01.원천데이터", "5대사고유형")
MANUFACTURING = os.path.join(
    ROOT, "스마트 제조 시설 안전 감시를 위한 데이터", "Sample", "01.원천데이터",
    "human-accident", "jam", "rgb")
SHIP_PPE = os.path.join(
    ROOT, "선박·해양플랜트 스마트 야드 안전 데이터", "Sample", "01.원천데이터",
    "안전장비-S63_DATA3")

AXES = ["FALL", "CAUGHT", "DROP", "STRUCK", "FIRE", "PPE"]

# 축 → 출처. 규칙 문서 2항과 같아야 한다
SOURCES = {
    "FALL": ("건설 추락/비정상", os.path.join(CONSTRUCTION, "추락", "비정상")),
    "DROP": ("건설 낙하/비정상", os.path.join(CONSTRUCTION, "낙하", "비정상")),
    "STRUCK": ("건설 전도/비정상", os.path.join(CONSTRUCTION, "전도", "비정상")),
    "FIRE": ("건설 화재/비정상", os.path.join(CONSTRUCTION, "화재", "비정상")),
}

# PPE만 정답이 있다. 파일명 접미가 라벨이다 (B=안전대 H=안전모 M=용접마스크, 0=미착용)
PPE_TRUTH = {
    "B0": ("안전대 미착용", True), "B1": ("안전대 착용", False),
    "H0": ("안전모 미착용", True), "H1": ("안전모 착용", False),
    "M0": ("용접마스크 미착용", True), "M1": ("용접마스크 착용", False),
}


def images_in(path, exts=(".jpg", ".jpeg", ".png")):
    if not os.path.isdir(path):
        return []
    return sorted(os.path.join(path, f) for f in os.listdir(path)
                  if f.lower().endswith(exts))


def build_sets():
    """게이트 30장과 평가셋 30장을 겹치지 않게 고른다. 시드를 박아 재현 가능하게."""
    if os.path.exists(GATE_SET):
        print(f"이미 동결되어 있다: {GATE_SET}")
        print("세트를 다시 만들면 게이트가 의미를 잃는다. 지우려면 손으로 지울 것.")
        return

    rng = random.Random(20260921)
    gate, evalset = {}, {}

    for axis in ["FALL", "DROP", "STRUCK", "FIRE"]:
        label, path = SOURCES[axis]
        pool = images_in(path)
        if len(pool) < 10:
            print(f"경고: {axis} 이미지 부족 ({len(pool)}장)")
        rng.shuffle(pool)
        gate[axis] = [{"path": p, "source": label} for p in pool[:5]]
        evalset[axis] = [{"path": p, "source": label} for p in pool[5:10]]

    # CAUGHT — 시퀀스마다 1장씩. 같은 시퀀스의 연속 프레임은 사실상 같은 사진이다
    seqs = sorted(d for d in os.listdir(MANUFACTURING)
                  if os.path.isdir(os.path.join(MANUFACTURING, d))) \
        if os.path.isdir(MANUFACTURING) else []
    caught_gate, caught_eval = [], []
    for i, seq in enumerate(seqs):
        frames = images_in(os.path.join(MANUFACTURING, seq))
        if not frames:
            continue
        # 시퀀스 중간 프레임을 쓴다. 첫 프레임은 사람이 아직 안 들어온 경우가 많다
        mid = frames[len(frames) // 2]
        late = frames[min(len(frames) - 1, (len(frames) * 3) // 4)]
        (caught_gate if len(caught_gate) < 5 else caught_eval).append(
            {"path": mid, "source": f"제조 jam/{seq}"})
        if len(caught_gate) >= 5:
            caught_eval.append({"path": late, "source": f"제조 jam/{seq}"})
    gate["CAUGHT"] = caught_gate[:5]
    evalset["CAUGHT"] = caught_eval[:5]

    # PPE — 미착용 3 + 착용 2. 정답 라벨이 파일명에 있다
    ppe = images_in(SHIP_PPE)
    missing, worn = [], []
    for p in ppe:
        for key, (desc, is_missing) in PPE_TRUTH.items():
            if f"_{key}_" in os.path.basename(p):
                (missing if is_missing else worn).append(
                    {"path": p, "source": "선박 안전장비", "truth": desc,
                     "expect_finding": is_missing})
    gate["PPE"] = missing[:3] + worn[:2]
    evalset["PPE"] = missing[3:] + worn[2:]

    save(GATE_SET, gate)
    save(EVAL_SET, evalset)

    overlap = ({i["path"] for a in gate.values() for i in a}
               & {i["path"] for a in evalset.values() for i in a})
    print(f"게이트 {sum(len(v) for v in gate.values())}장 / "
          f"평가셋 {sum(len(v) for v in evalset.values())}장")
    print(f"겹침: {len(overlap)}장 (0이어야 한다)")
    for axis in AXES:
        print(f"  {axis:7} 게이트 {len(gate.get(axis, [])):2}  평가셋 {len(evalset.get(axis, [])):2}")


def save(path, obj):
    io.open(path, "w", encoding="utf-8").write(
        json.dumps(obj, ensure_ascii=False, indent=1))


def load(path):
    return json.load(io.open(path, encoding="utf-8"))


def upload(image_path):
    """SSE 스트림에서 assess.done의 candidates만 꺼낸다."""
    boundary = "----gate" + uuid.uuid4().hex
    ctype = mimetypes.guess_type(image_path)[0] or "image/jpeg"

    buf = io.BytesIO()
    buf.write(f"--{boundary}\r\n".encode())
    buf.write(('Content-Disposition: form-data; name="image"; filename="%s"\r\n'
               % os.path.basename(image_path)).encode())
    buf.write(f"Content-Type: {ctype}\r\n\r\n".encode())
    buf.write(io.open(image_path, "rb").read())
    buf.write(f"\r\n--{boundary}--\r\n".encode())

    req = urllib.request.Request(
        BASE + "/api/vision/analyze", data=buf.getvalue(),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST")

    try:
        with urllib.request.urlopen(req, timeout=240) as res:
            name = None
            for raw in res:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.startswith("event:"):
                    name = line[6:].strip()
                elif line.startswith("data:") and name in ("assess.done", "assess.failed"):
                    payload = json.loads(line[5:].strip()).get("payload", {})
                    if name == "assess.failed":
                        return {"error": payload.get("message", "판독 실패")}
                    return payload
    except urllib.error.HTTPError as e:
        return {"error": f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"}
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}
    return {"error": "assess.done 이벤트가 오지 않았다"}


def run(set_path=GATE_SET, out_path=FINDINGS, label="게이트"):
    """세트를 판독한다. 설비를 붙이지 않는다 — 판독 자체만 본다.

    게이트와 평가셋이 같은 코드를 지난다. 판독 경로가 달라지면 두 결과를
    비교할 수 없다."""
    gate = load(set_path)
    out = {}

    for axis in AXES:
        out[axis] = []
        items = gate.get(axis, [])
        for i, item in enumerate(items, 1):
            path = item["path"]
            print(f"  [{axis} {i}/{len(items)}] {os.path.basename(path)[:44]} ...",
                  end=" ", flush=True)
            result = upload(path)
            if "error" in result:
                print(f"실패: {result['error'][:60]}")
                out[axis].append({**item, "error": result["error"]})
                continue
            cands = result.get("candidates", [])
            demo = result.get("demoMode")
            print(f"후보 {len(cands)}건" + (" [데모모드]" if demo else ""))
            out[axis].append({
                **item,
                "assessmentId": result.get("assessmentId"),
                "demoMode": demo,
                "candidates": [
                    {"accidentType": c["accidentType"],
                     "missingControl": c["missingControl"],
                     "evidence": c["evidence"],
                     "riskLevel": c["riskLevel"],
                     "confidence": c.get("confidence")}
                    for c in cands],
            })

    save(out_path, out)
    total = sum(len(v) for v in out.values())
    cands = sum(len(x.get("candidates", [])) for v in out.values() for x in v)
    print(f"\n{label} 판독 {total}장, 후보 {cands}건 → {out_path}")
    print("다음: 각 후보를 사진과 대조해 adjudication.json에 채택/반려/해당없음을 기록할 것")


def score(adj_path=ADJUDICATION, findings_path=FINDINGS, label="게이트"):
    """판정 기록을 읽어 축별 통과를 계산한다. 기준은 규칙 문서 4항.

    평가셋도 **같은 기준**으로 센다. 평가셋에서 기준을 바꾸면 그건 재확인이
    아니라 새 주장이다."""
    if not os.path.exists(adj_path):
        print(f"{adj_path}가 없다. 판정 기록을 먼저 만들 것.")
        sys.exit(1)

    adj = load(adj_path)
    findings = load(findings_path)

    print(f"[{label}]")
    print(f"{'축':8} {'채택':>4} {'반려':>4} {'해당없음':>8} {'반려율':>7} {'채택된 장수':>10}  판정")
    print("-" * 72)

    passed = []
    for axis in AXES:
        verdicts = [v for v in adj if v["axis"] == axis]
        adopt = sum(1 for v in verdicts if v["verdict"] == "ADOPT")
        reject = sum(1 for v in verdicts if v["verdict"] == "REJECT")
        na = sum(1 for v in verdicts if v["verdict"] == "NA")

        decided = adopt + reject
        reject_rate = (reject / decided) if decided else 0.0
        images_with_adopt = len({v["image"] for v in verdicts if v["verdict"] == "ADOPT"})

        ok = images_with_adopt >= 3 and reject_rate <= 0.40

        # PPE 추가 조건: 착용 사진에서 오탐 0건
        note = ""
        if axis == "PPE":
            worn = {os.path.basename(i["path"]) for i in findings["PPE"]
                    if not i.get("expect_finding", True)}
            # 규칙 4항의 조건은 "미착용 후보" 오탐이다. 착용 사진에서 다른 축
            # 후보(예: FALL 난간)가 나온 것은 오탐이 아니다
            false_alarm = sum(1 for v in verdicts
                              if os.path.basename(v["image"]) in worn
                              and "미착용" in v["finding"])
            if false_alarm > 0:
                ok = False
                note = f"착용 사진 오탐 {false_alarm}건"

        print(f"{axis:8} {adopt:4} {reject:4} {na:8} {reject_rate:6.0%} "
              f"{images_with_adopt:10}  {'통과' if ok else '미통과'} {note}")
        if ok:
            passed.append(axis)

    print("-" * 72)
    print(f"통과 축: {len(passed)}개 {passed}")
    if len(passed) >= 3:
        print("\n결정: 통과 — 통과한 축만 사진 판독 모드로 간다")
    elif len(passed) >= 1:
        print("\n결정: 미통과 — 전 축을 체크리스트 모드로 강등한다 (플랜 B)")
    else:
        print("\n결정: 전 축 실패 — 지표를 바꾸고 음성 결과를 성과로 보고한다")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if "--build-sets" in sys.argv:
        build_sets()
    elif "--run" in sys.argv:
        run()
    elif "--run-eval" in sys.argv:
        run(EVAL_SET, EVAL_FINDINGS, "평가셋")
    elif "--score-eval" in sys.argv:
        score(EVAL_ADJUDICATION, EVAL_FINDINGS, "평가셋")
    elif "--score" in sys.argv:
        score()
    else:
        print(__doc__)
