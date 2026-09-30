"""
UC3 시연 경로 스모크 테스트.

실제 백엔드에 붙어 대화 한 건을 끝까지 돌린다.
확인할 것:
  1. 도구가 순차 점등되는가 (트레이스 패널이 살아 있는가)
  2. 에이전트가 미이행 조치를 먼저 말하는가 (데이터 코어 소환)
  3. 필수 항목이 비면 되묻는가
  4. 답을 주면 재호출하고 브리핑까지 가는가

실행:
  python uc3_smoke.py
  SAIFE_BASE_URL=http://localhost:8081 python uc3_smoke.py
"""
import json
import os
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = os.environ.get("SAIFE_BASE_URL", "http://localhost:8080") + "/api/agent/chat"


def turn(message, conversation_id=None, slot_key=None):
    body = {"message": message, "conversationId": conversation_id, "slotKey": slot_key}
    req = urllib.request.Request(
        BASE,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
    )

    tools, answer, slot, err = [], "", None, None
    cid = conversation_id

    with urllib.request.urlopen(req, timeout=300) as r:
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
                if t == "ai.tool.start":
                    print(f"     ● {p['callOrder']}. {p['toolName']} …")
                elif t == "ai.tool.done":
                    mark = "OK  " if p.get("success") else "FAIL"
                    print(f"     ● {p['callOrder']}. {p['toolName']} {mark} {p.get('durationMs')}ms")
                    tools.append((p["toolName"], p.get("success")))
                elif t == "ai.slot.request":
                    slot = p
                elif t == "ai.token":
                    answer += str(p)
                elif t == "ai.error":
                    err = p
    return {"cid": cid, "tools": tools, "answer": answer, "slot": slot, "error": err}


SCRIPT = [
    ("작업자",
     "내일 공장동 후면 차양부에서 이동식 사다리 놓고 천장 페인트 작업 하려고요. "
     "저랑 동료 한 명 둘이서 하루 종일 할 겁니다. 작업일은 2026-09-21입니다."),
    ("작업자", "천장 높이는 3.2m 정도 됩니다."),
    ("작업자", "안전대 부착설비는 아직 없습니다."),
    ("작업자", "제품은 ○○ 유성페인트 씁니다."),
    ("작업자", "네, 작업계획서 등록해 주세요."),
]


def main():
    cid = None
    all_tools = []
    for idx, (_, msg) in enumerate(SCRIPT, 1):
        print(f"\n{'='*72}\n[턴 {idx}] 작업자: {msg}\n{'-'*72}")
        try:
            r = turn(msg, cid)
        except Exception as e:
            print(f"  요청 실패: {type(e).__name__} {e}")
            break
        cid = r["cid"]
        all_tools += r["tools"]

        if r["error"]:
            print("  [오류]", r["error"])
        if r["slot"]:
            print(f"  ⏸ UI힌트: slotKey={r['slot'].get('slotKey')}")
        if r["answer"]:
            print("\n  [에이전트]")
            for line in r["answer"].strip().split("\n"):
                print("   ", line)

    print(f"\n{'='*72}")
    print(f"대화 ID: {cid}")
    print(f"총 도구 호출: {len(all_tools)}건")
    names = [t for t, _ in all_tools]
    for n in dict.fromkeys(names):
        ok = sum(1 for t, s in all_tools if t == n and s)
        tot = sum(1 for t, _ in all_tools if t == n)
        print(f"  - {n}: {ok}/{tot}")
    distinct = len(set(names))
    print(f"고유 도구 {distinct}/6종 발화")


if __name__ == "__main__":
    main()
