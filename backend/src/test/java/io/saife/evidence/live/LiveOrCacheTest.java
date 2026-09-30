package io.saife.evidence.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LiveOrCacheTest {

    private LiveOrCache newHelper() {
        return new LiveOrCache(Duration.ofMillis(200), 3, Duration.ofSeconds(60));
    }

    @Test
    void 라이브_성공이면_LIVE이고_캐시에_저장한다() {
        LiveOrCache h = newHelper();
        AtomicInteger stored = new AtomicInteger();
        Fetched<String> f = h.fetch("h1", true, () -> "live", Optional::empty, v -> stored.incrementAndGet());
        assertThat(f.value()).isEqualTo("live");
        assertThat(f.origin()).isEqualTo(Origin.LIVE);
        assertThat(stored.get()).isEqualTo(1);
    }

    @Test
    void 키가_없으면_라이브를_부르지_않고_캐시() {
        LiveOrCache h = newHelper();
        AtomicInteger calls = new AtomicInteger();
        Fetched<String> f = h.fetch("h1", false, () -> { calls.incrementAndGet(); return "live"; },
                () -> Optional.of("cached"), v -> {});
        assertThat(calls.get()).isZero();
        assertThat(f.origin()).isEqualTo(Origin.CACHE);
        assertThat(f.value()).isEqualTo("cached");
    }

    @Test
    void 타임아웃이면_캐시() {
        LiveOrCache h = newHelper();
        Fetched<String> f = h.fetch("h1", true, () -> { Thread.sleep(1000); return "late"; },
                () -> Optional.of("cached"), v -> {});
        assertThat(f.origin()).isEqualTo(Origin.CACHE);
        assertThat(f.value()).isEqualTo("cached");
    }

    @Test
    void 라이브도_캐시도_없으면_empty() {
        LiveOrCache h = newHelper();
        Fetched<String> f = h.fetch("h1", true, () -> { throw new RuntimeException("boom"); },
                Optional::empty, v -> {});
        assertThat(f.isEmpty()).isTrue();
        assertThat(f.note()).contains("boom");
    }

    @Test
    void 호출_스레드가_인터럽트되면_캐시로_폴백하고_인터럽트_플래그를_복원한다() {
        LiveOrCache h = newHelper();
        Thread.currentThread().interrupt();
        Fetched<String> f = h.fetch("h1", true, () -> { Thread.sleep(500); return "late"; },
                () -> Optional.of("cached"), v -> {});
        assertThat(f.origin()).isEqualTo(Origin.CACHE);
        assertThat(Thread.interrupted()).isTrue();   // 플래그 복원 확인. 이 호출 자체가 플래그를 지운다
        assertThat(h.isOpen("h1")).isFalse();        // 호출 스레드 인터럽트는 호스트 실패로 세지 않는다
    }

    @Test
    void 연속_3회_실패_후_회로_열림() {
        LiveOrCache h = newHelper();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            h.fetch("h1", true, () -> { calls.incrementAndGet(); throw new RuntimeException("x"); }, Optional::empty, v -> {});
        }
        assertThat(h.isOpen("h1")).isTrue();
        h.fetch("h1", true, () -> { calls.incrementAndGet(); return "v"; }, Optional::empty, v -> {});
        assertThat(calls.get()).isEqualTo(3);   // 4번째는 시도조차 안 한다
        assertThat(h.isOpen("h2")).isFalse();   // 호스트별
    }
}
