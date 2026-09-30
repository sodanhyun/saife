package io.saife.publicapi.service;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사고사망(1040) 응답 전처리.
 *
 * <p>필드가 {@code contents}·{@code keyword}·{@code arno} 셋뿐이다. 일자·장소·사망자 수가
 * 구조화 필드로 오지 않고 전부 자연어 안에 있다.
 *
 * <p>{@code keyword}는 포맷이 정규화되어 있어 매칭의 주력이다:
 * <pre>[9/17, 충남 아산시] 차량 유도 작업 중 후진하는 타이어롤러에 부딪힘</pre>
 *
 * <p>반면 {@code contents}는 HTML과 꼬리말이 섞여 있고, 태그를 떼면
 * {@code "15:29 경"}, {@code "( 수 )"}, {@code "사망 1 명"}처럼 공백이 깨진다.
 * 그대로 임베딩에 넣으면 유사도가 망가진다.
 */
public final class CaseTextCleaner {

    private CaseTextCleaner() {}

    /**
     * HTML 태그.
     *
     * <p><b>길이 상한을 두면 안 된다.</b> 공단 게시판의 {@code contents}에는
     * {@code <img src='data:image/jpeg;base64,...'>}가 통째로 들어 있고, 그 태그 하나가
     * 500KB를 넘는 경우가 있다. 상한을 2000자로 뒀더니 이 태그들이 살아남아
     * 본문 평균 길이가 3,714자로 부풀었다 (실제 서술은 150자 안팎이다).
     *
     * <p>base64 덩어리가 남으면 임베딩이 망가지고, 모델 프롬프트에 그대로 실려
     * 토큰을 태우고, 저장소 동봉 캐시가 9MB로 불어난다. 셋 다 실제로 일어났다.
     *
     * <p>{@code [^>]*}는 중첩 수량자가 없어 선형이다. 상한이 없어도 폭주하지 않는다.
     */
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    /** 태그 형태가 깨져 본문에 노출된 base64 덩어리 */
    private static final Pattern DATA_URI =
            Pattern.compile("data:[a-zA-Z/]+;base64,[A-Za-z0-9+/=]+");

    private static final Pattern TAIL = Pattern.compile("※\\s*위\\s*내용은[\\s\\S]*$");
    private static final Pattern HEAD_BRACKET =
            Pattern.compile("^\\[\\s*(\\d{1,2})/(\\d{1,2})\\s*,\\s*([^\\]]+?)\\s*\\]");

    /** 숫자·한글 사이에 끼어든 군더더기 공백. 태그 제거의 부산물이다 */
    private static final Pattern SPACE_BEFORE_UNIT =
            Pattern.compile("(\\d)\\s+(명|일|시|분|월|년|개|층|m|M|호)");
    private static final Pattern SPACE_IN_PAREN = Pattern.compile("\\(\\s+([^)]*?)\\s+\\)");
    /**
     * 모든 공백류를 한 칸으로 — <b>개행과 CR을 반드시 포함한다.</b>
     *
     * <p>공백과 탭만 다루면 원문의 CR이 살아남는다. psql이나 브라우저는 CR을
     * 보여주지 않아 <b>"금속에서패널"처럼 단어가 붙은 것처럼 보이는데 실제로는
     * 제어문자가 끼어 있는 것</b>이다. 바이트를 찍어보기 전까지 띄어쓰기 버그로
     * 오진해 엉뚱한 정규식을 붙일 뻔했다 (2026-09-20 실측: 2,316건).
     *
     * <p>제어문자가 남으면 JSONL 한 줄이 여러 줄로 깨지고 임베딩 토큰도 갈라진다.
     *
     * <p>(!) {@code "\s"}로 쓰면 안 된다. Java 15부터 {@code \s}는 문자열 리터럴에서
     * <b>공백 한 칸을 뜻하는 합법 이스케이프</b>다(JEP 378). 컴파일 에러 없이 통과하고
     * 정규식은 {@code " +"}가 돼 공백만 지우는 버그가 조용히 생긴다. 실제로 당했다.
     */
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /**
     * 원문에 붙어 있는 문장 경계 — <b>내 전처리의 부산물이 아니라 소스가 그렇다.</b>
     *
     * <p>{@code "15:15경충남 아산시"}처럼 한 {@code <p>} 안에서 시각과 지역이 붙어 있다.
     * 공단 게시판이 블록 레이아웃에 기대어 작성된 탓이다. 붙은 채로 두면 임베딩에서
     * "경충남"이 한 토큰처럼 취급돼 유사도가 망가진다.
     *
     * <p>{@code 에서} 뒤에 무조건 공백을 넣으면 {@code "현장에서의 작업"} 같은
     * 조사 확장형이 깨진다. 실측 데이터에서 조사 확장형 381건은 건드리지 않고
     * 붙은 문장 89건만 띄어놓는 것을 확인했다 (2026-09-20).
     */
    private static final Pattern TIME_GLUE =
            Pattern.compile("([0-9]{1,2}:[0-9]{2}[ ]*경)(?=[가-힣])");
    private static final Pattern PLACE_GLUE =
            Pattern.compile("에서(?=[가-힣])(?!는|은|도|의|만|나|과|와|부터|까지|라도|야말로)");

    /** 사진은 공단 포털 호스트에서만 받는다. 프록시가 SSRF에 쓰이지 않게 여기서부터 막는다 */
    private static final Pattern IMG_SRC =
            Pattern.compile("<img[^>]*\\ssrc\\s*=\\s*['\"]?(https://portal\\.kosha\\.or\\.kr/[^'\"\\s>]+)", Pattern.CASE_INSENSITIVE);

    /** 국내재해사례(1060)는 게시글 딥링크가 없다. 포털 목록 페이지가 원문 링크다 */
    public static final String DISASTER_LIST_URL =
            "https://portal.kosha.or.kr/archive/disaster-case/accident-case";

    /**
     * 원문 HTML의 첫 사고 사진 URL.
     *
     * <p>1040 원문은 전 건에 {@code <img src='https://portal.kosha.or.kr/api/compn24/auth/stdtboard/getImage.do?...'>}가
     * 있다(2026-09-28 실측 300/300). {@link #clean}이 태그를 지우기 <b>전에</b> 불러야 한다.
     */
    public static String imageUrlOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = IMG_SRC.matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /**
     * HTML 태그·꼬리말 제거 후 공백 정규화.
     *
     * <p>세 단계 전부 필요하다. 하나라도 빼면 사례 검색 결과에 깨진 문장이 그대로 뜬다.
     */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String s = raw;
        s = TAG.matcher(s).replaceAll(" ");
        // 태그 밖에 노출된 data: URI (닫는 > 가 유실된 경우)
        s = DATA_URI.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        s = TAIL.matcher(s).replaceAll("");
        // 글루 규칙보다 먼저 공백을 정규화한다.
        // 안 그러면 제어문자가 낀 자리를 글루 규칙이 못 본다
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = SPACE_IN_PAREN.matcher(s).replaceAll("($1)");
        s = SPACE_BEFORE_UNIT.matcher(s).replaceAll("$1$2");
        s = TIME_GLUE.matcher(s).replaceAll("$1 ");
        // 패턴이 "에서"만 잡고 캐처 그룹이 없다. $1을 쓰면 No group 1로 터진다
        s = PLACE_GLUE.matcher(s).replaceAll("에서 ");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /**
     * {@code [9/17, 충남 아산시]}에서 지역을 뽑는다.
     *
     * <p>포맷을 벗어나면 null을 돌려준다. <b>추측하지 않는다</b> — 틀린 지역이 붙으면
     * 업종·지역 필터가 조용히 잘못된 사례를 올린다.
     */
    public static String regionOf(String keyword) {
        if (keyword == null) {
            return null;
        }
        Matcher m = HEAD_BRACKET.matcher(keyword.trim());
        return m.find() ? m.group(3).trim() : null;
    }

    /**
     * {@code [9/17, ...]}에서 발생일을 뽑는다.
     *
     * <p>연도가 없다. 이 API는 최근 사고를 싣고 미래 날짜는 없으므로,
     * 올해로 해석했을 때 미래면 작년으로 본다. 그래도 틀릴 수 있는 추정이라
     * 정렬·표시에만 쓰고 법정 기한 계산에는 쓰지 않는다.
     */
    public static LocalDate occurredOn(String keyword) {
        if (keyword == null) {
            return null;
        }
        Matcher m = HEAD_BRACKET.matcher(keyword.trim());
        if (!m.find()) {
            return null;
        }
        try {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            LocalDate today = LocalDate.now();
            LocalDate candidate = LocalDate.of(today.getYear(), month, day);
            return candidate.isAfter(today) ? candidate.minusYears(1) : candidate;
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }
}
