package io.saife.common.web;

import io.saife.common.error.ApiExceptions.InvalidRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 페이징 파라미터 검증.
 *
 * <p>{@code size=0}을 그대로 {@code PageRequest.of}에 넘기면 예외가 나고 500이 된다
 * (2026-09-21 QA 실측). 0건씩 페이징은 뜻이 없는 요청이라 400으로 돌려준다.
 *
 * <p>상한도 둔다. {@code size=100000} 한 번이 DB 조회와 직렬화를 통째로 태운다.
 */
public final class PageRequests {

    private static final int MAX_SIZE = 200;

    private PageRequests() {}

    public static Pageable of(int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("page는 0 이상이어야 합니다: " + page);
        }
        if (size < 1) {
            throw new InvalidRequestException("size는 1 이상이어야 합니다: " + size);
        }
        if (size > MAX_SIZE) {
            throw new InvalidRequestException(
                    "size는 %d 이하여야 합니다: %d".formatted(MAX_SIZE, size));
        }
        return PageRequest.of(page, size);
    }
}
