package io.saife.ai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.ai.config.GeminiSafetySettings;
import io.saife.common.config.DemoModeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 개선대책 이행 증빙 사진 대조.
 *
 * <p>사진이 "그 대책이 적용된 상태"를 보여 주는지 항목별로 본다. 판정 대상은 순회점검과 같이
 * <b>사진에 물리적으로 있거나 없는 것</b>뿐이다. 교육 실시, 작업 절차 같은 것은 사진으로 확정할 수
 * 없으므로 판단 불가로 둔다.
 *
 * <p>대조 결과는 확인자의 판단 자료다. 이행 확인 자체(확인자, 개선 후 위험성)는 사람이 정한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActionEvidenceChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;
    private final DemoModeConfig demoModeConfig;

    /** 항목 판정 */
    public enum ItemStatus { SEEN, NOT_SEEN, UNCLEAR }

    /** 종합. 모든 항목이 보이면 CONFIRMED, 하나라도 안 보이면 NOT_CONFIRMED, 그 밖에는 PARTIAL */
    public enum Verdict { CONFIRMED, PARTIAL, NOT_CONFIRMED }

    /**
     * @param item     사진으로 확인할 항목 (예: "안전난간")
     * @param status   보임 / 안 보임 / 판단 불가
     * @param evidence 사진의 어느 부분을 보고 그렇게 판단했는지
     */
    public record Item(String item, ItemStatus status, String evidence) {}

    public record Result(Verdict verdict, List<Item> items) {}

    private static final String PROMPT = """
            당신은 제조 사업장의 안전관리자입니다. 아래 개선대책을 이행했다며 올라온 증빙 사진입니다.
            개선대책: "%s"
            %s
            이 대책을 사진으로 확인할 수 있는 항목 2~4개로 나누고, 항목마다 사진에 보이는지 판정하십시오.
            예: "이동식 비계(안전난간) 사용" → 작업발판, 안전난간, 바퀴 고정 또는 아웃트리거

            반드시 지킬 것:
            - 사진에 물리적으로 보이는 것만 SEEN, 있어야 할 자리에 없으면 NOT_SEEN으로 판정하십시오.
            - 사진으로 확정할 수 없는 항목(교육 실시, 점검 주기, 화면 밖)은 UNCLEAR로 두십시오.
            - item은 10자 안팎의 명사로 쓰십시오.
            - evidence에는 사진의 어느 부분을 보고 판단했는지 한 문장으로 구체적으로 쓰십시오.
              가운뎃점(·)과 대시(—)는 쓰지 마십시오. 문장은 "~임", "~됨", "~없음"처럼 명사형으로 끝내십시오.
            - 위험성 등급은 매기지 마십시오.

            JSON만 출력하십시오:
            {"items":[{"item":"안전난간","status":"SEEN","evidence":"작업발판 네 변에 상부 난간대와 중간 난간대가 설치됨"}]}
            """;

    /**
     * @param actionContent 개선대책 문안
     * @param hazardText    위험요인 (발생형태: 빠진 조치). 없으면 null
     */
    public Result check(byte[] imageBytes, String contentType, String actionContent, String hazardText) {
        if (demoModeConfig.isDemoMode()) {
            log.info("[ACTION] 데모 모드, 증빙 대조는 픽스처를 쓴다");
            return demoResult();
        }
        String hazardLine = hazardText == null || hazardText.isBlank() ? "" : "위험요인: \"%s\"".formatted(hazardText);
        try {
            String json = chatClientBuilder.build()
                    .prompt()
                    .user(u -> u.text(PROMPT.formatted(actionContent, hazardLine))
                            .media(new Media(mimeType(contentType), new ByteArrayResource(imageBytes))))
                    .options(GoogleGenAiChatOptions.builder()
                            .safetySettings(GeminiSafetySettings.SAFETY_SETTINGS_OFF)
                            .responseMimeType("application/json")
                            .build())
                    .call()
                    .content();
            return parse(json);
        } catch (Exception e) {
            log.error("[ACTION] 증빙 사진 대조 실패", e);
            throw new VisionAnalyzer.VisionAnalysisException("증빙 사진 대조에 실패했습니다: " + e.getMessage(), e);
        }
    }

    static Result parse(String json) {
        List<Item> items = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(stripFence(json));
                for (JsonNode n : root.path("items")) {
                    String item = n.path("item").asText("").strip();
                    if (item.isEmpty()) continue;
                    ItemStatus status;
                    try {
                        status = ItemStatus.valueOf(n.path("status").asText("UNCLEAR").strip().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        status = ItemStatus.UNCLEAR;
                    }
                    items.add(new Item(plain(item), status, plain(n.path("evidence").asText(null))));
                }
            } catch (Exception e) {
                log.warn("[ACTION] 대조 결과 파싱 실패: {}", e.toString());
            }
        }
        return new Result(verdictOf(items), items);
    }

    static Verdict verdictOf(List<Item> items) {
        if (items.isEmpty()) return Verdict.PARTIAL;
        if (items.stream().anyMatch(i -> i.status() == ItemStatus.NOT_SEEN)) return Verdict.NOT_CONFIRMED;
        if (items.stream().allMatch(i -> i.status() == ItemStatus.SEEN)) return Verdict.CONFIRMED;
        return Verdict.PARTIAL;
    }

    /** 화면 문구 규칙: 가운뎃점과 대시를 쉼표로 */
    private static String plain(String text) {
        if (text == null) return null;
        return text.replaceAll("\\s*[\u2014\u2013]\\s*", ", ").replace("·", "/").strip();
    }

    private static String stripFence(String json) {
        String t = json.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first > 0 && last > first) return t.substring(first + 1, last).trim();
        }
        return t;
    }

    private static MimeType mimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) return MimeTypeUtils.IMAGE_JPEG;
        try {
            return MimeTypeUtils.parseMimeType(contentType);
        } catch (Exception e) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
    }

    /** 데모 모드 픽스처: 이동식 비계 사진 */
    private static Result demoResult() {
        List<Item> items = List.of(
                new Item("작업발판", ItemStatus.SEEN, "비계 상단에 전면 작업발판이 깔려 있음"),
                new Item("안전난간", ItemStatus.SEEN, "작업발판 둘레에 상부 난간대와 중간 난간대가 설치됨"),
                new Item("아웃트리거", ItemStatus.SEEN, "비계 하부 네 모서리에 아웃트리거가 펼쳐져 바닥에 닿아 있음"));
        return new Result(verdictOf(items), items);
    }
}
