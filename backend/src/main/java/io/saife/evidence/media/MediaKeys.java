package io.saife.evidence.media;

import java.util.regex.Pattern;

/**
 * 지침(KOSHA GUIDE) PDF 캐시 키에 쓰이는 {@code guideNo} 검증 규칙.
 *
 * <p>{@link MediaController}(지침 PDF 프록시)와 {@code io.saife.evidence.index.GuidePdfTextSource}
 * (인덱스 빌드)가 같은 {@code "guide/{guideNo}"} 캐시 키를 쓰므로, 둘 다 <b>같은 판정</b>을 내려야
 * 같은 디스크 파일을 가리킨다는 보장이 선다. 규칙은 <b>검증이지 치환이 아니다</b> — 허용 문자
 * ({@code [A-Za-z0-9._-]+}) 밖이면 값을 고쳐 쓰지 않고 그 자체로 거부한다. {@code guideNo}는
 * URL 경로 세그먼트로도 쓰이므로 경로 조작 문자를 전부 배제해야 한다.
 */
public final class MediaKeys {
    private MediaKeys() {}

    /** {@code guideNo} 허용 문자. 프록시·인덱스 빌드 양쪽이 이 상수 하나를 공유한다 */
    public static final Pattern GUIDE_NO_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    /** {@code guideNo}가 캐시 키로 쓰기에 안전한 형식인지. null이거나 허용 문자 밖이면 false */
    public static boolean isValidGuideNo(String guideNo) {
        return guideNo != null && GUIDE_NO_PATTERN.matcher(guideNo).matches();
    }
}
