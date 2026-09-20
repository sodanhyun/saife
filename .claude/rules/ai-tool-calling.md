---
globs: ["backend/**/ai/**/*.java"]
---

# Gemini 도구 호출 규칙

Inufleet(레스토랑 로봇 관제 플랫폼)에서 실제로 터진 문제들의 대응을 이식했다.
`ai/config/`의 클래스들은 장식이 아니다. **Spring AI 자동설정으로 되돌리지 말 것.**

## 손대면 안 되는 클래스

| 클래스 | 막고 있는 문제 |
|--------|---------------|
| `SafeCandidateGoogleGenAiChatModel` | Spring AI 1.1.5 라이브러리 NSEE 버그 2건 — ① `candidate.content()/parts()` empty일 때 `Optional.get()` ② `response.modelVersion().get()`. **도구 다중 라운드에서 청크가 늘면 빈발한다** |
| `FuzzyToolCallingManager` | Gemini가 도구명을 환각한다 (camelCase로 등록했는데 snake_case로 호출). 등록된 정식 이름으로 정규화해 위임 |
| `ToolCallingConfig` | 도구 **예외도 JSON으로 감싸야** 한다. `parseJsonToMap()`이 JSON을 강제하므로 plain text를 던지면 `JsonParseException` |
| `GeminiClientConfig` | HTTP 타임아웃 180초. **SDK 재시도는 끈다** — 재시도 제어권을 앱 계층에 일원화 |
| `GeminiSafetySettings` | 아래 |

Spring AI 버전을 올리기 전에 **위 버그들이 상위 버전에서 고쳐졌는지 먼저 확인**하고,
고쳐졌으면 해당 우회만 제거한다. 버전만 올리고 우회를 남겨두면 이중 방어라 무해하지만,
버전을 올리면서 우회를 지우면 회귀한다.

## ⚠️ 안전 필터는 반드시 꺼둔다

`GeminiSafetySettings.SAFETY_SETTINGS_OFF`를 모든 호출에 적용한다.

SAIFE가 다루는 정상 입력은 산재 사고 서술이다:

> "설비 내 슬러지 제거 작업 중 설비가 갑자기 가동되어 스크류에 끼임"
> "지붕교체 공사 작업 중 선라이트가 파손되면서 약 7m 아래로 추락"
> "4단으로 적재된 곡물 톤백 하적단이 갑자기 무너지며 깔림"

이 문장들은 `HARM_CATEGORY_DANGEROUS_CONTENT`에 걸린다. 필터를 켜두면
**사례 검색이 예외 없이 조용히 빈 결과를 반환**한다. 디버깅이 매우 어렵다.

## 도구 작성 규칙

### 1. 반환값은 반드시 `ToolResult.of()`로 감싼다

```java
return ToolResult.of("설비 3건을 찾았습니다. …");   // {"result":"…"}
```

plain text를 반환하면 Gemini 어댑터가 파싱에 실패한다.

### 2. `ToolCallTracker.execute()`로 감싼다

```java
return ToolCallTracker.execute("findLocationEquipment",
        Map.of("query", query), sseService, toolContext, () -> {
    // 실제 로직
});
```

감싸지 않으면 **트레이스 패널에 해당 도구가 점등되지 않는다.** 시연이 여기 걸려 있다.

### 3. `@Tool` 설명은 3블록 구조로 쓴다

```java
@Tool(description = """
        <tool-description>
        <purpose>장소나 설비를 찾고, 그 설비의 기존 위험요인·조치 이력을 함께 반환합니다.</purpose>
        <returns>설비 ID, 명칭, 위치, 최근 평가 등급, 미이행 조치 목록을 반환합니다.</returns>
        <prerequisites>없음. 대화의 첫 도구로 사용하세요.</prerequisites>
        </tool-description>
        """)
```

`<prerequisites>`가 도구 체이닝을 유도한다. 이게 없으면 Gemini가 순서를 뒤집는다.

### 4. 파라미터 null을 항상 가정한다

**Gemini는 파라미터 없이 도구를 호출하는 경우가 있다.** 레코드 파라미터가 통째로 null로 온다.

```java
Long inputId = request != null ? request.equipmentId() : null;
```

### 5. 도구 개수는 6종을 유지한다

늘리는 건 자유지만 **프로젝터에서 읽히는 줄 수**가 상한이다. 시연 트레이스 패널은
큰 글씨 6줄 기준으로 설계했다. 도구를 추가할 거면 패널 레이아웃을 같이 본다.

## 등급 판정은 모델이 하지 않는다

AI는 **후보 제안과 문안 생성만** 한다. 위험성 등급은 `assessment_hazard.risk_level`이고,
그 값은 **룰 엔진**이 낸다. 판정 근거는 `rule_trace`에 문자열로 남겨 화면에 그대로 띄운다.

무대에서 모델이 흔들려도 등급은 흔들리지 않아야 하고, 심사위원이
"이 등급은 AI가 정한 겁니까"라고 물으면 룰 테이블을 보여줄 수 있어야 한다.

## 모델 선택

- 기본: `gemini-2.5-flash` (`GEMINI_CHAT_MODEL` 환경변수로 교체 가능)
- 무대에서는 **라이브 호출**이 기본이다. 네트워크 장애 시에만 `SAIFE_DEMO_MODE=true`로
  픽스처 폴백으로 전환한다. 전환 절차도 리허설 대상이다
- 도구는 항상 **로컬 캐시**에 대해 실행한다. 외부 공공 API 라이브 호출은 무대에서 0
