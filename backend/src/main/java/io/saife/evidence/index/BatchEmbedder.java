package io.saife.evidence.index;

import io.saife.common.config.DemoModeConfig;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 배치 임베딩. 테스트에서는 람다로 대체한다 — {@link IndexBuilder}는 100건 단위로
 * 이미 잘라 넘기므로 구현체가 다시 쪼갤 필요는 없다.
 */
public interface BatchEmbedder {
    List<float[]> embedAll(List<String> texts);

    /**
     * 지금 임베딩을 부를 수 있는가. {@link IndexBuilder#rebuild}가 <b>아무것도 지우기 전에</b> 본다 —
     * 데모 모드에서 기존 청크를 지운 뒤에야 {@link DemoModeSkipped}를 받으면 근거 코퍼스가 통째로
     * 사라진다(최종 리뷰 F5). 테스트 람다는 기본값(true)을 쓴다.
     */
    default boolean available() {
        return true;
    }

    /**
     * 데모 모드에서 모델을 부르지 않았다는 신호. {@link IndexBuilder}가 이 예외만 따로 잡아
     * 체크포인트를 FAILED로 더럽히지 않고 리포트 status를 {@code SKIPPED}로 남긴다.
     */
    class DemoModeSkipped extends RuntimeException {
        public DemoModeSkipped(String message) {
            super(message);
        }
    }

    /** gemini-embedding-2. 데모 모드(키 없음)에서는 절대 호출하지 않는다 */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    class Gemini implements BatchEmbedder {
        private final EmbeddingModel model;
        private final DemoModeConfig demoModeConfig;

        @Override
        public boolean available() {
            return !demoModeConfig.isDemoMode();
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            if (demoModeConfig.isDemoMode()) {
                throw new DemoModeSkipped("데모 모드 — 임베딩 호출을 생략한다 (" + texts.size() + "건)");
            }
            return new ArrayList<>(model.embed(texts));
        }
    }
}
