package io.saife.common.error;

import io.saife.common.error.ApiExceptions.ConflictException;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.error.ApiExceptions.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전역 예외 처리.
 *
 * <p><b>없는 id 하나에 스택트레이스가 뜨면 안 된다.</b> QA에서 {@code /api/incident/999999}부터
 * {@code /form/assessment/999999}까지 전부 500으로 응답했다 (2026-09-21 실측 12건).
 * 심사위원은 URL을 바꿔보고, 링크를 두 번 누르고, 뒤로 갔다 온다. 그때마다
 * Internal Server Error가 뜨면 그 화면이 마지막 인상이 된다.
 *
 * <p>상태코드는 <b>예외 타입으로</b> 고른다. 메시지 문자열을 검사하는 방식은
 * 문구를 다듬는 순간 조용히 깨진다.
 *
 * <p>{@code /form/**}은 사람이 브라우저로 여는 화면이라 JSON 대신 읽을 수 있는
 * HTML을 돌려준다. JSON 덤프를 보여주면 "고장났다"로 읽힌다.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException e, HttpServletRequest req) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<?> handleConflict(ConflictException e, HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT, "CONFLICT", e.getMessage(), req);
    }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<?> handleBadRequest(Exception e, HttpServletRequest req) {
        // 요청 본문 파싱 실패 메시지에는 내부 타입명이 섞여 나온다. 그대로 흘리지 않는다
        String message = (e instanceof InvalidRequestException || e instanceof IllegalArgumentException)
                ? e.getMessage()
                : "요청 형식이 올바르지 않습니다.";
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION", message, req);
    }

    /** 상태 전이 위반 — 이미 승인된 계획서를 또 승인하는 등 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e, HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT, "CONFLICT", e.getMessage(), req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleTooLarge(MaxUploadSizeExceededException e,
                                            HttpServletRequest req) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "VALIDATION",
                "이미지가 너무 큽니다. 20MB 이하로 올려주세요.", req);
    }

    /**
     * 나머지 전부.
     *
     * <p>메시지를 밖으로 내보내지 않는다. 내부 예외 문구에는 테이블명·쿼리·경로가
     * 섞여 나오고, 그건 화면에 띄울 것이 아니다. 로그에는 전부 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("[ERROR] 처리되지 않은 예외 {} {}", req.getMethod(), req.getRequestURI(), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", req);
    }

    // ---------- 응답 만들기 ----------

    private ResponseEntity<?> respond(HttpStatus status, String errorType,
                                      String message, HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path != null && path.startsWith("/form")) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_HTML)
                    .body(htmlPage(status, message));
        }

        // 에러 payload 구조는 SSE 에러 이벤트와 같은 모양을 쓴다
        // (.claude/rules/sse-streaming.md — 도메인마다 다른 에러 모양을 만들지 않는다)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", errorType);
        body.put("message", message);
        body.put("status", status.value());
        body.put("path", path);
        body.put("ts", OffsetDateTime.now().toString());
        return ResponseEntity.status(status).body(body);
    }

    /** 서식 경로용 최소 오류 화면. 인쇄 레이아웃과 같은 폰트를 쓴다 */
    private String htmlPage(HttpStatus status, String message) {
        return """
                <!DOCTYPE html>
                <html lang="ko"><head><meta charset="UTF-8"><title>%d</title>
                <style>
                  body { font-family: "Malgun Gothic", "맑은 고딕", sans-serif;
                         display: flex; align-items: center; justify-content: center;
                         height: 100vh; margin: 0; background: #f3f4f6; color: #111; }
                  .box { background: #fff; padding: 32px 40px; border-radius: 8px;
                         box-shadow: 0 1px 6px rgba(0,0,0,.12); text-align: center; }
                  h1 { margin: 0 0 8px; font-size: 20pt; }
                  p { margin: 0; color: #555; }
                </style></head>
                <body><div class="box"><h1>%d</h1><p>%s</p></div></body></html>
                """.formatted(status.value(), status.value(), escape(message));
    }

    /** 메시지에 사용자 입력이 섞여 들어올 수 있다. HTML로 해석되게 두지 않는다 */
    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
