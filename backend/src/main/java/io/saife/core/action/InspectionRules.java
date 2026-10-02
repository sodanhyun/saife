package io.saife.core.action;

import io.saife.core.domain.RiskLevel;

import java.util.Arrays;
import java.util.List;

/**
 * 순회점검 기록의 판정 규칙. 화면(API)과 위험성평가표 서식이 같은 규칙을 쓴다.
 * 화면은 "확정"이라고 하는데 서식은 "작성 중"이면 그 순간 신뢰를 잃는다.
 */
public final class InspectionRules {

    private InspectionRules() {}

    /**
     * 허용 가능 여부 기본값. 3단계 판단법(고시 제7조)에서 상과 중은 감소대책이 필요한 수준이라
     * 허용 불가로 시작하고, 하만 허용 가능으로 시작한다. 사람이 바꾸면 그 값이 우선한다.
     */
    public static boolean defaultAcceptable(RiskLevel level) {
        return level == RiskLevel.LOW;
    }

    /** 저장값이 있으면 그것, 없으면 등급 기본값 */
    public static boolean acceptable(Boolean stored, RiskLevel level) {
        return stored != null ? stored : defaultAcceptable(level);
    }

    /**
     * 위험요인 한 건의 기록 상태.
     *
     * @param suggested  사진에서 올라온 후보인가(반영/제외 판단 대상)
     * @param reflected  반영 여부. null이면 아직 판단 전
     * @param acceptable 허용 가능 여부(기본값 적용 후)
     * @param hasAction  개선대책이 있는가(이 점검에서 등록했거나, 기한이 남은 기존 조치가 있음)
     */
    public record Item(boolean suggested, Boolean reflected, boolean acceptable, boolean hasAction) {}

    /**
     * 확정 조건: 참여 근로자가 있고, 모든 후보를 반영 또는 제외했고,
     * 반영한 위험요인 중 허용 불가인 것에는 개선대책이 있다.
     */
    public static boolean complete(String participants, List<Item> items) {
        if (splitParticipants(participants).isEmpty()) {
            return false;
        }
        for (Item item : items) {
            if (item.suggested() && item.reflected() == null) {
                return false;
            }
            boolean excluded = item.suggested() && Boolean.FALSE.equals(item.reflected());
            if (!excluded && !item.acceptable() && !item.hasAction()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 기한이 지난 조치인가(사업장 기준시). 기한 지난 미이행 조치는 개선대책으로 인정하지 않는다.
     * 고시 제12조④: 미이행이 길어지면 잠정조치를 포함한 대책을 다시 세운다.
     */
    public static boolean overdue(java.time.LocalDate dueDate) {
        return dueDate != null && dueDate.isBefore(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
    }

    /** 쉼표로 이은 이름 → 목록. 빈 이름과 앞뒤 공백은 버린다 */
    public static List<String> splitParticipants(String participants) {
        if (participants == null || participants.isBlank()) {
            return List.of();
        }
        // "(자동 생성 초안, 참여자를 입력한 뒤 …)" 같은 괄호 안내 문구는 이름이 아니다
        String t = participants.strip();
        if (t.startsWith("(") && t.endsWith(")")) {
            return List.of();
        }
        return Arrays.stream(participants.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 목록 → 저장 문자열. 비면 null */
    public static String joinParticipants(List<String> names) {
        if (names == null) {
            return null;
        }
        List<String> clean = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.strip().replace(",", " "))
                .distinct()
                .toList();
        return clean.isEmpty() ? null : String.join(", ", clean);
    }
}
