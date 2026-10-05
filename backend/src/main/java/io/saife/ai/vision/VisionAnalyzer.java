package io.saife.ai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.ai.config.GeminiSafetySettings;
import io.saife.common.config.DemoModeConfig;
import io.saife.core.domain.AccidentType;
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
 * 사진 → <b>빠진 안전조치</b> 탐지 (UC1).
 *
 * <p>축을 "사고유형 분류"가 아니라 "빠진 안전조치 탐지"로 잡은 것이 이 기능의 핵심 결정이다.
 * 정지 사진은 기계의 동력 상태를 담지 못하므로 <b>사진에 물리적으로 있거나 없는 것</b>만
 * 판정 대상으로 둔다. 인터록·유도자 부재·환기 적정성은 그래서 제외됐다.
 * 이게 위험성평가가 실제로 기록하는 것이기도 하다.
 *
 * <p><b>모델은 후보만 낸다.</b> 등급은 {@code PhotoRiskTable}이 정하고,
 * 채택 여부는 사람이 정한다({@code hazard.ai_adopted}). 이 분리가 성과 지표의
 * 정의이기도 하다 — 정확도를 주장하지 않고 채택률을 센다.
 *
 * <p>2026-09-20 8장 사전 판독 실측: 근거 서술이 사람이 검증 가능한 수준으로 나왔고
 * ({@code docs/vision-smoke-test-20260920.md}), {@code CAUGHT} 축은 0건이었다.
 * 협착은 정지 사진의 한계가 분명한 축이라 9/23 정식 게이트에서 별도로 본다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisionAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;
    private final DemoModeConfig demoModeConfig;

    /**
     * @param accidentType   6축 중 하나
     * @param missingControl 빠진 안전조치 — <b>이게 출력의 본체다</b>
     * @param evidence       사진의 어디를 보고 그렇게 판단했는지. 사람이 검증할 수 있어야 한다
     * @param confidence     모델의 자기보고 신뢰도. 등급에 쓰지 않는다. 정렬에만 쓴다
     * @param box            사진에서 그 근거가 보이는 위치 [ymin, xmin, ymax, xmax] (0~1000). 없으면 null
     */
    public record Finding(AccidentType accidentType, String missingControl,
                          String evidence, Double confidence, List<Integer> box) {
        public Finding(AccidentType accidentType, String missingControl, String evidence, Double confidence) {
            this(accidentType, missingControl, evidence, confidence, null);
        }
    }

    private static final String PROMPT = """
            당신은 제조 사업장의 순회점검자입니다. 사진을 보고 **빠져 있는 안전조치**를 찾으십시오.

            판정 대상은 아래 6가지 발생형태뿐입니다. missingControl은 가능하면 괄호 안 표현을 그대로 쓰십시오.
            - FALL(떨어짐): 이동식 사다리 최상부 발판 또는 그 바로 아래 디딤대에 올라섬("최상부 디딤대 사용"),
              사다리 위 작업인데 작업발판이 없음("작업발판 미확보"), "안전대 부착설비 미설치",
              "개구부 덮개 미설치", "작업발판 안전난간 미설치"
            - CAUGHT(끼임): "방호덮개 미설치"(회전, 구동부)
            - DROP(물체에 맞음): "적재 불량", "낙하물 방지망 미설치"
            - STRUCK(부딪힘): "통로 폐색", "구획선 미설치"
            - FIRE(화재): "가연물 근처 화기", "소화기 미비치"
            - PPE(보호구 미착용): "안전모 미착용", "안전대 미착용", "보안경 미착용"

            반드시 지킬 것:
            - **사진에 물리적으로 보이거나 보이지 않는 것만** 판단하십시오.
            - 추론해야 알 수 있는 것은 판단하지 마십시오. 기계의 동력 상태(가동 중인지),
              환기의 적정성, 사람의 부재(프레임 밖에 있을 수 있음)는 사진으로 확정할 수 없습니다.
            - 사다리를 잡아 주는 사람이나 아웃트리거가 보이면 넘어짐 방지는 있는 것으로 봅니다.
            - evidence에는 **사진의 어느 부분을 보고 그렇게 판단했는지** 한 문장으로 구체적으로 쓰십시오.
              "위험해 보임" 같은 서술은 쓰지 마십시오. 가운뎃점(·)과 대시(—)는 쓰지 마십시오.
            - 빠진 조치가 없으면 findings를 빈 배열로 두십시오. **없는 것을 만들지 마십시오.**
            - 위험성 등급은 매기지 마십시오. 등급은 시스템이 정합니다.
            - box에는 evidence가 가리키는 사람이나 물체를 감싸는 영역을 [ymin, xmin, ymax, xmax]로 쓰십시오.
              사진 크기를 0~1000으로 본 정수입니다.

            JSON만 출력하십시오:
            {"findings":[{"accidentType":"FALL","missingControl":"개구부 덮개 미설치",
              "evidence":"바닥 개구부에 고정되지 않은 임시 철망만 얹혀 있음","confidence":0.8,
              "box":[612,240,840,505]}]}
            """;

    /**
     * 사진을 판독한다.
     *
     * @param imageBytes 이미지 바이트
     * @param contentType MIME 타입 (image/jpeg 등)
     * @return 후보 목록. 빈 목록은 정상 결과다 — 빠진 조치가 없다는 뜻이다
     */
    public List<Finding> analyze(byte[] imageBytes, String contentType) {
        if (demoModeConfig.isDemoMode()) {
            log.info("[UC1] 데모 모드 — 사진 판독은 픽스처를 쓴다");
            return demoFindings();
        }
        try {
            String json = chatClientBuilder.build()
                    .prompt()
                    .user(u -> u.text(PROMPT).media(new Media(mimeType(contentType),
                            new ByteArrayResource(imageBytes))))
                    .options(GoogleGenAiChatOptions.builder()
                            .safetySettings(GeminiSafetySettings.SAFETY_SETTINGS_OFF)
                            .responseMimeType("application/json")
                            .build())
                    .call()
                    .content();

            return parse(json);

        } catch (Exception e) {
            log.error("[UC1] 사진 판독 실패", e);
            throw new VisionAnalysisException("사진 판독에 실패했습니다: " + e.getMessage(), e);
        }
    }

    /** 판독 실패는 조용히 빈 결과로 넘기지 않는다 — 빈 결과와 구분되어야 한다 */
    public static class VisionAnalysisException extends RuntimeException {
        public VisionAnalysisException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private List<Finding> parse(String json) {
        List<Finding> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(stripFence(json));
            JsonNode findings = root.path("findings");
            if (!findings.isArray()) {
                log.warn("[UC1] findings 배열이 없다: {}", json.substring(0, Math.min(200, json.length())));
                return out;
            }
            for (JsonNode node : findings) {
                AccidentType axis = parseAxis(node.path("accidentType").asText(null));
                if (axis == null) {
                    // 6축 밖의 값은 버린다. 축을 늘리는 건 코드에서 결정할 일이지
                    // 모델이 런타임에 결정할 일이 아니다
                    log.warn("[UC1] 알 수 없는 축 무시: {}", node.path("accidentType").asText());
                    continue;
                }
                String missing = node.path("missingControl").asText(null);
                if (missing == null || missing.isBlank()) {
                    continue;
                }
                Double confidence = node.hasNonNull("confidence")
                        ? node.path("confidence").asDouble() : null;
                // 화면 문구 규칙: 가운뎃점 구분자를 쉼표로 바꾼다 ("유도 표식·구획선" → "유도 표식, 구획선")
                out.add(new Finding(axis, missing.trim().replaceAll("\\s*·\\s*", ", "),
                        dotless(node.path("evidence").asText(null)), confidence, box(node.path("box"))));
            }
        } catch (Exception e) {
            log.warn("[UC1] 판독 결과 파싱 실패: {}", e.toString());
        }
        return out;
    }

    /** [ymin, xmin, ymax, xmax] 0~1000. 모양이 맞지 않으면 null (위치 표시만 빠지고 후보는 남는다) */
    static List<Integer> box(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != 4) {
            return null;
        }
        List<Integer> v = new ArrayList<>();
        for (JsonNode n : node) {
            if (!n.isNumber()) {
                return null;
            }
            v.add(Math.max(0, Math.min(1000, n.asInt())));
        }
        if (v.get(2) <= v.get(0) || v.get(3) <= v.get(1)) {
            return null;
        }
        return v;
    }

    /** 화면 문구 규칙: 모델 서술의 가운뎃점("수직·수평")을 슬래시로 바꾼다 */
    private static String dotless(String text) {
        return text == null ? null : text.replace("·", "/");
    }

    /** 모델이 ```json 펜스를 붙이는 경우가 있다 */
    private String stripFence(String json) {
        String t = json.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first > 0 && last > first) {
                return t.substring(first + 1, last).trim();
            }
        }
        return t;
    }

    private AccidentType parseAxis(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return AccidentType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private MimeType mimeType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
        try {
            return MimeTypeUtils.parseMimeType(contentType);
        } catch (Exception e) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
    }

    /**
     * 데모 모드 픽스처.
     *
     * <p>키 없이 받은 심사위원도 UC1 화면을 끝까지 볼 수 있어야 한다.
     * 픽스처를 실제 판독인 것처럼 보이면 안 된다. 사이드바의 "오프라인 모드" 표시와 판독 내용 앞의
     * "(고정 응답)" 표기로 드러난다(순회점검 화면 본문에는 따로 안내 상자를 두지 않는다).
     */
    private List<Finding> demoFindings() {
        return List.of(
                new Finding(AccidentType.FALL, "최상부 디딤대 사용",
                        "(고정 응답) 작업자가 A형 사다리 최상부 발판 위에 서 있음", 0.9, List.of(150, 430, 980, 640)),
                new Finding(AccidentType.PPE, "안전대 미착용",
                        "(고정 응답) 사다리 위 작업자에게 안전대가 보이지 않음", 0.7, List.of(130, 470, 560, 600)));
    }
}
