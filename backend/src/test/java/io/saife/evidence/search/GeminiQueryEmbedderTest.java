package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.live.LiveOrCache;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class GeminiQueryEmbedderTest {
    @Test
    void 데모_모드면_available_false이고_호출_안_함() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        when(cfg.isDemoMode()).thenReturn(true);
        GeminiQueryEmbedder e = new GeminiQueryEmbedder(model, cfg, new LiveOrCache(Duration.ofSeconds(1), 3, Duration.ofSeconds(60)));
        assertThat(e.available()).isFalse();
        assertThat(e.embed("q")).isEmpty();
        verifyNoInteractions(model);
    }

    @Test
    void 성공하면_벡터() {
        float[] vector = new float[SearchPolicy.EMBEDDING_DIMENSIONS];
        vector[0] = 0.1f; vector[1] = 0.2f;
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(String.class))).thenReturn(vector);
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        GeminiQueryEmbedder e = new GeminiQueryEmbedder(model, cfg, new LiveOrCache(Duration.ofSeconds(1), 3, Duration.ofSeconds(60)));
        assertThat(e.embed("q")).contains(vector);
    }

    @Test
    void 실패하면_empty() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(String.class))).thenThrow(new RuntimeException("429"));
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        GeminiQueryEmbedder e = new GeminiQueryEmbedder(model, cfg, new LiveOrCache(Duration.ofSeconds(1), 3, Duration.ofSeconds(60)));
        assertThat(e.embed("q")).isEmpty();
    }

    @Test
    void 차원이_다르면_empty() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f});
        DemoModeConfig cfg = mock(DemoModeConfig.class);
        GeminiQueryEmbedder e = new GeminiQueryEmbedder(model, cfg, new LiveOrCache(Duration.ofSeconds(1), 3, Duration.ofSeconds(60)));
        assertThat(e.embed("q")).isEmpty();
    }
}
