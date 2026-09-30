"""
근거(RAG) 스모크 — B1/B2 근거 계층이 실제 API 위에서 끝까지 동작하는지 검증한다.

`docs/SAIFE_연결성_개선_프롬프트.md`/`.superpowers/sdd/2026-09-28-evidence-b2-frontend-surfaces/
task-5-brief.md`가 정의를 준다. `baseline_connectivity.py`가 남긴 B9~B11의 "이전"(전부 0/n·0/10)
값에 대응하는 "이후" 값을 같은 정의로 재측정해 이 스크립트의 결과 JSON에 남긴다.

측정 방식(모두 표준 라이브러리 `urllib`만 사용 — uc2_smoke.py/uc3_smoke.py/
baseline_connectivity.py/recall_smoke.py와 동일):

  A) 데모 모드 단일 턴 10회. `recall_smoke.py`처럼 매번 새 대화(conversationId=None)로
     시작하고 `equipmentId=1` 단축 경로를 함께 싣는다. 단, 메시지 자체에 필수 슬롯
     (작업일·높이·안전대·제품명)을 전부 채워 넣어 **한 턴 안에서** 장소 확인 →
     항목 구조화 → 근거 수집(analyzeHazards·searchCases·getMsds) → 제출까지
     끝나게 만든다(`DemoConversationScript.respond()`가 필수 슬롯이 모두 채워지면
     같은 호출 안에서 바로 4)근거 수집 5)제출까지 진행하는 것을 이용— 실측으로
     확인했다). 이게 B9("데모 모드 첫 턴 10회 중 ai.evidence 발행 수")의 "이후" 값이다.
  B) UC3 고전 5턴 대화(`uc3_smoke.py`의 SCRIPT 그대로) 한 건. 되묻기를 거쳐 마지막에
     `createWorkPlan`이 성공하는 흐름 — `ai.evidence`가 실제로는 어느 턴에서
     뜨는지, `work-plan` 상세에 근거가 붙는지, transcript 복원이 라이브와 같은
     번호를 돌려주는지, [#n] 인용이 원장 번호를 가리키는지를 검증한다.
  C) A+B에서 모은 모든 카드의 sourceUrl/mediaUrl/thumbnailUrl을 분류해 백엔드
     프록시 경로(`/api/...`)만 실제로 받아온다 — 외부 URL(law.go.kr·kosha.or.kr 등)은
     "외부, 미검증"으로만 집계하고 절대 호출하지 않는다(무대 원칙 — 외부 API 라이브
     호출 0, `.claude/rules/deployment.md`).
  D) `/api/system/status`로 데모 모드·임베딩 가용성·근거 청크 수를 확인한다.

실행:
  cd backend && ./gradlew bootRun --args="--server.port=8081 --spring.datasource.url=jdbc:postgresql://localhost:5433/saife"
  (GEMINI_API_KEY 없이 — 데모 모드로 기동)
  python docs/experiments/reset_demo_data.py
  SAIFE_BASE_URL=http://localhost:8081 python docs/experiments/evidence_smoke.py

전제: 백엔드가 데모 모드로 SAIFE_BASE_URL에 떠 있고, reset_demo_data.py로 시드 상태 복원.
"""
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE = os.environ.get("SAIFE_BASE_URL", "http://localhost:8080")
CHAT_URL = BASE + "/api/agent/chat"
RESULT_PATH = os.path.join(os.path.dirname(__file__), "evidence_smoke_result.json")
REPO_ROOT = os.path.join(os.path.dirname(__file__), "..", "..")


def _detect_base_commit():
    """현재 측정이 실제로 돈 커밋을 런타임에 계산한다.

    fix round 1 — 하드코딩된 값은 hotfix가 쌓일 때마다 매번 손으로 갱신해야 하고,
    잊으면 결과 JSON의 base_commit이 실제 측정 대상과 어긋난 채로 남는다(실측:
    이 스크립트를 만든 시점의 커밋이 26ba5e4였는데 그 뒤 hotfix 6건이 더 쌓였다).
    `SAIFE_BASE_COMMIT` 환경변수가 있으면 그걸 우선한다(CI·의도적 고정용).
    """
    env = os.environ.get("SAIFE_BASE_COMMIT")
    if env:
        return env
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=REPO_ROOT, capture_output=True, text=True, timeout=10)
        sha = out.stdout.strip()
        return sha if out.returncode == 0 and sha else "unknown"
    except Exception:
        return "unknown"


BASE_COMMIT = _detect_base_commit()

# 백엔드가 아예 응답하지 않을 때(baseline_connectivity.py와 같은 F2 처리)
CONNECTION_ERRORS = (urllib.error.URLError, ConnectionRefusedError, TimeoutError)

CITE_PATTERN = re.compile(r"\[\s*#\s*(\d+)\s*\]")


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


# ---------- HTTP 원시 호출 ----------

def fetch(path, timeout=20):
    """경로 하나를 GET한다. (status, body_bytes, content_type). 백엔드 프록시 경로 전용."""
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, res.read(), res.headers.get("Content-Type")
    except urllib.error.HTTPError as e:
        body = e.read() if e.fp else b""
        ctype = e.headers.get("Content-Type") if e.headers else None
        return e.code, body, ctype


def fetch_head(path, timeout=20):
    """경로 하나를 HEAD한다. (status, content_type). 바이트를 받지 않는다 — B10 대량 검증용.

    fix round 1: 카드 URL 여러 건을 매번 GET(전체 바이트 다운로드)하던 것을 HEAD로
    바꿨다. 상태·콘텐츠타입만 확인하면 충분한데 GET은 사진 바이트까지 매번 받아온다.
    """
    req = urllib.request.Request(BASE + path, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, res.headers.get("Content-Type")
    except urllib.error.HTTPError as e:
        ctype = e.headers.get("Content-Type") if e.headers else None
        return e.code, ctype


def get_json(path, timeout=30):
    status, body, _ = fetch(path, timeout=timeout)
    if status != 200:
        return status, None
    try:
        return status, json.loads(body.decode("utf-8"))
    except Exception:
        return status, None


def chat_turn(message, conversation_id=None, equipment_id=None, slot_key=None, timeout=120):
    """uc2_smoke.py/uc3_smoke.py/recall_smoke.py와 같은 방식으로 SSE 프레임을 파싱한다."""
    body = {"message": message, "conversationId": conversation_id, "slotKey": slot_key}
    if equipment_id is not None:
        body["equipmentId"] = equipment_id
    req = urllib.request.Request(
        CHAT_URL,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    events = []
    cid = conversation_id
    with urllib.request.urlopen(req, timeout=timeout) as r:
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
                events.append(e)
    return events, cid


def classify_and_check(url, full_get=False):
    """카드 URL 하나를 분류하고, 백엔드 프록시 경로면 상태·콘텐츠타입을 확인한다.

    fix round 1 — B10은 기본으로 HEAD만 쓴다(status + content-type). 실제 바이트
    (사진이 ≥1KB인지)는 `full_get=True`로 지정한 카드 1건에서만 전체 GET을 쓴다
    (`run_media_checks`가 사진 카드 1건을 골라 넘긴다). 외부 URL은 절대 호출하지
    않는다(무대 원칙 — 외부 API 라이브 호출 0).
    """
    if not url:
        return None
    if url.startswith("/api/"):
        if full_get:
            status, body, ctype = fetch(url)
            size = len(body)
        else:
            status, ctype = fetch_head(url)
            size = None
        ok = (status == 200 and ctype is not None
              and (ctype.startswith("image/") or ctype.startswith("application/pdf")))
        return {"url": url, "kind": "internal", "status": status,
                "content_type": ctype, "size": size, "ok": ok,
                "method": "GET" if full_get else "HEAD"}
    if url.startswith("http://") or url.startswith("https://"):
        return {"url": url, "kind": "external", "ok": None}
    return {"url": url, "kind": "unknown", "ok": False}


# ---------- A) 데모 모드 단일 턴 10회 — B9 ----------

# 한 메시지 안에 장소(equipmentId 단축 경로로 대체)·작업일·높이·안전대·제품명을 전부 채워
# DemoConversationScript.respond()가 같은 호출 안에서 근거 수집·제출까지 끝내게 만든다.
# (실측 확인: TYPES에 ai.tool.start*6, ai.evidence, ai.token, ai.done 순으로 한 번에 옴)
SINGLE_TURN_TEMPLATE = (
    "내일 공장동 후면 차양부에서 이동식 사다리 놓고 천장 페인트 작업 하려고요. "
    "작업일은 {date}입니다. 천장 높이는 3.2m 정도 됩니다. "
    "안전대 부착설비는 아직 없습니다. 제품은 OO 유성페인트 씁니다."
)


def order_ok(types):
    """ai.evidence(있다면)가 첫 ai.token보다 먼저, 그리고 ai.done보다 먼저 오는지."""
    ev_idx = [i for i, t in enumerate(types) if t == "ai.evidence"]
    if not ev_idx:
        return True  # 근거가 없으면 순서 위반도 없다 — 이 경우는 별도로 has_evidence=False로 잡힌다
    tok_idx = [i for i, t in enumerate(types) if t == "ai.token"]
    done_idx = next((i for i, t in enumerate(types) if t == "ai.done"), None)
    before_token = (not tok_idx) or all(e < min(tok_idx) for e in ev_idx)
    before_done = (done_idx is None) or all(e < done_idx for e in ev_idx)
    return before_token and before_done


def run_b9():
    section("[B9] 데모 모드 단일 턴 10회 — equipmentId=1, 근거 완결 메시지")
    results = []
    all_cards = []
    for i in range(1, 11):
        msg = SINGLE_TURN_TEMPLATE.format(date=f"2026-11-{i:02d}")
        try:
            events, cid = chat_turn(msg, conversation_id=None, equipment_id=1)
        except Exception as e:
            print(f"  {i:2d}. 요청 실패: {type(e).__name__} {e}")
            results.append({"idx": i, "error": str(e), "has_evidence": False,
                             "unique_ok": False, "starts_at_1": False, "order_ok": False})
            continue

        types = [e.get("type") for e in events]
        ev_events = [e for e in events if e.get("type") == "ai.evidence"]
        cards = ev_events[0]["payload"] if ev_events else []
        nos = [c["no"] for c in cards]

        unique_ok = len(nos) == len(set(nos))
        starts_at_1 = bool(nos) and sorted(nos) == list(range(1, len(nos) + 1))
        ok_order = order_ok(types)
        has_evidence = len(cards) > 0

        all_cards.extend(cards)
        print(f"  {i:2d}. cards={len(cards):2d} unique={unique_ok} "
              f"1부터_연속={starts_at_1} 순서(ev<token/done)={ok_order}")

        results.append({
            "idx": i, "cid": cid, "card_count": len(cards), "nos": nos,
            "has_evidence": has_evidence, "unique_ok": unique_ok,
            "starts_at_1": starts_at_1, "order_ok": ok_order,
        })

    n = len(results)
    with_evidence = sum(1 for r in results if r.get("has_evidence"))
    summary = {
        "n": n,
        "with_evidence": with_evidence,
        "b9": f"{with_evidence}/{n}",
        "all_unique_within_turn": all(r.get("unique_ok") for r in results),
        "all_start_at_1_contiguous": all(r.get("starts_at_1") for r in results if r.get("has_evidence")),
        "all_order_ok": all(r.get("order_ok") for r in results),
        "details": results,
    }
    print()
    print(f"  B9 (근거 첨부 턴수) = {summary['b9']}")
    return summary, all_cards


# ---------- B) UC3 고전 5턴 대화 — 근거 등장 턴, work-plan 근거, transcript 복원, 인용 ----------

UC3_SCRIPT = [
    "내일 공장동 후면 차양부에서 이동식 사다리 놓고 천장 페인트 작업 하려고요. "
    "저랑 동료 한 명 둘이서 하루 종일 할 겁니다. 작업일은 {date}입니다.",
    "천장 높이는 3.2m 정도 됩니다.",
    "안전대 부착설비는 아직 없습니다.",
    "제품은 ○○ 유성페인트 씁니다.",
    "네, 작업계획서 등록해 주세요.",
]


def run_uc3(date="2026-10-07"):
    section("[UC3] 전체 대화 5턴 — 근거 등장 턴 · work-plan 근거 · transcript 복원 · 인용")
    cid = None
    per_turn_nos = []
    per_turn_text = []
    all_cards = []

    for i, template in enumerate(UC3_SCRIPT, 1):
        msg = template.format(date=date) if "{date}" in template else template
        events, cid = chat_turn(msg, conversation_id=cid)
        ev_events = [e for e in events if e.get("type") == "ai.evidence"]
        cards = ev_events[0]["payload"] if ev_events else []
        nos = sorted(c["no"] for c in cards)
        tok = "".join(str(e.get("payload")) for e in events if e.get("type") == "ai.token")
        per_turn_nos.append(nos)
        per_turn_text.append(tok)
        all_cards.extend(cards)
        print(f"  턴 {i}: evidence_nos={nos} 텍스트={tok[:70]!r}")

    # workPlanId — "제출 완료 [id=N]"을 우선으로, 없으면 마지막 [id=N] 언급을 쓴다
    workplan_id = None
    for text in reversed(per_turn_text):
        m = re.search(r"제출 완료 \[id=(\d+)\]", text)
        if m:
            workplan_id = int(m.group(1))
            break
    if workplan_id is None:
        for text in reversed(per_turn_text):
            m = re.search(r"\[id=(\d+)\]", text)
            if m:
                workplan_id = int(m.group(1))
                break

    # work-plan 상세 — evidence 필드 존재 여부만 감지한다(B1 Task4 여부와 무관하게 실패시키지 않음)
    detail_status = None
    has_evidence_field = None
    evidence_count = None
    if workplan_id is not None:
        detail_status, detail = get_json(f"/api/work-plan/{workplan_id}")
        if isinstance(detail, dict):
            has_evidence_field = "evidence" in detail
            if has_evidence_field:
                evidence_count = len(detail.get("evidence") or [])

    # transcript 복원 — k번째 assistant 줄이 그 턴의 라이브 근거 번호와 같아야 한다
    transcript_nos = None
    transcript_match = None
    transcript_detail = None
    if cid:
        t_status, transcript = get_json(f"/api/agent/{cid}/transcript")
        if isinstance(transcript, list):
            assistant_lines = [l for l in transcript if l.get("role") == "assistant"]
            transcript_nos = [sorted(e["no"] for e in (l.get("evidence") or [])) for l in assistant_lines]
            transcript_match = transcript_nos == per_turn_nos
            transcript_detail = {"live_per_turn": per_turn_nos, "transcript_per_turn": transcript_nos}

    # 인용 [#n] — 그 턴까지 알려진 번호만 참조하는지
    known = set()
    cited_all = []
    citation_valid = True
    for nos, text in zip(per_turn_nos, per_turn_text):
        known |= set(nos)
        cited = [int(n) for n in CITE_PATTERN.findall(text)]
        cited_all.extend(cited)
        if any(c not in known for c in cited):
            citation_valid = False

    result = {
        "conversation_id": cid,
        "workplan_id": workplan_id,
        "per_turn_evidence_nos": per_turn_nos,
        "evidence_appeared_on_turn": next((i + 1 for i, nos in enumerate(per_turn_nos) if nos), None),
        "workplan_detail_status": detail_status,
        "workplan_has_evidence_field": has_evidence_field,
        "workplan_evidence_count": evidence_count,
        "transcript_match": transcript_match,
        "transcript_detail": transcript_detail,
        "cited_numbers": cited_all,
        "citation_valid": citation_valid,
    }
    print()
    print(f"  workPlanId = {workplan_id}, 근거가 등장한 턴 = {result['evidence_appeared_on_turn']}")
    print(f"  work-plan.evidence 필드 존재 = {has_evidence_field} (개수={evidence_count})")
    print(f"  transcript 근거 번호 == 라이브 근거 번호 = {transcript_match}")
    if transcript_match is False:
        print(f"    라이브(턴별)      = {per_turn_nos}")
        print(f"    transcript(줄별)  = {transcript_nos}")
    print(f"  인용 [#n] 발견 {len(cited_all)}건, 알려진 번호만 참조 = {citation_valid}")
    return result, all_cards


# ---------- C) 미디어 URL 검증 (B10) + 사진 비율 (B11) ----------

def run_media_checks(all_cards):
    section("[B10/B11] 카드 미디어 URL 검증(HEAD) + 사진 비율(자연 대화, STRICT)")
    checked_by_url = {}
    external_by_url = {}

    # B11에서 쓸 사진 카드를 먼저 골라둔다 — 이 중 내부(/api/) thumbnailUrl 1건만
    # 실제 바이트(GET)로 ≥1KB를 확인한다. 나머지 카드는 전부 HEAD만 쓴다.
    case_cards_preview = [c for c in all_cards if str(c.get("kind", "")).startswith("CASE")]
    photo_cards_preview = [c for c in case_cards_preview if c.get("thumbnailUrl")]
    photo_bytes_url = next(
        (c["thumbnailUrl"] for c in photo_cards_preview
         if str(c.get("thumbnailUrl", "")).startswith("/api/")), None)

    for c in all_cards:
        for field in ("sourceUrl", "mediaUrl", "thumbnailUrl"):
            url = c.get(field)
            if not url:
                continue
            full_get = (field == "thumbnailUrl" and url == photo_bytes_url)
            res = classify_and_check(url, full_get=full_get)
            if res is None:
                continue
            entry = dict(res, field=field, evidence_no=c.get("no"), kind=c.get("kind"))
            if res["kind"] == "internal":
                checked_by_url.setdefault(url, entry)
            elif res["kind"] == "external":
                external_by_url.setdefault(url, entry)

    checked = list(checked_by_url.values())
    ok = sum(1 for r in checked if r["ok"])
    b10 = f"{ok}/{len(checked)}" if checked else "n/a"

    print(f"  내부(/api/) URL {len(checked)}건 중 200+올바른 콘텐츠타입(HEAD 기준) {ok}건")
    for r in checked:
        mark = "OK  " if r["ok"] else "FAIL"
        size_note = f" size={r['size']}" if r.get("size") is not None else ""
        print(f"    {mark} [field={r['field']} method={r['method']}] {r['url']} "
              f"status={r['status']} ctype={r['content_type']}{size_note}")
    print(f"  외부 URL {len(external_by_url)}건 — 무대 원칙상 호출하지 않음(외부, 미검증)")

    photo_bytes_check = None
    if photo_bytes_url and photo_bytes_url in checked_by_url:
        entry = checked_by_url[photo_bytes_url]
        photo_bytes_check = {
            "url": photo_bytes_url, "size": entry.get("size"),
            "ok": bool(entry.get("size")) and entry["size"] >= 1024,
        }
        print(f"  사진 바이트 샘플 검증(GET 1건, ≥1KB) = {photo_bytes_check}")

    # B11 — CASE 카드 중 thumbnailUrl 보유 비율(사진 비율). fix round 1: STRICT —
    # photo_card_count > 0이 그대로 pass/fail이다(자연 대화 기준, DB 직접 조회로 대체하지 않는다).
    case_cards = case_cards_preview
    photo_cards = photo_cards_preview
    b11 = f"{len(photo_cards)}/{len(case_cards)}" if case_cards else "n/a"
    print(f"  B11 (유사사례 사진비율, 자연 대화 기준, STRICT) = {b11}")
    print("  [사진] 마커 카운트: SSE ai.tool.done 페이로드는 toolName/callOrder/success/durationMs만"
          " 갖고 결과 본문을 내보내지 않는다(ToolCallTracker.java) — 런타임 관찰 불가."
          " 대신 카드의 kind/thumbnailUrl로 photo 여부를 판정했다(위 B11).")

    return {
        "b10": b10, "checked_count": len(checked), "checked_ok": ok,
        "external_count": len(external_by_url),
        "checked_urls": checked, "external_urls": list(external_by_url.values()),
        "photo_bytes_check": photo_bytes_check,
        "b11_photo_ratio": b11, "case_card_count": len(case_cards), "photo_card_count": len(photo_cards),
    }


def find_photo_case_id():
    """자연 대화에서 사진 카드가 0건일 때, 프록시 메커니즘 자체는 별도로 검증하기 위해
    DB에서 사진 있는 사례 ID 하나를 직접 가져온다(reset_demo_data.py와 같은
    docker exec + psql 패턴). 컨테이너 이름은 reset_demo_data.py와 같이
    `SAIFE_PG_CONTAINER`(기본 `saife-postgres`)를 따른다 — 격리 스택을 검증할 때는
    그 스택의 postgres 컨테이너 이름을 준다. 조회가 실패하면 None을 돌려준다. 이 함수의
    결과는 진단(diagnostics.direct_proxy)일 뿐 B11 pass/fail에 영향을 주지 않는다."""
    try:
        out = subprocess.run(
            ["docker", "exec", "-i", os.environ.get("SAIFE_PG_CONTAINER", "saife-postgres"), "psql", "-U", "saife", "-d", "saife",
             "-t", "-A", "-c",
             "SELECT ref_id FROM evidence_chunk WHERE kind='CASE_FATALITY' "
             "AND metadata->>'hasImage'='true' LIMIT 1;"],
            capture_output=True, text=True, timeout=15)
        lines = [l.strip() for l in out.stdout.strip().splitlines() if l.strip()]
        return int(lines[0]) if lines else None
    except Exception:
        return None


def verify_photo_proxy_directly():
    case_id = find_photo_case_id()
    if case_id is None:
        return {"available": False, "reason": "DB 조회 실패 또는 사진 있는 사례를 찾지 못함"}
    status, body, ctype = fetch(f"/api/media/case/{case_id}/photo?w=320")
    ok = status == 200 and bool(ctype) and ctype.startswith("image/") and len(body) >= 1024
    return {"available": True, "case_id": case_id, "status": status,
            "content_type": ctype, "size": len(body), "ok": ok}


# ---------- D) 시스템 상태 ----------

def run_system_status():
    section("[시스템 상태] GET /api/system/status")
    status, data = get_json("/api/system/status")
    print(f"  status={status} body={data}")
    return status, data


def main():
    section(f"BASE = {BASE}")
    measured_at = datetime.now(timezone.utc).isoformat()
    checks = []

    def check(name, passed, note=""):
        checks.append((name, passed, note))
        print(("PASS" if passed else "FAIL"), name, note)

    status_code, sysinfo = run_system_status()
    check("system.status HTTP 200", status_code == 200, str(status_code))
    check("system.status demoMode=true", bool(sysinfo) and sysinfo.get("demoMode") is True, str(sysinfo))
    check("system.status embeddingAvailable=false", bool(sysinfo) and sysinfo.get("embeddingAvailable") is False, "")
    evidence_chunk_count = sysinfo.get("evidenceChunkCount") if sysinfo else None
    check("system.status evidenceChunkCount>15000", bool(evidence_chunk_count) and evidence_chunk_count > 15000,
          str(evidence_chunk_count))

    b9_result, b9_cards = run_b9()
    check("B9 = 10/10 근거 첨부 턴수", b9_result["with_evidence"] == b9_result["n"] == 10, b9_result["b9"])
    check("카드 번호 턴 내 유일", b9_result["all_unique_within_turn"], "")
    check("새 대화 번호 1부터 연속", b9_result["all_start_at_1_contiguous"], "")
    check("ai.evidence가 ai.token/ai.done보다 먼저 도착", b9_result["all_order_ok"], "")

    uc3_result, uc3_cards = run_uc3()
    check("UC3 완주 — workPlanId 확보", uc3_result["workplan_id"] is not None, str(uc3_result["workplan_id"]))
    check("work-plan 상세 evidence 필드 감지(정보성 — 부재해도 실패 아님)", True,
          f"present={uc3_result['workplan_has_evidence_field']} count={uc3_result['workplan_evidence_count']}")
    # C Task 6a — B1 T4(work-plan.evidence 저장) 착지 이후로는 정보성이 아니라 STRICT.
    # UC3 대화가 근거를 실제로 만들었다면(evidence_appeared_on_turn is not None) work-plan
    # 상세의 evidence 배열도 비어 있으면 안 된다 — 대화 근거와 저장된 근거가 같은 것이어야 한다.
    if uc3_result["evidence_appeared_on_turn"] is not None:
        check("work-plan.evidence 비어있지 않음 (B1 T4)",
              bool(uc3_result["workplan_has_evidence_field"]) and (uc3_result["workplan_evidence_count"] or 0) > 0,
              f"count={uc3_result['workplan_evidence_count']}")
    check("transcript 근거 번호 == 라이브 근거 번호",
          uc3_result["transcript_match"] is True,
          "" if uc3_result["transcript_match"] else "불일치 — 아래 report의 원인 분석 참고")
    check("인용 [#n]이 알려진 번호만 참조(0건이면 정보성 통과)",
          uc3_result["citation_valid"],
          f"cited={uc3_result['cited_numbers']}")

    all_cards = b9_cards + uc3_cards
    media_result = run_media_checks(all_cards)
    check("B10 내부 미디어 URL 200 + 올바른 콘텐츠타입",
          media_result["checked_count"] == 0 or media_result["checked_ok"] == media_result["checked_count"],
          media_result["b10"])

    # fix round 1 — STRICT: 자연 대화의 photo_card_count > 0이 그대로 pass/fail이다.
    # verify_photo_proxy_directly()는 진단용으로만 남긴다(diagnostics.direct_proxy) —
    # 그 결과가 무엇이든 이 체크의 pass/fail을 절대 뒤집지 않는다. fix round 1(2):
    # STRICT 체크가 통과하면 이 진단은 아예 돌리지 않는다 — 불필요한 docker exec/DB 조회다.
    natural_photo_ok = media_result["photo_card_count"] > 0
    if natural_photo_ok:
        direct_proxy = None
        photo_check_note = f"자연 대화에서 사진 카드 {media_result['photo_card_count']}건 확보 (STRICT PASS)"
    else:
        direct_proxy = verify_photo_proxy_directly()
        photo_check_note = (
            "STRICT FAIL — 자연 대화에서 사진 카드(photo_card_count)가 0건이다. "
            f"진단용 diagnostics.direct_proxy={direct_proxy} (saife-postgres 컨테이너 가정, "
            "pass/fail에는 영향 없음 — 위 find_photo_case_id() 문서 참고)"
        )
    check("사진 카드(자연 대화, photo_card_count > 0) — STRICT", natural_photo_ok, photo_check_note)

    ok = all(c[1] for c in checks)

    result = {
        "measured_at": measured_at,
        "base_commit": BASE_COMMIT,
        "base_url": BASE,
        "system_status": sysinfo,
        "b9_single_turn": b9_result,
        "uc3_conversation": uc3_result,
        "media_checks": media_result,
        # fix round 1 — SSE ai.tool.done 페이로드는 toolName/callOrder/success/durationMs만 갖고
        # 도구 결과 본문(텍스트)을 내보내지 않는다(ToolCallTracker.java). "[사진]" 마커 등장 여부를
        # 런타임에서 직접 셀 방법이 없다 — 그래서 이 필드는 측정값이 아니라 그 사실 자체를 남긴다.
        "photo_marker_check": "n/a — ai.tool.done payload carries no result text",
        # diagnostics.* — pass/fail에 절대 영향을 주지 않는 진단 정보만 모아둔다.
        "diagnostics": {"direct_proxy": direct_proxy},
        "checks": [{"name": n, "passed": p, "note": note} for n, p, note in checks],
        "overall_pass": ok,
        "summary": {
            "B9_근거_첨부_턴수": b9_result["b9"],
            "B10_카드_미디어_URL_유효율": media_result["b10"],
            "B10_외부_URL_미검증_건수": media_result["external_count"],
            "B11_유사사례_사진비율": media_result["b11_photo_ratio"],
        },
    }

    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)

    section("요약")
    for name, passed, note in checks:
        print(("PASS" if passed else "FAIL"), name, note)
    print()
    print(f"B9  (근거 첨부 턴수)         = {result['summary']['B9_근거_첨부_턴수']}")
    print(f"B10 (카드 미디어 URL 유효율) = {result['summary']['B10_카드_미디어_URL_유효율']} "
          f"(외부 미검증 {result['summary']['B10_외부_URL_미검증_건수']}건)")
    print(f"B11 (유사사례 사진비율)      = {result['summary']['B11_유사사례_사진비율']}")
    print()
    print(f"결과 저장: {RESULT_PATH}")

    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except CONNECTION_ERRORS:
        print(f"백엔드가 {BASE}에서 응답하지 않습니다. README의 기동 절차를 먼저 실행하세요.")
        sys.exit(2)
