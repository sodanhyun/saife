---
globs: ["frontend/src/**/*sse*", "frontend/src/**/*SSE*", "frontend/src/**/*Stream*", "frontend/src/hooks/useSSEStream*", "frontend/src/types/sse*", "backend/**/service/SseService.java", "backend/**/event/*Event*.java"]
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
| `type` | `domain.action` 형태 (예: `cv.progress`, `cv.done`, `ai.token`) |
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

// 그룹 브로드캐스트 (배치작업, 임베딩)
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

모든 도메인의 에러 이벤트(`ai.error`, `batch.error`, `embed.error` 등)가 동일 구조:
```json
{ "errorType": "RATE_LIMIT|STREAM_ERROR|TIMEOUT|INTERNAL|VALIDATION|CONTRACT_MISMATCH", "message": "...", "detail": "..." }
```

- `CONTRACT_MISMATCH`(2026-08-01, 측정기준 계층): CV 응답이 계약 게이트(atoms 부재·schema_version 미달·필수 원자 누락)에 걸려 판정이 거부된 경우. `cv.failed`로 방출되며 프론트는 재업로드 버튼을 숨기고 관리자 문의 안내를 표시한다(재업로드로 해결되지 않는 운영 오설정이므로).

## 프론트엔드 규칙

### 3계층 구조

```
Layer 1: readSseStream()    — 순수 SSE 프레임 파싱 (React 무관, src/utils/sseStream.ts)
Layer 2: useSSEStream()     — React 래퍼 (봉투 파싱, 연결 상태, src/hooks/useSSEStream.ts)
Layer 3: 도메인 훅          — handlers 맵 주입 (useInspectionStream, useBatchProgress 등)
```

### useSSEStream 두 가지 모드

- **선언적**: `useSSEStream({ enabled: true, ... })` — 페이지 진입 시 자동 연결 (배치작업, 임베딩)
- **명령형**: `useSSEStream({ enabled: false, ... })` + `start(body)` — 사용자 액션으로 트리거 (AI 스트리밍)
- AI 스트리밍 훅은 고유 fire-and-forget 패턴이므로 useSSEStream을 사용하지 않고 readSseStream + 봉투 파싱 직접 구현

### 타입 정의

- `SseEnvelope<T>`, `ConnectionState`, `SseErrorPayload` → `src/types/sse.ts`
- 도메인별 `EventMap` → 각 도메인 훅 파일 또는 `src/types/sse.ts`

## 새 SSE 이벤트 추가 절차

1. **백엔드**: `SseEvent.of("domain.action", ...)` 호출 추가
2. **프론트엔드**: 도메인 `EventMap`에 타입 추가
3. **프론트엔드**: 도메인 훅의 `handlers` 맵에 핸들러 등록
4. 이 파일의 이벤트 목록 업데이트

## 비동기 작업 SSE 연결 패턴 (임베딩 기준)

비동기(@Async) 작업의 진행 상태를 SSE로 실시간 전달할 때, 아래 패턴을 필수 적용한다.

> **CV 게이트웨이 적용**: CV 측정은 비동기(@Async) 호출이다. HTTP 스레드에서 라운드 `UPLOADED` 상태를 REQUIRES_NEW로 선커밋한 뒤 `@Async` CV 호출을 디스패치하고, 완료 시 `cv.done`(라운드 `CV_DONE`)·실패 시 `cv.failed`(라운드 `CV_FAILED`/`MASK_FAIL`)를 브로드캐스트한다. 프론트는 `useCvProgress` 훅(cv.progress/done/failed 핸들러 맵)으로 수신하며, `enabled`는 서버 데이터(라운드 상태 UPLOADED/진행중)에서 파생한다(낙관적 플래그 금지).

### 백엔드 규칙

1. **HTTP 스레드에서 "진행 중" 상태를 DB에 커밋** (REQUIRES_NEW) — `@Async` 디스패치 **전**
2. `@Async` 메서드는 이미 "진행 중" 상태인 데이터를 처리만 수행
3. 완료/실패 시 상태 업데이트 + SSE 이벤트 브로드캐스트

```java
// ✅ 올바른 패턴 (임베딩/배치작업)
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

### 예외: in-memory 상태 홀더 기반 작업

in-memory 상태 홀더 기반으로 실행되는 배치/동기화 작업은 동기 실행 경로가 있어 패턴이 다름. `state.running` 낙관적 플래그 허용.

### 참조 구현

| 도메인 | 프론트엔드 파생 | 백엔드 선커밋 |
|--------|---------------|-------------|
| 임베딩 | `hasEmbedding` (`useEmbedding.ts`) | `uploadAndEmbed()` T1 커밋 + afterCommit |
| 배치작업 | `hasBatchRunning` (`useBatchJob.ts`) | `markAsProcessing()` REQUIRES_NEW |
| CV 측정 | `hasCvRunning`(라운드 UPLOADED) (`useCvProgress.ts`) | 라운드 UPLOADED 선커밋(REQUIRES_NEW) + @Async CV 호출 |
| 학습 데이터셋 export | `jobs.some(PENDING\|RUNNING)` (`useExportJobs.ts` — App 전역 1회 마운트) | PENDING 선커밋(REQUIRES_NEW) + DB 클레임 픽업 루프(잡별 @Async 없음 — 슬라이스6 D20) |

## 시그널 이벤트 + 전량 재조회 (OP 라인 andon — 2026-08-06)

`op.queue.changed`는 **payload가 없다.** 클라이언트는 이벤트를 받으면 큐를 전량 재조회하며,
연결이 맺힐 때마다(최초·재연결 모두) 한 번 더 재조회한다.

이벤트에 데이터를 실으면 유실 1건이 화면 누락 1건으로 영구화되지만, 신호만 실으면 어떤 이벤트를
놓쳐도 그다음 이벤트 하나로 상태가 완전히 복구된다 — **"연결은 살아 있는데 이벤트만 조용히
사라지는" 경로가 구조적으로 존재하지 않는다**(`send()` 실패는 emitter 정리 → 클라이언트 끊김 감지
→ 재연결 → 재조회로 이어진다).

- 발행은 `OpQueueNotifier.lineChanged(lineId)` **단일 진입점**을 거치며 `afterCommit`에서 나간다.
  커밋 전에 보내면 롤백된 변경으로 현장 화면이 한 번 깜빡인다.
- 큐를 바꾸는 전이를 추가하면 이 메서드를 호출하고 **`OpQueueNotifierWiringTest`에 단언을 더한다.**
  발행 지점 누락이 이 설계에서 구조적으로 닫히지 않는 유일한 빈틈이고, 그 테스트가 방어선이다.
  현재 배선 지점: `ConfirmService.confirm`·`autoConfirm`·`completeAction`,
  `CaseDeleteService.softDelete`·`restore`, `CaseUpdateService.update`(호기 변경 시 구·신 양쪽).
- 브로드캐스트 실패는 삼킨다(`log.warn`) — 알림 실패로 조치완료가 롤백되는 쪽이 훨씬 나쁘다.
  클라이언트는 재연결 재조회와 3분 백스톱으로 스스로 회복한다.
- OP 화면의 `useSSEStream`은 **`retry: "forever"`** 를 쓴다(1s→2s→5s→10s→30s 상한, 지터 ±20%).
  키보드 없는 상시 모니터라 재시도 예산이 소진되면 사람이 되살릴 수단이 없다.
  **다른 화면의 "무한 재시도 금지" 정책은 그대로다** — 기본값은 여전히 `budgeted`다.
- emitter 타임아웃은 **1500초(25분)** — nginx `proxy_read_timeout 1800s`보다 짧아야 emitter가 먼저
  정상 종료된다(프록시가 먼저 끊으면 클라이언트는 원인 불명의 연결 끊김을 본다).
- `SseService.MAX_SESSIONS_PER_GROUP`은 3이다. 한 호기에 모니터를 4대 이상 붙이면 가장 오래된
  세션이 FIFO로 밀려난다 — 밀려난 모니터는 재연결하지만, 라인당 3대를 넘길 계획이라면 이 상수를 먼저 본다.

## 주의사항

- SSE 연결은 HTTP/1.1 기반 — 브라우저당 동일 도메인 6개 연결 제한. group 브로드캐스트로 연결 수 최소화
- 긴 스트리밍 중 JWT 만료 가능성 → 타임아웃 범위 내에서 처리
- Broken pipe / Connection reset 예외는 클라이언트 끊김 — `cleanup()`에서 자동 처리
