package io.saife.evidence.search;

/** 검색 상수. Inufleet 검증값을 그대로 쓰고, 여기 한 곳에서만 바꾼다 */
public final class SearchPolicy {
    private SearchPolicy() {}
    public static final double VECTOR_WEIGHT = 0.6;
    public static final double KEYWORD_WEIGHT = 0.4;
    public static final int RRF_K = 60;
    public static final double SIMILARITY_THRESHOLD = 0.40;
    public static final int CANDIDATE_MULTIPLIER = 4;
    public static final int RERANK_MAX_DOCS = 40;
    public static final int RERANK_MAX_CHARS = 1500;
    public static final int RERANK_MIN_SCORE = 4;
    public static final int KEYWORD_MAX_TOKENS = 8;
    public static final int SNIPPET_CHARS = 200;
    public static final double BOOST_SAME_AXIS = 0.05;
    public static final double BOOST_MANUFACTURING = 0.03;
    public static final double BOOST_HAS_IMAGE = 0.02;
    public static final int EMBEDDING_DIMENSIONS = 768;
    /** 양쪽 레그 rank 0인 문서의 이론상 최대 RRF 점수 — 리랭크를 건너뛴 경로에서 0~1로 정규화하는 분모 */
    public static final double RRF_MAX = 1.0 / (RRF_K + 1);
    /** 리랭크 생략 경로에서 보정·중복제거 이후 남기는 후보 폭 배수(= k의 몇 배) */
    public static final int NO_RERANK_MULTIPLIER = 2;
}
