package io.saife.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 스트리밍 연결 관리 — 통합 인프라 레이어.
 *
 * <h3>핵심 설계: sessionId 기반 SSE 연결 격리</h3>
 * <p>
 * 동일 conversationId로 연속 요청이 들어오면 각 요청마다 고유한 {@code sessionId}를 발급한다.
 * 모든 SSE 이벤트 전송/종료는 sessionId로 수행하므로, 이전 세션의 종료(onError, onTimeout, complete)가
 * 새 세션의 emitter를 삭제하는 레이스 컨디션이 구조적으로 불가능하다.
 * </p>
 * <pre>
 * [요청 1] conversationId="conv-123" → sessionId="sess-aaa" → emitters["sess-aaa"]=emitter1
 * [요청 2] conversationId="conv-123" → sessionId="sess-bbb" → emitters["sess-bbb"]=emitter2
 *   ↳ emitter1의 onError → cleanup("sess-aaa") — emitter2에 영향 없음!
 * </pre>
 *
 * <h3>컨트롤러 → 서비스 흐름</h3>
 * <ol>
 *   <li>컨트롤러: {@code createSession(conversationId)} → {@code SseSession(emitter, sessionId, conversationId)}</li>
 *   <li>컨트롤러: sessionId를 {@code process()} 파라미터로 전달</li>
 *   <li>서비스: 모든 SSE 호출에 sessionId 사용 → {@code send(sessionId, event)}</li>
 * </ol>
 *
 * <h3>그룹 브로드캐스트</h3>
 * <p>
 * {@code createSession(correlationId, timeoutMs, group)}으로 세션을 그룹에 등록하면,
 * {@code broadcastToGroup(group, event)}로 동일 그룹의 모든 세션에 이벤트를 전송할 수 있다.
 * </p>
 */
@Service
@Slf4j
public class SseService {

    /** SSE 세션 — emitter + sessionId + conversationId를 캡슐화 */
    public record SseSession(SseEmitter emitter, String sessionId, String conversationId) {}

    /**
     * SSE 통합 이벤트 봉투 — seq/ts 자동 부여, 그룹 브로드캐스트 지원.
     * 모든 새 SSE 이벤트 전송은 이 record를 통해 전달된다.
     */
    public record SseEvent(
            String type,
            String correlationId,
            String targetId,
            int seq,
            Instant ts,
            Object payload
    ) {
        /** 팩토리 메서드 — seq/ts는 send() 시점에 자동 부여 */
        public static SseEvent of(String type, String correlationId, String targetId, Object payload) {
            return new SseEvent(type, correlationId, targetId, 0, null, payload);
        }

        /** seq/ts를 부여한 새 인스턴스 반환 (불변) */
        SseEvent withSeqAndTs(int seq, Instant ts) {
            return new SseEvent(this.type, this.correlationId, this.targetId, seq, ts, this.payload);
        }
    }

    private static final long CHAT_TIMEOUT_MS = 5 * 60 * 1000L;    // 5분
    private static final long REPORT_TIMEOUT_MS = 10 * 60 * 1000L;  // 10분

    /** 그룹당 허용 최대 구독 세션 수 — 초과 시 FIFO eviction */
    private static final int MAX_SESSIONS_PER_GROUP = 3;

    // sessionId → emitter (각 SSE 연결은 고유한 sessionId로 격리)
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // sessionId → per-emitter 동기화 락 (동시 전송 직렬화)
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    // sessionId → 시퀀스 카운터 (이벤트 순서 보장)
    private final ConcurrentHashMap<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();

    // sessionId → group (세션이 속한 그룹)
    private final ConcurrentHashMap<String, String> sessionGroups = new ConcurrentHashMap<>();

    // group → Set<sessionId> (그룹에 속한 세션 목록)
    private final ConcurrentHashMap<String, Set<String>> groupSessions = new ConcurrentHashMap<>();

    /**
     * SSE 세션을 생성하고 고유 sessionId를 발급한다 (그룹 지원).
     *
     * @param correlationId 논리적 식별자 (conversationId 등)
     * @param timeoutMs     SSE 타임아웃 (밀리초)
     * @param group         그룹 식별자 (null이면 그룹 없음)
     * @return SseSession (emitter, sessionId, correlationId)
     */
    public SseSession createSession(String correlationId, long timeoutMs, String group) {
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(timeoutMs);

        emitters.put(sessionId, emitter);
        seqCounters.put(sessionId, new AtomicInteger(0));

        if (group != null) {
            sessionGroups.put(sessionId, group);
            Set<String> sessions = groupSessions.computeIfAbsent(group,
                    k -> Collections.synchronizedSet(new LinkedHashSet<>()));

            // add + size check + oldest peek을 원자적으로 수행 (TOCTOU 방지)
            String oldest = null;
            synchronized (sessions) {
                sessions.add(sessionId);
                if (sessions.size() > MAX_SESSIONS_PER_GROUP) {
                    var it = sessions.iterator();
                    if (it.hasNext()) {
                        oldest = it.next();
                    }
                }
            }
            // complete()는 synchronized 밖에서 호출 (cleanup → sessions.remove() 재진입 방지)
            if (oldest != null && !oldest.equals(sessionId)) {
                log.info("그룹 세션 상한 초과, 최오래 세션 evict. group={}, evictedSessionId={}", group, oldest);
                complete(oldest);
            }
        }

        log.info("SSE 세션 생성. sessionId={}, correlationId={}, group={}", sessionId, correlationId, group);

        emitter.onCompletion(() -> cleanup(sessionId));
        emitter.onTimeout(() -> {
            log.warn("SSE 타임아웃: sessionId={}", sessionId);
            cleanup(sessionId);
        });
        emitter.onError(e -> {
            log.debug("SSE 에러: sessionId={}", sessionId);
            cleanup(sessionId);
        });

        return new SseSession(emitter, sessionId, correlationId);
    }

    /**
     * SSE 세션을 생성하고 고유 sessionId를 발급한다.
     * 동일 conversationId로 여러 번 호출해도 각각 독립된 세션이 생성되므로 레이스 컨디션이 발생하지 않는다.
     *
     * @param conversationId 논리적 대화 식별자 (DB 저장, 프론트엔드 매핑용)
     * @return SseSession (emitter, sessionId, conversationId)
     */
    public SseSession createSession(String conversationId) {
        return createSession(conversationId, CHAT_TIMEOUT_MS, null);
    }

    /**
     * 세션 관련 모든 리소스를 정리한다 (emitter, lock, seq, group 매핑).
     */
    private void cleanup(String sessionId) {
        SseEmitter removed = emitters.remove(sessionId);
        if (removed == null) return; // 이미 정리됨 (중복 콜백 방지)
        locks.remove(sessionId);
        seqCounters.remove(sessionId);
        log.debug("SSE 세션 정리. sessionId={}, 남은 세션 수={}", sessionId, emitters.size());

        String group = sessionGroups.remove(sessionId);
        if (group != null) {
            Set<String> sessions = groupSessions.get(group);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    groupSessions.remove(group);
                }
            }
        }
    }

    // ==================== 통합 전송 API ====================

    /**
     * SseEvent를 seq/ts 자동 부여 후 전송한다.
     * per-emitter synchronized 락으로 동시 전송을 직렬화한다.
     */
    public void send(String sessionId, SseEvent event) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) return;

        AtomicInteger counter = seqCounters.get(sessionId);
        int seq = (counter != null) ? counter.incrementAndGet() : 0;
        SseEvent complete = event.withSeqAndTs(seq, Instant.now());

        Object lock = locks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(seq))
                        .name(complete.type())
                        .data(complete));
            } catch (IOException e) {
                log.debug("SSE 전송 실패 (클라이언트 끊김): sessionId={}", sessionId);
                cleanup(sessionId);
            } catch (IllegalStateException e) {
                log.debug("SSE 스트림 이미 닫힘: sessionId={}", sessionId);
                cleanup(sessionId);
            }
        }
    }

    /**
     * 그룹에 속한 모든 세션에 이벤트를 브로드캐스트한다.
     */
    public void broadcastToGroup(String group, SseEvent event) {
        Set<String> sessions = groupSessions.get(group);
        if (sessions == null || sessions.isEmpty()) return;

        // synchronizedSet 순회는 모니터 획득 필수 (Collections.synchronizedSet 계약)
        Set<String> snapshot;
        synchronized (sessions) {
            snapshot = Set.copyOf(sessions);
        }
        for (String sessionId : snapshot) {
            send(sessionId, event);
        }
    }

    /** SSE 세션 정상 종료 */
    public void complete(String sessionId) {
        SseEmitter emitter = emitters.get(sessionId);
        cleanup(sessionId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    /** SSE 세션 에러 종료 */
    public void completeWithError(String sessionId, Throwable error) {
        SseEmitter emitter = emitters.get(sessionId);
        cleanup(sessionId);
        if (emitter != null) {
            try { emitter.completeWithError(error); } catch (Exception ignored) {}
        }
    }

    /**
     * 주기적 하트비트 전송 — 통합 봉투 포맷(SseEvent) 사용.
     */
    @Scheduled(fixedDelayString = "${sse.heartbeat.interval:30000}")
    /**
     * 현재 seq 값. 되묻기 턴으로 중단할 때 이 값을 저장했다가 재개 후 이어서 증가시킨다.
     * 리셋하면 프론트 트레이스 패널의 순서가 무너진다.
     */
    public int currentSeq(String sessionId) {
        AtomicInteger counter = seqCounters.get(sessionId);
        return counter != null ? counter.get() : 0;
    }

    public void sendHeartbeat() {
        if (emitters.isEmpty()) return;

        // ConcurrentHashMap.forEach()는 약한 일관성 — 순회 중 제거된 엔트리는 건너뜀
        emitters.forEach((sessionId, emitter) -> {
            SseEvent heartbeat = SseEvent.of("system.heartbeat", sessionId, null, null);
            send(sessionId, heartbeat);
        });
    }
}
