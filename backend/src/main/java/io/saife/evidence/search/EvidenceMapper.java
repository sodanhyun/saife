package io.saife.evidence.search;

import io.saife.evidence.Evidence;
import io.saife.evidence.chunk.ContextualPrefix;
import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.util.Map;

/** ChunkHit → 카드. URL 규칙이 여기 한 곳에 있다 */
public final class EvidenceMapper {
    private EvidenceMapper() {}

    public static Evidence toEvidence(ChunkHit h, Origin origin) {
        Map<String, Object> m = h.metadata();
        String body = ContextualPrefix.strip(h.text());
        String snippet = body.length() > SearchPolicy.SNIPPET_CHARS ? body.substring(0, SearchPolicy.SNIPPET_CHARS) + "…" : body;
        String source = null, media = null, thumb = null;
        switch (h.kind()) {
            case CASE_FATALITY, CASE_DISASTER -> {
                boolean hasImage = Boolean.TRUE.equals(m.get("hasImage"));
                if (hasImage) { media = "/api/media/case/" + h.refId() + "/photo"; thumb = media + "?w=320"; }
                source = str(m.get("sourceUrl"));
            }
            case GUIDE -> {
                String guideNo = str(m.get("guideNo"));
                media = guideNo == null ? null : "/api/media/guide/" + guideNo + ".pdf";
                String fileDownloadUrl = str(m.get("fileDownloadUrl"));
                source = fileDownloadUrl != null ? fileDownloadUrl : media;
            }
            case LAW -> source = str(m.get("sourceUrl"));
            case MSDS -> source = str(m.get("sourceUrl"));
        }
        return new Evidence(0, h.kind(), h.refId(), h.refKey(), h.title(), snippet, source, media, thumb, origin,
                displayScore(h.score()), h.fetchedAt() != null ? h.fetchedAt() : OffsetDateTime.now(), m);
    }

    /**
     * 카드·도구 줄에 싣는 "유사도"는 0~1로 자른다(최종 리뷰 F2). 검색 파이프라인은 도메인 보정
     * (축·업종·사진)을 더한 값으로 <b>정렬</b>하므로 1.0을 넘을 수 있지만(리랭크 10점 + 보정 →
     * "유사도 107%"), 정렬은 이미 끝났으니 표시값만 자른다. 별도 표시 점수 필드를 두는 것보다
     * 변경이 작고, 카드 순서는 그대로다.
     */
    static double displayScore(double score) {
        double clamped = Math.max(0.0, Math.min(1.0, score));
        return Math.round(clamped * 1000) / 1000.0;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
