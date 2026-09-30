package io.saife.evidence.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Gemini Flash 리랭크(Inufleet LlmRerankPostProcessor 이식).
 * thinkingBudget(0)이 아니면 Flash가 JSON 지시를 무시한다(실측). 실패는 원본 순서 topK.
 */
@Slf4j
@Component
public class LlmReranker {
    private final ChatClient.Builder builder;
    private final String template;
    @Value("${spring.ai.google.genai.chat.options.model:gemini-3.8-flash}")
    private String model = "gemini-3.8-flash";

    @Autowired
    public LlmReranker(ChatClient.Builder builder, @Value("classpath:prompts/evidence-rerank.txt") Resource prompt) throws IOException {
        this(builder, prompt.getContentAsString(StandardCharsets.UTF_8));
    }

    LlmReranker(ChatClient.Builder builder, String template) { this.builder = builder; this.template = template; }

    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates, int topK) {
        if (candidates.size() <= topK) return candidates;
        List<ChunkHit> docs = candidates.subList(0, Math.min(candidates.size(), SearchPolicy.RERANK_MAX_DOCS));
        try {
            String content = builder.build().prompt().user(buildPrompt(query, docs))
                    .options(GoogleGenAiChatOptions.builder().model(model).temperature(0.0)
                            .maxOutputTokens(500).responseMimeType("application/json").thinkingBudget(0).build())
                    .call().content();
            int[] scores = RerankScoreParser.parse(content, docs.size());
            if (scores == null) { log.warn("[RERANK] 점수 파싱 실패 — 원본 순서"); return fallback(docs, topK); }
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) pairs.add(new int[]{scores[i], i});
            pairs.sort(Comparator.comparingInt((int[] p) -> p[0]).reversed());
            return pairs.stream().filter(p -> p[0] >= SearchPolicy.RERANK_MIN_SCORE).limit(topK)
                    .map(p -> docs.get(p[1]).withScore(p[0] / 10.0)).toList();
        } catch (Exception e) {
            log.warn("[RERANK] 호출 실패 — 원본 순서: {}", e.getMessage());
            return fallback(docs, topK);
        }
    }

    private List<ChunkHit> fallback(List<ChunkHit> docs, int topK) { return docs.subList(0, Math.min(docs.size(), topK)); }

    private String buildPrompt(String query, List<ChunkHit> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            String text = docs.get(i).title() + "\n" + docs.get(i).text();   // 맥락 프리픽스는 리랭커에 유용해 유지
            if (text.length() > SearchPolicy.RERANK_MAX_CHARS) text = text.substring(0, SearchPolicy.RERANK_MAX_CHARS);
            sb.append("[문서 ").append(i + 1).append("]\n").append(text).append("\n\n");
        }
        return template.replace("{query}", query).replace("{documents}", sb.toString().trim());
    }
}
