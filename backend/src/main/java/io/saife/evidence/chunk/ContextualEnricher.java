package io.saife.evidence.chunk;

import io.saife.common.config.DemoModeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Contextual Retrieval(HyDE 대체, Inufleet 이식). 청크가 문서의 어느 자리인지 한 줄을 만들어 앞에 붙인다.
 * 실패·빈 답·대괄호 포함 답은 버리고 원문을 돌려준다. 데모 모드에서는 부르지 않는다.
 */
@Slf4j
@Component
public class ContextualEnricher {
    static final int HEAD_CHARS = 3000;
    static final int AROUND_CHARS = 2500;
    static final int WHOLE_DOC_LIMIT = 8000;

    private final ChatClient.Builder builder;
    private final DemoModeConfig demoModeConfig;
    private final String template;
    @Value("${spring.ai.google.genai.chat.options.model:gemini-3.8-flash}")
    private String model = "gemini-3.8-flash";

    @Autowired
    public ContextualEnricher(ChatClient.Builder builder, DemoModeConfig demoModeConfig,
                              @Value("classpath:prompts/contextual-enrichment.txt") Resource prompt) throws IOException {
        this(builder, demoModeConfig, prompt.getContentAsString(StandardCharsets.UTF_8));
    }

    ContextualEnricher(ChatClient.Builder builder, DemoModeConfig demoModeConfig, String template) {
        this.builder = builder; this.demoModeConfig = demoModeConfig; this.template = template;
    }

    public String enrich(String fullDocument, String chunkText, int chunkOffset, String documentName) {
        if (demoModeConfig.isDemoMode() || chunkText == null || chunkText.isBlank()) return chunkText;
        try {
            String prompt = template.replace("{fileName}", documentName == null ? "" : documentName)
                    .replace("{document}", buildDocumentContext(fullDocument, chunkOffset))
                    .replace("{chunk}", chunkText);
            String answer = builder.build().prompt().user(prompt)
                    .options(GoogleGenAiChatOptions.builder().model(model).temperature(0.0)
                            .maxOutputTokens(150).thinkingBudget(0).build())
                    .call().content();
            if (answer == null) return chunkText;
            String ctx = answer.strip().replaceAll("^맥락 설명:\\s*", "");
            if (ctx.isBlank() || ctx.contains("[") || ctx.contains("]") || ctx.length() > ContextualPrefix.MAX_PREFIX - 2) return chunkText;
            return ContextualPrefix.of(ctx, chunkText);
        } catch (Exception e) {
            log.warn("[ENRICH] 실패 doc={}: {}", documentName, e.getMessage());
            return chunkText;
        }
    }

    /** 8,000자 이하 문서는 전체. 아니면 앞 3,000자 + 청크 주변 ±2,500자 */
    String buildDocumentContext(String doc, int offset) {
        if (doc == null) return "";
        if (doc.length() <= WHOLE_DOC_LIMIT) return doc;
        if (offset <= HEAD_CHARS + AROUND_CHARS) return doc.substring(0, WHOLE_DOC_LIMIT);
        int end = Math.min(doc.length(), offset + AROUND_CHARS);
        // offset이 문서 끝 근처(또는 밖)여도 start<=end가 깨지지 않도록 end로 한 번 더 clamp한다.
        int start = Math.min(Math.max(HEAD_CHARS, offset - AROUND_CHARS), end);
        return doc.substring(0, HEAD_CHARS) + "\n...\n" + doc.substring(start, end);
    }
}
