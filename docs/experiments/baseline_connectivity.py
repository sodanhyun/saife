"""
Phase 0 기준선 측정 — 연결성 B1~B5 + 근거 B9~B11 (commit c26861b).

`docs/SAIFE_연결성_개선_프롬프트.md`의 "Phase 0 — 기준선 측정" §3과
`.superpowers/sdd/2026-09-28-connectivity-integration/task-0-brief.md`가 정의를 준다.
나중에 `docs/connectivity-report-YYYYMMDD.md`의 Before 열이 이 스크립트의 출력이다.

측정 방식:
  - B1(기억 도달 클릭 수), B3(첫 화면 정보량)는 **코드 근거**로 산정한다. 프론트
    라우팅·훅 기본값·렌더 컴포넌트를 직접 읽고 근거 파일·줄을 기록한다(브라우저 자동화 없음).
  - B2, B4, B5, B9, B10, B11은 **백엔드에 직접 호출**해 실측한다(uc2_smoke.py /
    uc3_smoke.py와 같은 표준 라이브러리 SSE 파싱 방식).
  - B11은 `HazardAnalysisTools.searchCases`가 만드는 문자열 자체가 "[사진]" 토큰을
    절대 넣지 않는다는 것을 소스에서 확인한 것이라, 런타임 호출로는 재현되지 않는다
    (SSE `ai.tool.done`은 결과 본문을 내보내지 않는다 — `ToolCallTracker.preview()`는
    트레이스 로그용이고 클라이언트로 나가지 않는다). 코드 근거를 결과에 남긴다.

결과 JSON 최상위 필드:
  - `measured_at`: 이 스크립트를 실행한 시각(UTC, ISO 8601) — datetime.now(timezone.utc).
  - `demo_mode`: B2 첫 성공 호출의 ai.token에서 DEMO_MODE_MARKER 유무로 판정한 bool
    (전부 실패하면 None). /actuator/health는 데모/라이브를 구분하지 않으므로 구조적으로
    이렇게 판정한다 — 아래 DEMO_MODE_MARKER 정의 참고.
  - `b2_status`: "OK" 또는 "CONNECTION_FAILED"(B2 10회가 전부 요청 실패일 때 —
    이때 회상 지표의 "0/10"은 측정값이 아니라 측정 불가라는 뜻이다).

백엔드가 SAIFE_BASE_URL에서 아예 응답하지 않으면(연결 거부·타임아웃) 원시 트레이스백
대신 한국어 안내를 출력하고 종료코드 2로 끝난다.

사용법:
  SAIFE_BASE_URL=http://localhost:8081 python baseline_connectivity.py

전제:
  - 백엔드가 데모 모드(키 없음)로 SAIFE_BASE_URL에 떠 있어야 한다.
  - docs/experiments/reset_demo_data.py를 먼저 돌려 시드 상태로 되돌려 둔다.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE = os.environ.get("SAIFE_BASE_URL", "http://localhost:8080")
CHAT_URL = BASE + "/api/agent/chat"

# Phase 0 §3 B2가 지정한 첫 턴 발화 — 이동식 사다리 A(id=1)를 위치 태그로 정확히 매칭한다.
FIRST_TURN_MESSAGE = "공장동 후면 차양부 천장 페인트 작업"
RECALL_SUBSTRINGS = ("미이행", "최근 평가")
URL_PATTERN = re.compile(r"https?://[^\s)\]\"']+")

# 데모 모드 판정 규칙: /actuator/health는 UP만 말하고 데모/라이브 여부를 구분하지 않는다.
# DemoConversationScript.respond()(backend/src/main/java/io/saife/ai/agent/DemoConversationScript.java:76)는
# 데모 응답이면 항상 이 표식으로 시작한다 — "**데모 모드** — API 키가 없어 문장 생성은
# 고정 스크립트를 씁니다. ...". 라이브 모드(Gemini 호출)의 응답에는 이 표식이 없다.
# 그래서 B2의 첫 번째 성공한 채팅 호출의 ai.token 텍스트에서 이 표식 유무로 demo_mode를 판정한다.
DEMO_MODE_MARKER = "**데모 모드**"

# 백엔드가 아예 응답하지 않을 때 잡는 예외 3종 (F2) — HTTPError(4xx/5xx)는 정상 응답이므로 제외한다.
CONNECTION_ERRORS = (urllib.error.URLError, ConnectionRefusedError, TimeoutError)

RESULT_PATH = os.path.join(os.path.dirname(__file__), "baseline_connectivity_result.json")


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


# ---------- HTTP 원시 호출 (상태코드를 그대로 관찰한다 — 404도 결과다) ----------

def get_raw(path, timeout=30):
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, res.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def get_json(path, timeout=30):
    status, body = get_raw(path, timeout=timeout)
    if status != 200:
        return status, None
    try:
        return status, json.loads(body)
    except Exception:
        return status, None


def head_status(url, timeout=10):
    """URL 하나의 HEAD 상태를 본다. 도달 실패와 4xx/5xx를 구분해서 돌려준다(M2)."""
    req = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status
    except urllib.error.HTTPError as e:
        return e.code
    except (urllib.error.URLError, TimeoutError):
        return "unreachable"


def chat_turn(message, conversation_id=None, timeout=120):
    """uc2_smoke.py / uc3_smoke.py와 같은 방식으로 SSE 프레임을 파싱해 이벤트 목록을 돌려준다."""
    body = {"message": message, "conversationId": conversation_id, "slotKey": None}
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


# ---------- B1 / B3 — 코드 근거 산정 (브라우저 없이 소스에서 직접 판정한다) ----------

def measure_b1_b3():
    """
    B1 기억 도달 클릭 수, B3 첫 화면 정보량.

    근거:
      - frontend/src/components/layout/menu.ts: LANDING_PATH = "/work-plan".
        `/` 진입은 즉시 `/work-plan`으로 리다이렉트된다(App.tsx 32행) — 이건
        "화면 이동"이 아니라 진입 자체다.
      - frontend/src/pages/WorkPlan/WorkPlanPage.tsx: 최초 렌더는 ChatThread(빈 대화),
        SlotPrompt(없음), Composer, 빈 WorkPlanTable뿐이다. 설비 이름·등급·미이행이
        어디에도 없다 → B3 = 아니오.
      - frontend/src/components/layout/menu.ts: MENU 4번째 항목이 "설비 타임라인"
        (path=/timeline). 사이드바에서 이 항목 클릭 1회로 이동한다.
      - frontend/src/pages/Timeline/hooks/useTimeline.ts:
        `equipmentId = selectedId ?? equipment.data?.[0]?.id ?? null` — 드롭다운을
        바꾸지 않아도 설비 목록의 **첫 항목이 자동으로 선택**된다.
      - backend EquipmentRepository.findBySiteIdOrderByIdAsc → id 오름차순이므로
        `equipment.data[0]` = id=1 = 이동식 사다리 A (V2__seed_virtual_site.sql 28행).
      - frontend/src/pages/Timeline/components/TimelineSummary.tsx: 이 자동 선택된
        설비의 KpiCell에 "미이행 조치" 라벨과 `summary.unfinishedActionCount` 값이
        **드롭다운 조작 없이** 렌더된다.
      → 코드 구조상 최소 클릭 수는 "드롭다운 변경까지 2"가 아니라 **"사이드바에서
        설비 타임라인 클릭 1회"**다. 단, 이 사실이 실제로 성립하려면 시드 상태에서
        equipment id=1의 unfinishedActionCount > 0이어야 한다 — 이건 실측 값이므로
        아래에서 B4 측정과 같은 호출로 함께 확인한다(`live_check`).
    """
    status, data = get_json("/api/dashboard/equipment/1/timeline")
    live_check = {
        "endpoint": "/api/dashboard/equipment/1/timeline",
        "http_status": status,
        "unfinishedActionCount": (data or {}).get("summary", {}).get("unfinishedActionCount")
                                  if data else None,
    }
    b1_min_clicks = None
    b1_note = None
    if data and data.get("summary", {}).get("unfinishedActionCount", 0) > 0:
        b1_min_clicks = 1
        b1_note = ("사이드바 '설비 타임라인' 클릭 1회 → useTimeline 기본 선택(id=1, 이동식 사다리 A)"
                    " → TimelineSummary가 드롭다운 조작 없이 '미이행 조치' 수치를 보여준다. "
                    "(연결성 프롬프트 Phase 0가 가정한 '드롭다운 변경 포함 2 이상'과 다르다 — "
                    "코드 근거로 재산정)")
    else:
        b1_min_clicks = 2
        b1_note = ("id=1 기본 선택만으로는 미이행 조치가 보이지 않는다(실측 unfinishedActionCount="
                    f"{live_check['unfinishedActionCount']}). 드롭다운을 사다리 A로 바꿔야 하므로 "
                    "진입 후 최소 2회 화면 이동(클릭)이 필요하다.")

    return {
        "B1_기억_도달_클릭_수": {
            "정의": "앱 진입(/)부터 '이동식 사다리 A에 미이행 조치가 있다'가 화면 문자열로 나타날 때까지 최소 화면 이동 수",
            "값": b1_min_clicks,
            "근거": [
                "frontend/src/components/layout/menu.ts:18 LANDING_PATH=/work-plan",
                "frontend/src/App.tsx:32 '/'→LANDING_PATH 리다이렉트",
                "frontend/src/components/layout/menu.ts:11-16 MENU 4번째 항목 /timeline",
                "frontend/src/pages/Timeline/hooks/useTimeline.ts:9-10 selectedId ?? equipment.data[0]?.id",
                "backend/.../EquipmentRepository.java:21 findBySiteIdOrderByIdAsc",
                "backend/.../V2__seed_virtual_site.sql:28 equipment id=1 = 이동식 사다리 A",
                "frontend/src/pages/Timeline/components/TimelineSummary.tsx:17 '미이행 조치' KpiCell",
            ],
            "실측_확인": live_check,
            "설명": b1_note,
        },
        "B3_첫화면_정보량": {
            "정의": "/(=/work-plan) 최초 렌더에서 설비 이름·등급·미이행이 하나라도 보이는가",
            "값": "아니오",
            "근거": [
                "frontend/src/pages/WorkPlan/WorkPlanPage.tsx:29-50 — 최초 렌더 구성 요소는 "
                "빈 ChatThread, (조건부)SlotPrompt, Composer, 빈 WorkPlanTable뿐. "
                "설비명·등급·미이행 텍스트가 대화 시작 전에는 어디에도 없다.",
            ],
        },
    }


# ---------- B2 / B9 / B10 — 데모 모드 첫 턴 10회 ----------

def measure_b2_b9_b10():
    section("[B2/B9/B10] 데모 모드 첫 턴 10회 — POST /api/agent/chat")

    per_call = []
    demo_mode = None  # 첫 성공한 호출의 ai.token에서 DEMO_MODE_MARKER 유무로 판정한다
    for i in range(1, 11):
        try:
            events = chat_turn(FIRST_TURN_MESSAGE, conversation_id=None)
        except Exception as e:
            print(f"  {i:2d}. 요청 실패: {type(e).__name__} {e}")
            per_call.append({"idx": i, "request_failed": True, "error": str(e)})
            continue

        token_text = "".join(str(e.get("payload")) for e in events if e.get("type") == "ai.token")
        if demo_mode is None:
            demo_mode = DEMO_MODE_MARKER in token_text
        recall_events = sum(1 for e in events if e.get("type") == "ai.recall")
        evidence_events = sum(1 for e in events if e.get("type") == "ai.evidence")
        error_events = [e for e in events if e.get("type") == "ai.error"]
        substr_hit = any(s in token_text for s in RECALL_SUBSTRINGS)
        urls = URL_PATTERN.findall(token_text)

        print(f"  {i:2d}. substr_hit={substr_hit} ai.recall={recall_events} "
              f"ai.evidence={evidence_events} urls={len(urls)} "
              f"error={'있음' if error_events else '없음'}")

        per_call.append({
            "idx": i,
            "substr_hit": substr_hit,
            "ai_recall_events": recall_events,
            "ai_evidence_events": evidence_events,
            "urls_found": urls,
            "error": error_events[0] if error_events else None,
            "token_text_preview": token_text[:300],
        })

    n = len(per_call)
    substr_hits = sum(1 for c in per_call if c.get("substr_hit"))
    recall_total = sum(c.get("ai_recall_events", 0) for c in per_call)
    evidence_total = sum(c.get("ai_evidence_events", 0) for c in per_call)
    all_urls = [u for c in per_call for u in c.get("urls_found", [])]

    # M1 — 10회 전부 요청 자체가 실패했다면 "회상 0/10"과 구분해야 한다.
    # 전자는 "측정 불가"고 후자는 "측정했더니 0"이라 의미가 다르다.
    # 2026-09-29 fix(C Task 6a): 이전엔 `"error" in c`로 판정했는데, 성공한 호출의
    # per_call 딕셔너리에도 항상 "error" 키가 있다(값은 None) — 그래서 매번 참이 되어
    # 전부 성공해도 CONNECTION_FAILED로 잘못 보고했다(실측). 요청 자체가 실패했을
    # 때만 붙는 "request_failed" 키로 판정한다.
    all_failed = n > 0 and all(c.get("request_failed") for c in per_call)
    b2_status = "CONNECTION_FAILED" if all_failed else "OK"
    if all_failed:
        print("  경고: B2 10회 호출이 전부 실패했다 — 아래 0/10 값은 '측정된 0'이 아니라 "
              "'측정 불가'다. b2_status=CONNECTION_FAILED로 표시한다.")

    if all_urls:
        url_checks = [(u, head_status(u)) for u in all_urls]
        ok = sum(1 for _, s in url_checks if s == 200)
        b10_value = f"{ok}/{len(url_checks)}"
        b10_detail = url_checks
    else:
        b10_value = "n/a"
        b10_detail = "ai.token 텍스트에서 http(s) URL이 한 건도 발견되지 않았다 (0/%d회)" % n

    return {
        "demo_mode": demo_mode,
        "b2_status": b2_status,
        "B2_회상_보장률": {
            "정의": "POST /api/agent/chat에 데모 모드 첫 턴을 10회 보내 "
                    "(a) 첫 assistant 토큰 묶음에 '미이행' 또는 '최근 평가' 포함 비율, "
                    "(b) 구조화된 ai.recall 이벤트 발행 수",
            "첫턴_문자열_포함률": f"{substr_hits}/{n}",
            "ai_recall_이벤트": f"{recall_total}/{n}",
            "설명": "실측 결과 0/10 — 놀랍게도 텍스트 포함률도 0이다. 원인: 연결성 프롬프트가 "
                    "지정한 첫 턴 문구('공장동 후면 차양부 천장 페인트 작업')는 설비명을 포함하지 "
                    "않는다. EquipmentMatcher는 위치 태그로 공정('표면처리 라인')까지는 찾지만, "
                    "그 공정에 설비가 2건(이동식 사다리 A / 도장 부스 1호) 있어 설비명 단서 없이는 "
                    "Jaro-Winkler 점수가 SUGGEST_THRESHOLD(0.72) 미만으로 떨어져 'unmatched'로 "
                    "떨어진다 — 즉 회상은커녕 설비 매칭 자체가 실패한다(도구 결과: "
                    "'등록되지 않은 설비입니다'). 설비명을 포함한 문구("
                    "'…이동식 사다리에서 천장 페인트 작업')로 별도 확인한 결과 매칭은 성공하고 "
                    "'미이행 조치'·'최근 평가 등급' 문구가 정상적으로 나타난다 — 데모 파이프라인 "
                    "자체는 정상이며, 문제는 프롬프트가 지정한 문구가 이 매칭기의 약점을 그대로 "
                    "드러낸다는 점이다. 구조화 이벤트(ai.recall)는 프롬프트 문구와 무관하게 "
                    "코드에 아예 존재하지 않는다(grep 결과 0건) — 모델 문장과 무관하게 뜨는 "
                    "회상 카드가 아직 없다는 뜻.",
        },
        "B9_근거_첨부_턴수": {
            "정의": "데모 모드 첫 턴 10회 중 ai.evidence 이벤트 발행 수",
            "값": f"{evidence_total}/{n}",
            "설명": "ai.evidence 이벤트 타입이 백엔드 어디에도 없다(grep 0건, "
                    "backend/src/main/java/io/saife 전체). A1~A3 근거 검색 계층은 "
                    "이 커밋에 존재하지만 SSE·에이전트 루프에 아직 연결되지 않았다.",
        },
        "B10_응답내_URL_유효율": {
            "정의": "ai.token 텍스트에서 http(s) URL을 뽑아 HEAD 200 비율",
            "값": b10_value,
            "세부": b10_detail,
        },
        "_raw_per_call": per_call,
    }


# ---------- B4 — 설비 6개 타임라인 ----------

def measure_b4():
    section("[B4] GET /api/dashboard/equipment/{1..6}/timeline")
    rows = []
    with_incident = 0
    for eq_id in range(1, 7):
        status, data = get_json(f"/api/dashboard/equipment/{eq_id}/timeline")
        incident_count = None
        if data:
            incident_count = data.get("summary", {}).get("incidentCount")
            if incident_count and incident_count > 0:
                with_incident += 1
        print(f"  설비 {eq_id}: HTTP {status} incidentCount={incident_count}")
        rows.append({"equipmentId": eq_id, "http_status": status, "incidentCount": incident_count})

    return {
        "B4_시드_이야기_완결_설비수": {
            "정의": "GET /api/dashboard/equipment/{1..6}/timeline에서 incidentCount>0인 설비 수",
            "값": with_incident,
            "세부": rows,
        }
    }


# ---------- B5 — 홈/인박스 엔드포인트 존재 여부 ----------

def measure_b5():
    section("[B5] GET /api/dashboard/today, /api/dashboard/equipment/cards")
    endpoints = ["/api/dashboard/today", "/api/dashboard/equipment/cards"]
    rows = []
    for ep in endpoints:
        status, body = get_raw(ep)
        print(f"  {ep}: HTTP {status} {body[:200]}")
        rows.append({"endpoint": ep, "http_status": status, "body": body[:500]})

    return {
        "B5_홈_인박스_존재여부": {
            "정의": "/api/dashboard/today, /api/dashboard/equipment/cards 호출 결과 "
                    "(프롬프트는 404를 가정하지만 실측값을 그대로 기록한다)",
            "값": rows,
            "설명": "두 엔드포인트 모두 404가 아니라 HTTP 500(errorType=INTERNAL)을 반환했다. "
                    "GlobalExceptionHandler.java의 @ExceptionHandler(Exception.class) 캐치올이 "
                    "Spring의 미매핑 경로 예외까지 500으로 감싸는 것으로 보인다(엔드포인트 미구현은 "
                    "맞지만 상태코드는 404가 아니라 500 — Phase 1에서 신규 엔드포인트를 추가하면 "
                    "자연히 사라질 값이므로 백엔드를 고치지 않고 실측값 그대로 기록한다).",
        }
    }


# ---------- B11 — 유사 사례 사진 비율 (코드 근거) ----------

def measure_b11():
    """
    B11 유사 사례 사진 비율: searchCases 결과 텍스트에 "[사진]" 표기 비율.

    HazardAnalysisTools.searchCases()가 만드는 문자열은
    `keyword`(또는 contents) + "(업종)"만 이어붙인다 — "[사진]" 토큰을 넣는 코드
    경로가 아예 없다(grep 0건). 게다가 SSE `ai.tool.done`은 결과 본문을 내보내지
    않으므로(ToolCallTracker.java payload에 result 없음) 이 도구의 실제 반환
    문자열은 HTTP로 관찰할 수 없다 — 소스 코드가 유일하게 신뢰 가능한 관찰 지점이다.
    """
    return {
        "B11_유사사례_사진비율": {
            "정의": "searchCases 결과 텍스트에서 '[사진]' 표기 비율",
            "값": "0",
            "근거": [
                "backend/src/main/java/io/saife/ai/tools/HazardAnalysisTools.java:127-134 "
                "searchCases()가 만드는 문자열은 keyword/contents + business만 포함한다",
                "'[사진]' 문자열 리터럴이 backend 전체에 존재하지 않는다 (grep 0건)",
                "backend/src/main/java/io/saife/ai/tools/ToolCallTracker.java:81-89 "
                "ai.tool.done payload에는 toolName/callOrder/success/durationMs만 있고 "
                "결과 본문(result)은 클라이언트로 나가지 않는다 — 런타임 재현 불가, 코드 근거로 대체",
            ],
        }
    }


def main():
    print(f"BASE = {BASE}")

    # F1 — 측정 시각을 기계가 읽을 수 있는 형태로 남긴다. 이 값은 실행 시점 기준이고,
    # 결과 JSON을 나중에 손으로 보정한 값(예: 커밋 저자 시각)과는 출처가 다르다.
    measured_at = datetime.now(timezone.utc).isoformat()

    result = {
        "measured_at": measured_at,
        "base_commit": "c26861b", "base_url": BASE, "measured_at_note":
              "post-A3 검색 계층(리랭크·RRF)이 있으나 UI/에이전트 루프에 미연결된 시점. "
              "연결성 지표(B1~B5)는 A1~A3 변경의 영향을 받지 않는다.",
    }

    section("[B1/B3] 코드 근거 산정")
    b1_b3 = measure_b1_b3()
    for k, v in b1_b3.items():
        print(f"  {k}: {v.get('값')}")
    result.update(b1_b3)

    result.update(measure_b2_b9_b10())
    result.update(measure_b4())
    result.update(measure_b5())
    result.update(measure_b11())

    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    section("요약")
    print(f"demo_mode                  = {result['demo_mode']}")
    print(f"b2_status                  = {result['b2_status']}")
    print(f"B1 기억 도달 클릭 수        = {result['B1_기억_도달_클릭_수']['값']}")
    print(f"B2 회상 보장률(텍스트)      = {result['B2_회상_보장률']['첫턴_문자열_포함률']}")
    print(f"B2 회상 보장률(ai.recall)   = {result['B2_회상_보장률']['ai_recall_이벤트']}")
    print(f"B3 첫 화면 정보량           = {result['B3_첫화면_정보량']['값']}")
    print(f"B4 시드 이야기 완결 설비수  = {result['B4_시드_이야기_완결_설비수']['값']}")
    print(f"B5 홈/인박스 존재여부       = {result['B5_홈_인박스_존재여부']['값']}")
    print(f"B9 근거 첨부 턴수           = {result['B9_근거_첨부_턴수']['값']}")
    print(f"B10 응답내 URL 유효율       = {result['B10_응답내_URL_유효율']['값']}")
    print(f"B11 유사사례 사진비율       = {result['B11_유사사례_사진비율']['값']}")
    print(f"\n결과 저장: {RESULT_PATH}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except CONNECTION_ERRORS:
        # F2 — 백엔드가 아예 떠 있지 않을 때 원시 트레이스백 대신 조치를 알려준다.
        print(f"백엔드가 {BASE}에서 응답하지 않습니다. README의 기동 절차를 먼저 실행하세요.")
        sys.exit(2)
