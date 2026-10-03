package io.saife.core.action;

import io.saife.common.dto.PageResponse;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 개선대책 목록. 순회점검, 설비 이력, 오늘 할 일에 흩어진 조치를 한 표로 모아
 * 안전관리자가 기한과 이행을 추적한다. 이행 완료 기록은 {@link ActionService#complete}를 그대로 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ActionListService {

    static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final ActionListQuery query;

    @Transactional(readOnly = true)
    public PageResponse<ActionDtos.ActionListItem> list(String status, String keyword, Integer page, Integer size) {
        ActionListFilter filter = ActionListFilter.parse(status);
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw new InvalidRequestException("page는 0 이상입니다.");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new InvalidRequestException("size는 1~%d입니다.".formatted(MAX_SIZE));
        }
        ActionListQuery.Slice slice = query.find(filter, keyword, p, s, today());
        return PageResponse.from(new PageImpl<>(slice.content(), PageRequest.of(p, s), slice.total()));
    }

    @Transactional(readOnly = true)
    public ActionDtos.ActionCounts counts() {
        return query.counts(today());
    }

    private LocalDate today() {
        return LocalDate.now(Clock.system(ActionListQuery.KST));
    }
}
