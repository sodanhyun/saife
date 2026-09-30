package io.saife.evidence.live;

import java.time.OffsetDateTime;

/** 외부 조회 결과 + 출처. value가 null이면 빈 결과다 */
public record Fetched<T>(T value, Origin origin, OffsetDateTime fetchedAt, String note) {
    public static <T> Fetched<T> empty(String note) {
        return new Fetched<>(null, Origin.CACHE, OffsetDateTime.now(), note);
    }
    public boolean isEmpty() { return value == null; }
}
