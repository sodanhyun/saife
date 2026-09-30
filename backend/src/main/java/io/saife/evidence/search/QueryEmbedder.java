package io.saife.evidence.search;

import java.util.Optional;

/** 질의 임베딩. 테스트와 데모 모드에서 갈아끼운다 */
public interface QueryEmbedder {
    Optional<float[]> embed(String text);
    boolean available();
}
