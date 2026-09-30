package io.saife.evidence.live;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 라이브 우선 + 캐시 폴백 + 호스트별 회로 차단.
 *
 * <p>순서: 키 있음 && 회로 닫힘 → 라이브(타임아웃) → 성공 시 store, LIVE.
 * 실패·타임아웃·키 없음 → 캐시, CACHE. 캐시도 없으면 empty(note).
 *
 * <p>회로: 같은 호스트에서 연속 N회 실패하면 open 동안 라이브를 건너뛴다.
 * 무대에서 네트워크가 죽었을 때 도구마다 8초씩 기다리는 사고를 막는다.
 * 호출자는 예외를 보지 않는다.
 */
@Slf4j
@Component
public class LiveOrCache {
    private record Circuit(int failures, long openedUntilMs) {}

    private final Duration timeout;
    private final int failureThreshold;
    private final Duration openFor;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "live-or-cache");
        t.setDaemon(true);
        return t;
    });

    // 생성자가 2개라 애매하므로 Spring DI 대상을 명시한다 (없으면 no-arg 생성자를 찾다가 기동 실패)
    @Autowired
    public LiveOrCache(@Value("${saife.external.timeout-ms:8000}") long timeoutMs,
                       @Value("${saife.external.circuit-failures:3}") int failureThreshold,
                       @Value("${saife.external.circuit-open-ms:60000}") long openMs) {
        this(Duration.ofMillis(timeoutMs), failureThreshold, Duration.ofMillis(openMs));
    }

    // io.saife.evidence.search 등 다른 패키지의 테스트가 타임아웃을 짧게 주입하려고 이 생성자를 쓴다 (예: GeminiQueryEmbedderTest) — 그래서 public이다
    public LiveOrCache(Duration timeout, int failureThreshold, Duration openFor) {
        this.timeout = timeout;
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
    }

    public <T> Fetched<T> fetch(String host, boolean keyPresent, Callable<T> live,
                                Supplier<Optional<T>> cache, Consumer<T> store) {
        String note;
        if (!keyPresent) {
            note = "키 없음";
        } else if (isOpen(host)) {
            note = "회로 열림(" + host + ")";
        } else {
            Future<T> future = pool.submit(live);
            try {
                T value = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                onSuccess(host);
                if (value != null) {
                    try { store.accept(value); } catch (Exception e) { log.warn("[LIVE] 캐시 저장 실패 host={}: {}", host, e.getMessage()); }
                    return new Fetched<>(value, Origin.LIVE, OffsetDateTime.now(), null);
                }
                note = "라이브 응답 비어 있음";
            } catch (TimeoutException e) {
                future.cancel(true);
                onFailure(host);
                note = "타임아웃 " + timeout.toMillis() + "ms";
            } catch (InterruptedException e) {
                // 호출 스레드(우리 쪽)가 인터럽트된 것이지 호스트가 죽은 게 아니다.
                // 인터럽트 플래그를 복원하고, 회로 실패로는 세지 않는다.
                future.cancel(true);
                Thread.currentThread().interrupt();
                note = "호출 스레드 인터럽트";
            } catch (Exception e) {
                onFailure(host);
                Throwable c = e.getCause() != null ? e.getCause() : e;
                note = "라이브 실패: " + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
            }
            log.warn("[LIVE] host={} → 캐시 폴백 ({})", host, note);
        }
        Optional<T> cached = Optional.empty();
        try { cached = cache.get(); } catch (Exception e) { log.warn("[LIVE] 캐시 조회 실패 host={}: {}", host, e.getMessage()); }
        final String n = note;
        return cached.map(v -> new Fetched<>(v, Origin.CACHE, OffsetDateTime.now(), n))
                .orElseGet(() -> Fetched.empty(n));
    }

    public boolean isOpen(String host) {
        Circuit c = circuits.get(host);
        return c != null && c.openedUntilMs() > System.currentTimeMillis();
    }

    public Set<String> openHosts() {
        return circuits.entrySet().stream().filter(e -> isOpen(e.getKey())).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void onSuccess(String host) { circuits.remove(host); }

    private void onFailure(String host) {
        circuits.compute(host, (k, c) -> {
            int f = (c == null ? 0 : c.failures()) + 1;
            long until = f >= failureThreshold ? System.currentTimeMillis() + openFor.toMillis() : 0;
            if (until > 0) log.warn("[LIVE] 회로 열림 host={} {}초", host, openFor.toSeconds());
            return new Circuit(f, until);
        });
    }
}
