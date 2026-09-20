package io.saife.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 페이징 응답 표준 래퍼. 컨트롤러에서 {@code Page<T>}를 직접 반환하지 않는다.
 * 프론트 대응 타입: {@code PaginationResponse<T>} (src/types/common.ts)
 */
public record PageResponse<T>(
        List<T> content,
        int number,
        int size,
        int totalPages,
        long totalElements
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages(),
                page.getTotalElements());
    }
}
