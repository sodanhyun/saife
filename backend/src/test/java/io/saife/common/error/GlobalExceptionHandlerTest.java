package io.saife.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 없는 경로와 지원하지 않는 메서드가 500으로 떨어지지 않는다(GET /api/assessment 500 → 405) */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 도메인_루트의_없는_GET은_405다() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/assessment");
        assertThat(handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "api/assessment"), req)
                .getStatusCode().value()).isEqualTo(405);
    }

    @Test
    void 메서드가_없으면_405_없는_경로면_404다() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/work-plan/1/hold");
        assertThat(handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("GET"), req)
                .getStatusCode().value()).isEqualTo(405);

        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/api/work-plan/1/nothing");
        assertThat(handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "api/work-plan/1/nothing"), missing)
                .getStatusCode().value()).isEqualTo(404);
    }
}
