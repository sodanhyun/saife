package io.saife.common.error;

/**
 * 도메인 예외.
 *
 * <p>예외 메시지 문자열을 보고 상태코드를 고르지 않는다. "찾을 수 없습니다"가 들어갔는지
 * 검사하는 식으로 만들면 문구를 다듬는 순간 404가 400으로 바뀐다. <b>타입으로 가른다.</b>
 */
public final class ApiExceptions {

    private ApiExceptions() {}

    /** 자원이 없다 → 404 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }

        public static NotFoundException of(String what, Object id) {
            return new NotFoundException("%s을(를) 찾을 수 없습니다: %s".formatted(what, id));
        }
    }

    /** 지금 상태에서 할 수 없는 일이다 → 409 */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 입력이 잘못됐다 → 400 */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
