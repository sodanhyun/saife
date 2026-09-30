package io.saife.evidence.live;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 법령 링크 규칙과 법제처 자격증명(OC) 제거.
 *
 * <p><b>왜 필요한가(최종 리뷰 F1):</b> 법제처 목록 응답의 {@code 법령상세링크}는 DRF API URL이고
 * 쿼리에 호출자의 {@code OC}(기관코드)가 그대로 박혀 온다. 예전에는 이 링크에 조문 앵커만 붙여
 * {@code law_article.source_url}에 저장했고, 그게 시드 파일·근거 카드 "원문 보기"·법정 서식
 * "참고 자료" 줄로 그대로 새어 나갔다. OC는 가입 이메일에서 파생되어 재발급이 안 되는 값이라
 * 한 번 새면 되돌릴 수 없다.
 *
 * <p>그래서 원문 링크는 <b>자격증명이 없는 사람용 법령 페이지</b>
 * ({@code https://www.law.go.kr/법령/{법령명}/제{n}조[의{sub}]})로 만든다. DRF 링크를 받아야 하는
 * 경로가 남더라도 {@link #stripOc(String)}로 OC 파라미터를 지운다(시드 적재 시 이중 방어).
 */
public final class LawUrls {
    private LawUrls() {}

    private static final String BASE = "https://www.law.go.kr/";

    /**
     * OC 파라미터 한 개: 앞 구분자({@code ?}/{@code &}), 값, 뒤따르는 {@code &}(있으면).
     * 값은 URL이 JSON 문자열 안에 있을 수도 있어 따옴표·공백·{@code #}·{@code <}에서도 끊는다.
     */
    private static final Pattern OC_PARAM = Pattern.compile("([?&])OC=[^&#\"'\\s<>]*(&?)");

    /**
     * 사람이 읽는 조문 페이지 URL. 경로의 한글은 UTF-8 퍼센트 인코딩한다(공백은 {@code %20}).
     * V11 마이그레이션과 시드 스크럽 스크립트가 같은 규칙으로 문자열을 만든다 — 바꾸면 셋을 같이 바꾼다.
     */
    public static String articlePage(String lawName, int articleNo, int articleSub) {
        String article = "제" + articleNo + "조" + (articleSub > 0 ? "의" + articleSub : "");
        return BASE + enc("법령") + "/" + enc(lawName) + "/" + enc(article);
    }

    /**
     * 문자열 안의 모든 {@code OC=} 쿼리 파라미터를 지운다. 남는 구분자는 자리에 맞게 정리한다:
     * {@code a?OC=<v>&b=1 → a?b=1}, {@code a?OC=<v> → a}, {@code a?b=1&OC=<v> → a?b=1},
     * {@code a?b=1&OC=<v>&c=2 → a?b=1&c=2}. 정리는 치환 지점에서만 하므로 나머지 텍스트는 그대로다.
     */
    public static String stripOc(String s) {
        if (s == null || !s.contains("OC=")) {
            return s;
        }
        Matcher m = OC_PARAM.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String lead = m.group(1);
            boolean trailingAmp = !m.group(2).isEmpty();
            // ?OC=<v>&… → ?…   /  ?OC=<v> → (없음)  /  &OC=<v>&… → &…  /  &OC=<v> → (없음)
            String rep = trailingAmp ? lead : "";
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
