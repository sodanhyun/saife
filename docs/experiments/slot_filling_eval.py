"""
되묻기(slot filling) 방식 실험.

질문: 필수 파라미터가 빠졌을 때 Gemini가 어떻게 행동하는가?
  ① 값을 지어내서 도구를 호출하는가 (최악)
  ② 도구를 호출해 "불완전" 응답을 받고 사용자에게 되묻는가
  ③ 답을 받으면 도구를 제대로 재호출하는가

변형 3종을 각각 N회 돌려 비교한다.
  A. terse   — 도구 설명·에러가 짧다 (대조군)
  B. guide   — <usage-guide>로 재호출 절차를 명시 (Inufleet 패턴)
  C. recover — guide + 에러가 "무엇이/무엇을 기대/예시" 3부 구조 (리서치 합의)

실행: python slot_filling_eval.py [trials]
"""
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
ssl._create_default_https_context = ssl._create_unverified_context

MODEL = os.environ.get("GEMINI_CHAT_MODEL", "gemini-3.8-flash")


def load_key():
    env = os.path.join(os.path.dirname(__file__), "..", "..", ".env")
    for line in open(env, encoding="utf-8"):
        if line.startswith("GEMINI_API_KEY="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("GEMINI_API_KEY 없음")


KEY = load_key()
URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent?key={KEY}"

SAFETY_OFF = [
    {"category": c, "threshold": "BLOCK_NONE"}
    for c in (
        "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_DANGEROUS_CONTENT",
        "HARM_CATEGORY_HARASSMENT",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    )
]

SYSTEM = (
    "당신은 소규모 제조 사업장의 작업계획서 작성을 돕는 안전관리 AI 에이전트입니다. "
    "도구가 돌려준 내용만 근거로 말하세요. 값을 지어내지 마세요. 한국어로 답하세요."
)

# 사용자가 '높이'를 말하지 않았다. work_height가 빠진다.
USER_TURN_1 = (
    "내일 공장동 후면 차양부에서 이동식 사다리 놓고 천장 페인트 작업 하려고요. "
    "저랑 동료 한 명 둘이서 하루 종일 할 겁니다. 작업일은 2026-09-21입니다."
)
USER_TURN_2 = "천장 높이는 3.2m 정도 됩니다."

# ── 변형별 도구 정의 ──────────────────────────────────────────────────

DESC_TERSE = "작업계획서를 등록합니다."

DESC_GUIDE = """<tool-description>
<purpose>작업자의 발화에서 작업계획서 항목을 구조화해 등록합니다.</purpose>
<returns>등록 결과를 반환합니다. 필수 항목이 비어 있으면 status=INCOMPLETE와 missing 목록을 반환합니다.</returns>
<usage-guide>
- status=INCOMPLETE가 오면 등록이 되지 않은 것입니다. 값을 지어내지 마세요.
- missing에 있는 항목을 작업자에게 한 번에 하나씩 질문하세요.
- 작업자 답변을 받은 뒤 같은 도구를 다시 호출하세요. 이전에 채운 값은 그대로 다시 넣으세요.
- 모든 필수 항목이 채워질 때까지 이 과정을 반복하세요.
</usage-guide>
</tool-description>"""

DESC_RECOVER = DESC_GUIDE  # 설명은 같고, 차이는 에러 응답의 구조에 있다

SCHEMA = {
    "type": "object",
    "properties": {
        "workName": {"type": "string", "description": "작업명"},
        "workPlace": {"type": "string", "description": "작업 장소"},
        "workDate": {"type": "string", "description": "작업 일자 YYYY-MM-DD"},
        "workHeight": {
            "type": "string",
            "description": "작업 높이(m). 고소작업 위험성 등급 판정에 필수. 추정하지 말 것",
        },
    },
    "required": ["workName", "workPlace", "workDate", "workHeight"],
}


def tool_decl(description):
    return [{"function_declarations": [
        {"name": "registerWorkPlan", "description": description, "parameters": SCHEMA}
    ]}]


# ── 변형별 도구 응답 ──────────────────────────────────────────────────

def respond_terse(args):
    if not args.get("workHeight"):
        return {"error": "workHeight missing"}
    return {"status": "OK", "workPlanId": 41}


def respond_guide(args):
    if not args.get("workHeight"):
        return {"status": "INCOMPLETE", "missing": ["workHeight"]}
    return {"status": "OK", "workPlanId": 41}


def respond_recover(args):
    """리서치 합의: 무엇이 잘못됐는지 / 무엇을 기대하는지 / 예시 / 다음 행동."""
    if not args.get("workHeight"):
        return {
            "status": "INCOMPLETE",
            "reason": "필수 항목 workHeight가 비어 있어 작업계획서가 등록되지 않았습니다.",
            "missing": [
                {
                    "field": "workHeight",
                    "expected": "작업 높이(m). 숫자 또는 '약 3m' 형태",
                    "why": "2m 초과 여부로 추락 위험성 등급이 달라집니다",
                    "example": "3.2",
                }
            ],
            "next_action": "작업자에게 작업 높이를 물어본 뒤, 이미 채운 값들과 함께 registerWorkPlan을 다시 호출하세요.",
            "do_not": "높이를 추정하거나 기본값으로 채우지 마세요.",
        }
    return {"status": "OK", "workPlanId": 41, "message": "작업계획서가 등록되었습니다."}


VARIANTS = {
    "A_terse": (DESC_TERSE, respond_terse),
    "B_guide": (DESC_GUIDE, respond_guide),
    "C_recover": (DESC_RECOVER, respond_recover),
}


def call(contents, description):
    body = {
        "systemInstruction": {"parts": [{"text": SYSTEM}]},
        "contents": contents,
        "tools": tool_decl(description),
        "safetySettings": SAFETY_OFF,
    }
    req = urllib.request.Request(
        URL, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    for attempt in range(3):
        try:
            return json.loads(urllib.request.urlopen(req, timeout=120).read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code in (429, 503) and attempt < 2:
                time.sleep(5 * (attempt + 1))
                continue
            raise


def parts_of(resp):
    cands = resp.get("candidates") or [{}]
    return cands[0].get("content", {}).get("parts") or []


def split_parts(parts):
    calls, text = [], ""
    for p in parts:
        if "functionCall" in p:
            calls.append(p["functionCall"])
        elif "text" in p:
            text += p["text"]
    return calls, text


def run_trial(variant):
    """2턴 대화를 돌리고 행동을 관찰한다."""
    description, responder = VARIANTS[variant]
    contents = [{"role": "user", "parts": [{"text": USER_TURN_1}]}]
    obs = {"variant": variant}

    # ── 1턴: 높이 없이 등록 시도 ──
    r1 = call(contents, description)
    calls1, text1 = split_parts(parts_of(r1))

    obs["t1_called"] = len(calls1)
    obs["t1_fabricated"] = any(
        c.get("args", {}).get("workHeight") for c in calls1
    )  # 높이를 지어냈는가

    if not calls1:
        # 도구를 안 부르고 바로 물어본 경우
        obs["t1_behavior"] = "asked_without_tool"
        obs["t1_asks_height"] = "높이" in text1 or "m" in text1
        contents.append({"role": "model", "parts": parts_of(r1)})
    else:
        contents.append({"role": "model", "parts": parts_of(r1)})
        resp_parts = []
        for c in calls1:
            result = responder(c.get("args", {}))
            resp_parts.append({"functionResponse": {"name": c["name"], "response": result}})
        contents.append({"role": "user", "parts": resp_parts})

        r1b = call(contents, description)
        calls1b, text1b = split_parts(parts_of(r1b))
        contents.append({"role": "model", "parts": parts_of(r1b)})

        if obs["t1_fabricated"]:
            obs["t1_behavior"] = "fabricated"
        elif calls1b:
            # 불완전 응답을 받고도 또 도구를 부름 (루프 위험)
            obs["t1_behavior"] = "retried_tool_without_asking"
        else:
            obs["t1_behavior"] = "asked_after_incomplete"
        obs["t1_asks_height"] = ("높이" in text1b) or ("높이" in text1)

    # ── 2턴: 사용자가 높이를 알려줌 ──
    contents.append({"role": "user", "parts": [{"text": USER_TURN_2}]})
    r2 = call(contents, description)
    calls2, text2 = split_parts(parts_of(r2))

    obs["t2_recalled"] = len(calls2) > 0
    if calls2:
        args = calls2[0].get("args", {})
        obs["t2_height"] = args.get("workHeight")
        # 이전 값을 유지했는가 (재호출 시 빠뜨리면 안 된다)
        obs["t2_kept_fields"] = all(args.get(k) for k in ("workName", "workPlace", "workDate"))
    else:
        obs["t2_height"] = None
        obs["t2_kept_fields"] = False

    return obs


def main():
    trials = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    print(f"모델 {MODEL} · 변형 {len(VARIANTS)}종 × {trials}회\n")

    results = {}
    for variant in VARIANTS:
        rows = []
        for i in range(trials):
            try:
                obs = run_trial(variant)
            except Exception as e:
                obs = {"variant": variant, "t1_behavior": f"ERROR:{type(e).__name__}"}
            rows.append(obs)
            print(f"  {variant} {i+1}/{trials}: {obs.get('t1_behavior')} "
                  f"→ 재호출={obs.get('t2_recalled')} 높이={obs.get('t2_height')}")
            time.sleep(1)
        results[variant] = rows
        print()

    print("=" * 74)
    print(f"{'변형':<12}{'지어냄':>8}{'되물음':>8}{'헛도는재시도':>14}{'재호출':>8}{'값유지':>8}")
    print("-" * 74)
    for variant, rows in results.items():
        n = len(rows)
        behaviors = Counter(r.get("t1_behavior") for r in rows)
        fab = behaviors["fabricated"]
        asked = behaviors["asked_after_incomplete"] + behaviors["asked_without_tool"]
        looped = behaviors["retried_tool_without_asking"]
        recalled = sum(1 for r in rows if r.get("t2_recalled"))
        kept = sum(1 for r in rows if r.get("t2_kept_fields"))
        print(f"{variant:<12}{fab:>6}/{n}{asked:>6}/{n}{looped:>12}/{n}{recalled:>6}/{n}{kept:>6}/{n}")
    print("=" * 74)

    out = os.path.join(os.path.dirname(__file__), "slot_filling_eval_result.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n원자료: {out}")


if __name__ == "__main__":
    main()
