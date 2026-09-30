package io.saife.evidence.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.*;

/** 법제처 조문 캐시. 항(項) 단위 한 행. 항이 없는 조는 paragraphNo=0 한 행 */
@Entity
@Table(name = "law_article")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LawArticle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "law_id", nullable = false, length = 20) private String lawId;
    @Column(name = "law_name", nullable = false, length = 100) private String lawName;
    @Column(name = "article_no", nullable = false) private int articleNo;
    @Column(name = "article_sub", nullable = false) private int articleSub;
    @Column(name = "paragraph_no", nullable = false) private int paragraphNo;
    @Column(length = 300) private String title;
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(name = "effective_on") private LocalDate effectiveOn;
    @Column(name = "source_url", columnDefinition = "text") private String sourceUrl;
    @Column(name = "fetched_at", nullable = false) private OffsetDateTime fetchedAt;

    @PrePersist void onCreate() { if (fetchedAt == null) fetchedAt = OffsetDateTime.now(); }

    /** 조(條) 단위 제목. 항 표기가 없다 — parent 청크 제목은 이걸 쓴다. 예: 산업안전보건법 제36조(위험성평가의 실시) */
    public String articleCitation() {
        String sub = articleSub > 0 ? "의" + articleSub : "";
        String t = title == null ? "" : "(" + title + ")";
        return "%s 제%d조%s%s".formatted(lawName, articleNo, sub, t);
    }

    /** 화면·인용용 제목. 예: 산업안전보건법 제36조(위험성평가의 실시) ① */
    public String citation() {
        String para = paragraphNo > 0 ? " " + circled(paragraphNo) : "";
        return articleCitation() + para;
    }

    private static String circled(int n) {
        return n >= 1 && n <= 20 ? String.valueOf((char) ('①' + n - 1)) : "(" + n + ")";
    }
}
