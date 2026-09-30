package io.saife.evidence.search;

import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.live.LiveOrCache;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/** gemini-embedding-2(768). 타임아웃·회로는 LiveOrCache가 맡는다. 캐시는 없다(질의는 매번 새것) */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiQueryEmbedder implements QueryEmbedder {
    private static final String HOST = "gemini-embedding";
    private final EmbeddingModel embeddingModel;
    private final DemoModeConfig demoModeConfig;
    private final LiveOrCache liveOrCache;

    @Override
    // 여기서 false면 embed()를 아예 호출하지 않지만, fetch() 내부에서도 isOpen(HOST)을
    // 다시 확인한다(의도적 이중 확인 — 이 메서드와 embed() 사이에 회로가 열릴 수 있다)
    public boolean available() { return !demoModeConfig.isDemoMode() && !liveOrCache.isOpen(HOST); }

    @Override
    public Optional<float[]> embed(String text) {
        if (!available() || text == null || text.isBlank()) return Optional.empty();
        Fetched<float[]> f = liveOrCache.fetch(HOST, true, () -> embeddingModel.embed(text), Optional::empty, v -> {});
        float[] v = f.value();
        if (v != null && v.length != SearchPolicy.EMBEDDING_DIMENSIONS) {
            log.warn("[EMBED] 차원 불일치: 기대 {}, 실제 {} — 빈 결과로 처리", SearchPolicy.EMBEDDING_DIMENSIONS, v.length);
            return Optional.empty();
        }
        return Optional.ofNullable(v);
    }
}
