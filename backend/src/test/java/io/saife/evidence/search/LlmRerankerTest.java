package io.saife.evidence.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.saife.evidence.EvidenceKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class LlmRerankerTest {
    private ChunkHit hit(long id) { return new ChunkHit(id, EvidenceKind.GUIDE, 1, "k" + id, "child", null, null, "t", "text" + id, Map.of(), 0.1 * id); }

    private LlmReranker reranker(String answer) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(client);
        if (answer == null) when(client.prompt().user(any(String.class)).options(any()).call().chatResponse()).thenThrow(new RuntimeException("x"));
        else when(client.prompt().user(any(String.class)).options(any()).call().chatResponse())
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        return new LlmReranker(builder, "<p>{query}{documents}</p>");
    }

    @Test
    void 점수_내림차순_4점_미만_제외_topK() {
        List<ChunkHit> out = reranker("[3, 9, 5, 8]").rerank("q", List.of(hit(1), hit(2), hit(3), hit(4)), 2);
        assertThat(out).extracting(ChunkHit::id).containsExactly(2L, 4L);
        assertThat(out.get(0).score()).isEqualTo(0.9);
    }

    @Test
    void 전부_4점_미만이면_빈_결과() {
        assertThat(reranker("[1, 2, 3]").rerank("q", List.of(hit(1), hit(2), hit(3)), 2)).isEmpty();
    }

    @Test
    void 후보가_topK_이하면_LLM을_부르지_않는다() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        LlmReranker r = new LlmReranker(builder, "x");
        assertThat(r.rerank("q", List.of(hit(1), hit(2)), 3)).hasSize(2);
        verifyNoInteractions(builder);
    }

    @Test
    void 파싱_실패나_예외면_원본_순서_topK() {
        assertThat(reranker("oops").rerank("q", List.of(hit(1), hit(2), hit(3)), 2)).extracting(ChunkHit::id).containsExactly(1L, 2L);
        assertThat(reranker(null).rerank("q", List.of(hit(1), hit(2), hit(3)), 2)).extracting(ChunkHit::id).containsExactly(1L, 2L);
    }

    @Test
    void 입력은_40건까지만() {
        List<ChunkHit> many = java.util.stream.LongStream.rangeClosed(1, 60).mapToObj(this::hit).toList();
        StringBuilder scores = new StringBuilder("[");
        for (int i = 0; i < SearchPolicy.RERANK_MAX_DOCS; i++) scores.append(i > 0 ? "," : "").append(5);
        scores.append("]");
        assertThat(reranker(scores.toString()).rerank("q", many, 5)).hasSize(5);
    }
}
