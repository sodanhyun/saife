"""
Phase 2-1 검증 — 회상 카드가 데모 모드에서 100% 뜨는지 실측한다.

`docs/SAIFE_연결성_개선_프롬프트.md` "### 2-3 검증"과
`.superpowers/sdd/2026-09-28-connectivity-integration/task-2-brief.md` Step 4가 정의를 준다.

측정 방식:
  - 데모 모드 첫 턴 10회. 매번 새 대화(conversationId=None)로 시작하고, 요청 본문에
    `equipmentId=1`을 함께 싣는다.
  - **왜 equipmentId가 필요한가**: `baseline_connectivity.py`의 B2 실측이 이미 기록해
    뒀듯이 "공장동 후면 차양부 천장 페인트 작업"은 그 공정(표면처리 라인)에 설비가
    2건(이동식 사다리 A / 도장 부스 1호) 있어 자유 텍스트 유사도만으로는 못 가른다
    (Jaro-Winkler 점수가 SUGGEST_THRESHOLD 미만으로 떨어져 unmatched가 된다). Task 2b가
    추가한 `equipmentId` 단축 경로(AgentContextKeys.EQUIPMENT_ID)가 없으면 이 스모크는
    10/10을 낼 수 없다 — 그래서 이 스크립트 자체가 그 경로의 존재 증명이다.
  - 각 호출에서 확인하는 것:
    1) `ai.recall` 이벤트가 정확히 1건 오는지, payload의 unfinishedActions가 1건
       이상이고 knownSlots에 "미이행 조치"가 포함되는지
    2) 첫 assistant 토큰 묶음(ai.token, 데모 모드는 한 번에 전문이 온다)이 그
       `ai.recall`의 headline으로 시작하는지
    3) 이벤트 순서 — `ai.recall`이 `ai.tool.done`보다 먼저 오는지(Review Focus 1).
       `ai.evidence`가 있으면(B1 Task 2 이후) 전부 `ai.token`보다 먼저 오는지도 함께
       본다 — 이 저장소 시점(B1 Task 2 이전)에는 `ai.evidence`가 아예 발행되지 않아
       공집합으로 통과한다. 이후 B1이 들어오면 실제로 검증되기 시작한다.

`--live` 옵션: 로컬 환경에 GEMINI_API_KEY가 있을 때만 5회를 라이브 모드로 추가
측정한다. 키 값 자체는 절대 출력하지 않는다 — 존재 여부만 본다.

사용법:
  SAIFE_BASE_URL=http://localhost:8081 python recall_smoke.py [--live]

전제:
  - 백엔드가 데모 모드(키 없음)로 SAIFE_BASE_URL에 떠 있어야 한다.
  - docs/experiments/reset_demo_data.py를 먼저 돌려 시드 상태로 되돌려 둔다.
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("SAIFE_BASE_URL", "http://localhost:8080")
CHAT_URL = BASE + "/api/agent/chat"

# baseline_connectivity.py의 FIRST_TURN_MESSAGE와 동일 — B2가 지정한 문구다.
FIRST_TURN_MESSAGE = "공장동 후면 차양부 천장 페인트 작업"
ENTRY_EQUIPMENT_ID = 1


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def chat_turn(message, conversation_id=None, equipment_id=None, timeout=120):
    """uc2_smoke.py / baseline_connectivity.py와 같은 방식으로 SSE 프레임을 파싱한다."""
    body = {"message": message, "conversationId": conversation_id, "slotKey": None}
    if equipment_id is not None:
        body["equipmentId"] = equipment_id
    req = urllib.request.Request(
        CHAT_URL,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    events = []
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
                    events.append(json.loads(line[5:].strip()))
                except Exception:
                    continue
    return events


def run_batch(label, count, equipment_id):
    section(f"[{label}] 첫 턴 {count}회 — equipmentId={equipment_id}")

    recall_ok = 0
    unfinished_ok = 0
    known_slots_ok = 0
    first_token_ok = 0
    order_ok = 0
    evidence_order_ok = 0
    request_failed = 0
    details = []

    for i in range(1, count + 1):
        try:
            events = chat_turn(FIRST_TURN_MESSAGE, conversation_id=None, equipment_id=equipment_id)
        except Exception as e:
            print(f"  {i:2d}. 요청 실패: {type(e).__name__} {e}")
            request_failed += 1
            details.append({"idx": i, "error": str(e)})
            continue

        types = [e.get("type") for e in events]
        recall_events = [e for e in events if e.get("type") == "ai.recall"]
        tool_done_idx = next((idx for idx, t in enumerate(types) if t == "ai.tool.done"), None)
        recall_idx = next((idx for idx, t in enumerate(types) if t == "ai.recall"), None)
        evidence_idxs = [idx for idx, t in enumerate(types) if t == "ai.evidence"]
        token_idxs = [idx for idx, t in enumerate(types) if t == "ai.token"]

        token_text = "".join(str(e.get("payload")) for e in events if e.get("type") == "ai.token")

        has_recall = len(recall_events) == 1
        payload = recall_events[0].get("payload") if recall_events else None
        unfinished = payload.get("unfinishedActions") if isinstance(payload, dict) else None
        known_slots = payload.get("knownSlots") if isinstance(payload, dict) else None
        headline = payload.get("headline") if isinstance(payload, dict) else None

        unfinished_hit = bool(unfinished) and len(unfinished) >= 1
        slots_hit = bool(known_slots) and ("미이행 조치" in known_slots)
        token_hit = bool(headline) and token_text.strip().startswith(headline.strip())

        order_hit = (recall_idx is not None and tool_done_idx is not None and recall_idx < tool_done_idx)
        # ai.evidence가 하나라도 있으면 전부 어떤 ai.token보다 먼저 와야 한다. 공집합이면 통과.
        evidence_order_hit = True
        if evidence_idxs:
            evidence_order_hit = all(any(ei < ti for ti in token_idxs) for ei in evidence_idxs)

        if has_recall:
            recall_ok += 1
        if unfinished_hit:
            unfinished_ok += 1
        if slots_hit:
            known_slots_ok += 1
        if token_hit:
            first_token_ok += 1
        if order_hit:
            order_ok += 1
        if evidence_order_hit:
            evidence_order_ok += 1

        print(f"  {i:2d}. ai.recall={len(recall_events)} unfinished_hit={unfinished_hit} "
              f"slots_hit={slots_hit} token_hit={token_hit} order_hit={order_hit} "
              f"evidence_order_hit={evidence_order_hit}")

        if not (has_recall and unfinished_hit and slots_hit and token_hit and order_hit
                and evidence_order_hit):
            details.append({
                "idx": i,
                "types": types,
                "has_recall": has_recall,
                "unfinished_hit": unfinished_hit,
                "slots_hit": slots_hit,
                "token_hit": token_hit,
                "order_hit": order_hit,
                "evidence_order_hit": evidence_order_hit,
                "headline": headline,
                "token_text_preview": token_text[:300],
            })

    n = count
    print()
    print(f"  ai.recall 발행(정확히 1건)  = {recall_ok}/{n}")
    print(f"  unfinishedActions>=1       = {unfinished_ok}/{n}")
    print(f"  knownSlots에 '미이행 조치' = {known_slots_ok}/{n}")
    print(f"  첫 토큰이 회상으로 시작    = {first_token_ok}/{n}")
    print(f"  순서(ai.recall<ai.tool.done) = {order_ok}/{n}")
    print(f"  순서(ai.evidence<ai.token)   = {evidence_order_ok}/{n}")
    if request_failed:
        print(f"  요청 실패                  = {request_failed}/{n}")

    return {
        "n": n,
        "request_failed": request_failed,
        "ai_recall": f"{recall_ok}/{n}",
        "unfinished_actions": f"{unfinished_ok}/{n}",
        "known_slots": f"{known_slots_ok}/{n}",
        "first_token_recall": f"{first_token_ok}/{n}",
        "order_recall_before_tool_done": f"{order_ok}/{n}",
        "order_evidence_before_token": f"{evidence_order_ok}/{n}",
        "details": details,
    }


def main():
    live = "--live" in sys.argv

    demo_result = run_batch("데모 모드", 10, ENTRY_EQUIPMENT_ID)
    result = {"demo": demo_result}

    if live:
        if not os.environ.get("GEMINI_API_KEY"):
            print()
            print("[--live] 로컬 환경에 GEMINI_API_KEY가 없어 라이브 측정을 건너뜁니다.")
            result["live"] = "skipped (no local GEMINI_API_KEY)"
        else:
            live_result = run_batch("라이브 모드", 5, ENTRY_EQUIPMENT_ID)
            result["live"] = live_result

    section("요약")
    summary = {}
    for key, value in result.items():
        if isinstance(value, dict):
            summary[key] = {k: v for k, v in value.items() if k != "details"}
        else:
            summary[key] = value
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    demo_pass = (
        demo_result["ai_recall"] == "10/10"
        and demo_result["unfinished_actions"] == "10/10"
        and demo_result["known_slots"] == "10/10"
        and demo_result["first_token_recall"] == "10/10"
        and demo_result["order_recall_before_tool_done"] == "10/10"
        and demo_result["order_evidence_before_token"] == "10/10"
    )

    if not demo_pass:
        section("실패")
        print("데모 모드 10/10 목표 미달. 세부 실패:")
        print(json.dumps(demo_result["details"], ensure_ascii=False, indent=2))
        sys.exit(1)

    section("PASS")
    print("데모 모드 10/10 전부 통과.")


if __name__ == "__main__":
    main()
