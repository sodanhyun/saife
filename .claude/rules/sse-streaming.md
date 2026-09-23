---
globs: ["frontend/src/types/sse.ts", "frontend/src/hooks/useAgentStream.ts", "frontend/src/components/ToolTracePanel.tsx", "backend/**/service/SseService.java", "backend/**/ai/**/*.java"]
---

# SSE 통합 프로토콜 규칙

## 통합 봉투 포맷

모든 SSE 엔드포인트가 동일한 봉투를 사용한다:

```
SSE event: <domain.action>
SSE id:    <seq>
SSE data:  { type, correlationId, targetId, seq, ts, payload }
```

| 필드 | 설명 |
|------|------|
| `type` | `domain.action` 형태 (예: `ai.tool.start`, `assess.done`, `ai.token`) |
| `correlationId` | SSE 세션 식별 |
| `targetId` | 세션 내 대상 식별 (도메인별 대상 식별자, 없으면 null) |
| `seq` | emitter별 단조 증가 정수 (SseService 자동 부여) |
| `ts` | ISO-8601 발행 시각 (SseService 자동 부여) |
| `payload` | 도메인별 데이터 (시그널 이벤트는 null) |

## 이벤트 네이밍 규칙

`domain.action` 형태를 사용한다. 플랫 이름 (`token`, `done`) 사용 금지.

| 도메인 | 이벤트 예시 |
|--------|-----------|
| `ai.*` | `ai.token`, `ai.done`, `ai.error`, `ai.tool.start`, `ai.tool.done`, **`ai.slot.request`** |
| `assess.*` | `assess.progress`, `assess.done`, `assess.failed` — 사진 판독(비전) 잡 |
| `batch.*` | `batch.progress`, `batch.done`, `batch.error` |
| `system.*` | `system.heartbeat`, `system.keepalive` |

## ⚠️ `ai.slot.request` — 스트림 중간에 멈추는 이벤트

SAIFE의 되묻기 턴은 **스트림 도중 사용자 입력을 기다리며 멈춘다.** 예약된 4종(`ai.token`,
`ai.done`, `ai.error`, `ai.tool`)은 전부 "서버가 계속 진행한다"를 전제하므로 여기에 맞는 게 없다.

```
SSE event: ai.slot.request
SSE data:  { type, correlationId, targetId, seq, ts,
             payload: { slotKey, question, options?, ledgerValue? } }
```

- `targetId` = 대화 세션 ID (`conversation.id`)
- `ledgerValue` — 데이터 코어가 이미 알고 있는 값. 있으면 프론트가 "확인" UI로, 없으면 "입력" UI로 그린다

**이건 이벤트 1건이 아니라 3건짜리 작업이다.** 착수 전에 셋 다 설계할 것:

1. **`ai.slot.request` 이벤트** (위)
2. **재개 엔드포인트** — 답변은 별도 `POST /api/agent/{conversationId}/slot`으로 들어온다.
   `correlationId`로 중단된 턴에 라우팅한다
3. **중단 세션 저장소** — `conversation_state.messages_json`에 메시지 배열을 영속화한다

### 절대 하지 말 것

**워커 스레드가 `SseEmitter`를 붙잡은 채 사람이 타이핑하기를 기다리면 안 된다.**
스레드 풀이 마르고 무대에서 데모가 죽는다. 턴을 저장하고 스레드를 놓아준 뒤,
재개 요청이 오면 새 스레드에서 이어서 돌린다.

### seq 연속성

재개 후 `seq`는 **초기화하지 않고 이어서 증가**한다. 프론트는 `seq` 순서로 렌더링하므로
리셋하면 트레이스 패널의 순서가 무너진다. `conversation_state.last_seq`에 저장한다.

### 타임아웃

기본 10분. 초과 시 `conversation.status = ABANDONED`로 마킹하고 `ai.error`를 발행한 뒤
`ToolCallContext.discard()`로 누수를 막는다.

## 백엔드 규칙

### SseService API

```java
// 1:1 전송 (AI 스트리밍, 단발 작업)
sseService.send(sessionId, SseEvent.of(type, correlationId, targetId, payload));

// 그룹 브로드캐스트 (여러 화면이 같은 작업을 볼 때)
sseService.broadcastToGroup(group, SseEvent.of(type, group, targetId, payload));

// 세션 생성
sseService.createSession(correlationId, timeoutMs);        // 1:1
sseService.createSession(correlationId, timeoutMs, group);  // 그룹
```

### 필수 규칙

- **SseEvent.of() 팩토리 사용 필수** — `seq`/`ts`는 `send()` 내부에서 자동 부여
- **SseEmitter.send() 직접 호출 금지** — 반드시 `sseService.send()` 경유 (스레드 안전성 보장)
- **emitter별 synchronized lock** — SseEmitter는 thread-safe하지 않음 (Spring #17815)
- **하트비트**: SseService 30초 전역 스케줄러 하나로 통합. 도메인별 자체 하트비트 금지
- **cleanup**: emitter 생명주기 콜백(onCompletion/onTimeout/onError)에서 자동 정리
- **비동기 이벤트**: Spring `ApplicationEvent` → `@EventListener` → `sseService.broadcastToGroup()`

### 에러 payload 표준화

모든 도메인의 에러 이벤트(`ai.error`, `assess.failed` 등)가 동일 구조:
```json
{ "errorType": "RATE_LIMIT|STREAM_ERROR|TIMEOUT|INTERNAL|VALIDATION", "message": "...", "detail": "..." }
```


## 프론트엔드 규칙

### 3계층 구조

| 계층 | 파일 | 역할 |
|---|---|---|
| 프레임 파서 (React 무관) | `src/utils/sseStream.ts` | `readSseStream(response, signal)` — `event:`/`data:` 블록을 async generator로 |
| 봉투 훅 | `src/hooks/useSSEStream.ts` | 봉투 파싱 + `handlers[type]` 디스패치 + `connectionState`. `start(body)`는 스트림이 끝날 때까지 기다리는 Promise |
| 도메인 훅 | `src/pages/WorkPlan/hooks/useAgentStream.ts`, `src/pages/Vision/hooks/useVisionStream.ts` | 이벤트별 상태 갱신, seq 처리, 대화 ID 보존 |

**`EventSource`를 쓰지 않는다.** POST로 시작해야 하고(대화 턴·멀티파트 업로드) 헤더가 필요하다.
**재연결이 없다.** SAIFE의 스트림은 전부 POST라 재연결이 곧 재요청이다. 끊기면 `connectionState`가 `error`로 남고 `SseConnectionStatus`가 표시한다.

### seq 처리

재개 후에도 `seq`가 이어지므로 **역행하는 seq만 중복으로 버린다.** 정렬하지 않고 도착 순서대로 처리한다. 턴 시작 시 `lastSeq=0`으로 초기화한다(도메인 훅 책임).

### 새 SSE 이벤트 추가 절차

1. 백엔드: `SseEvent.of("domain.action", ...)`
2. 프론트: `src/types/sse.ts`의 `SseEventType` union + payload 인터페이스
3. 프론트: 해당 도메인 훅의 `EventMap`과 `handlers`에 case 추가
4. 이 파일의 이벤트 목록 갱신

## 비동기 작업 SSE 연결 패턴

비동기(@Async) 작업의 진행 상태를 SSE로 실시간 전달할 때, 아래 패턴을 필수 적용한다.

> **SAIFE 적용**: 사진 판독(비전)이 비동기 호출이다. HTTP 스레드에서 평가 레코드를
> "판독 중"으로 선커밋한 뒤 `@Async`로 디스패치하고, 완료 시 `assess.done`·실패 시
> `assess.failed`를 발행한다. 프론트의 `enabled`는 서버 데이터에서 파생한다(낙관적 플래그 금지).

### 백엔드 규칙

1. **HTTP 스레드에서 "진행 중" 상태를 DB에 커밋** (REQUIRES_NEW) — `@Async` 디스패치 **전**
2. `@Async` 메서드는 이미 "진행 중" 상태인 데이터를 처리만 수행
3. 완료/실패 시 상태 업데이트 + SSE 이벤트 브로드캐스트

```java
// ✅ 올바른 패턴
public void triggerAsync(...) {
    self().markAsProcessing(...);           // REQUIRES_NEW → 즉시 커밋
    getScheduler().processAsync(...);       // @Async 디스패치
}

// ❌ 금지 패턴
public void triggerAsync(...) {
    getScheduler().processAsync(...);       // @Async 디스패치만 — DB 상태 없음
}
```

### 프론트엔드 규칙

1. API 호출 후 **목록 refetch** → 서버 데이터에서 "진행 중" 여부 파생
2. `enabled: hasXxx` — **서버 데이터 기반만 허용** (낙관적 플래그, always-on 금지)
3. 새로고침 복원: 마운트 시 fetch → 진행 중 항목 있으면 SSE 자동 연결
4. 수동 플래그(`isSending`, `isProcessing`) 없이 데이터에서만 파생

```typescript
// ✅ 올바른 패턴 (데이터 파생)
const hasXxx = dataList.some(item => item.status === "PROCESSING");
useXxxSSE({ enabled: hasXxx, ... });

// API 호출 후
await apiCall(...);
fetchDataList();  // refetch → hasXxx 자동 갱신 → SSE 자동 연결

// ❌ 금지 패턴 (수동 플래그)
const [isProcessing, setIsProcessing] = useState(false);
setIsProcessing(true);  // API 호출 전 수동 설정
useXxxSSE({ enabled: isProcessing, ... });
```


### 참조 구현

| 도메인 | 프론트엔드 파생 | 백엔드 선커밋 |
|--------|---------------|-------------|
| 사진 판독 | `assessment.status === 'ANALYZING'` | 평가 레코드 선커밋(REQUIRES_NEW) + @Async 비전 호출 |
| 공공 API 캐싱 | 크롤러 진행률 | 체크포인트를 DB에 선커밋 (쿼터 리셋 대비 재개 가능) |

## 주의사항

- SSE 연결은 HTTP/1.1 기반 — 브라우저당 동일 도메인 6개 연결 제한. group 브로드캐스트로 연결 수 최소화
- 긴 스트리밍 중 JWT 만료 가능성 → 타임아웃 범위 내에서 처리
- Broken pipe / Connection reset 예외는 클라이언트 끊김 — `cleanup()`에서 자동 처리
