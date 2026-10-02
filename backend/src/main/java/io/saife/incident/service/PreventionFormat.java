package io.saife.incident.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 재발방지 대책 한 줄의 형식: {@code 번호. 무엇을 (담당 직책 이름, 기한 YYYY-MM-DD)}.
 *
 * <p>조사표와 검토서는 날짜로 기한을 말한다. "작업 재개 전", "즉시", "MM-DD" 같은 기한은 출력 시점이나 읽는 사람에
 * 따라 뜻이 흔들린다. 모델이 형식을 어기거나 예전 기록이 짧은 날짜를 써도 여기서 날짜로 맞춘다.
 * 행정 절차(조사표 제출, 수시평가 실시)는 재발방지 대책이 아니므로 줄을 뺀다.
 */
public final class PreventionFormat {

    private PreventionFormat() {}

    /** "무엇을 (담당 누가, 기한 언제까지)" */
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(?:\\d+[.)]\\s*)?(.+?)\\s*\\(\\s*담당\\s*([^,()]+?)\\s*,\\s*기한\\s*([^()]+?)\\s*\\)\\s*\\.?\\s*((?:\\[#\\d+]\\s*)*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s*");
    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern MONTH_DAY = Pattern.compile("^(\\d{1,2})[-./](\\d{1,2})$");
    private static final Pattern ADMIN_STEP = Pattern.compile("수시평가.*(완료|실시)|조사표.*제출");
    private static final java.util.Set<String> TITLE_ONLY =
            java.util.Set.of("관리감독자", "안전관리자", "사업주", "담당자", "현장 관리자");

    /** 관리적 대책 기한: 발생일부터 7일, 주말이나 공휴일이면 다음 평일 */
    public static LocalDate adminDue(LocalDate occurredOn) {
        return weekday(occurredOn.plusDays(7));
    }

    /** 공학적 대책 기한: 발생일부터 14일, 주말이나 공휴일이면 다음 평일 */
    public static LocalDate engineeringDue(LocalDate occurredOn) {
        return weekday(occurredOn.plusDays(14));
    }

    /** 날짜가 고정인 공휴일(월-일) */
    private static final java.util.Set<String> FIXED_HOLIDAYS =
            java.util.Set.of("01-01", "03-01", "05-05", "06-06", "08-15", "10-03", "10-09", "12-25");
    /** 음력 공휴일과 대체공휴일, 선거일 (2025~2026) */
    private static final java.util.Set<LocalDate> MOVABLE_HOLIDAYS = java.util.Set.of(
            LocalDate.of(2025, 1, 28), LocalDate.of(2025, 1, 29), LocalDate.of(2025, 1, 30),
            LocalDate.of(2025, 3, 3), LocalDate.of(2025, 5, 6), LocalDate.of(2025, 6, 3),
            LocalDate.of(2025, 10, 6), LocalDate.of(2025, 10, 7), LocalDate.of(2025, 10, 8),
            LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18),
            LocalDate.of(2026, 3, 2), LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 3),
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25),
            LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 5));

    /** 주말과 공휴일이면 다음 평일로 */
    static LocalDate weekday(LocalDate d) {
        LocalDate out = d;
        while (out.getDayOfWeek() == DayOfWeek.SATURDAY || out.getDayOfWeek() == DayOfWeek.SUNDAY
                || FIXED_HOLIDAYS.contains("%02d-%02d".formatted(out.getMonthValue(), out.getDayOfMonth()))
                || MOVABLE_HOLIDAYS.contains(out)) {
            out = out.plusDays(1);
        }
        return out;
    }

    /**
     * 재발방지 문안을 줄마다 맞춘다. 번호는 1부터 다시 매긴다.
     *
     * @param occurredOn 사고일. 짧은 날짜(MM-DD)의 연도와 날짜 아닌 기한의 대체값을 정한다
     * @param defaultOwner 담당이 없을 때 쓰는 사람
     */
    public static String normalize(String prevention, LocalDate occurredOn, String defaultOwner) {
        if (prevention == null || prevention.isBlank()) {
            return prevention;
        }
        List<String> out = new ArrayList<>();
        for (String raw : prevention.split("\\r?\\n")) {
            if (raw.isBlank()) {
                continue;
            }
            if (ADMIN_STEP.matcher(raw).find()) {
                continue;
            }
            Matcher m = LINE.matcher(raw.strip());
            String what;
            String who;
            String when;
            String refs;
            if (m.matches()) {
                what = m.group(1).strip();
                who = m.group(2).strip();
                when = dueDate(m.group(3).strip(), occurredOn, what);
                refs = m.group(4) == null ? "" : m.group(4).strip();
            } else {
                what = NUMBERED.matcher(raw).replaceFirst("").strip();
                who = defaultOwner;
                when = dueDate("", occurredOn, what);
                refs = "";
            }
            // 직책만 있고 이름이 없으면 누가 하는지 알 수 없다. 사업장 안전관리자로 둔다
            if (who.isBlank() || TITLE_ONLY.contains(who)) {
                who = defaultOwner;
            }
            out.add("%d. %s (담당 %s, 기한 %s)%s".formatted(out.size() + 1, what, who, when,
                    refs.isEmpty() ? "" : " " + refs));
        }
        return String.join("\n", out);
    }

    /** 기한 문자열을 YYYY-MM-DD로. 날짜가 아니면 대책 성격으로 7일 또는 14일 */
    static String dueDate(String when, LocalDate occurredOn, String what) {
        String w = when == null ? "" : when.strip();
        if (ISO.matcher(w).matches()) {
            return w;
        }
        Matcher md = MONTH_DAY.matcher(w);
        if (md.matches()) {
            int month = Integer.parseInt(md.group(1));
            int day = Integer.parseInt(md.group(2));
            try {
                LocalDate d = LocalDate.of(occurredOn.getYear(), month, day);
                if (d.isBefore(occurredOn)) {
                    d = d.plusYears(1);
                }
                return d.toString();
            } catch (java.time.DateTimeException e) {
                // 날짜가 아니면 아래 기본값
            }
        }
        boolean engineering = what != null && what.matches(".*(설치|교체|수리|비계|방호|덮개|난간|개선|보수).*");
        return (engineering ? engineeringDue(occurredOn) : adminDue(occurredOn)).toString();
    }
}
