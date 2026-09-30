package io.saife.evidence.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.repository.LawArticleRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link LawCitationTable}의 모든 인용이 실제로 시드된 {@code law_article}에 존재하는지 검증한다.
 *
 * <p>Task 5 Step 9에서 {@code POST /api/admin/law/crawl}로 3개 법령 전체를 실제 수집한 뒤
 * (2026-09-28, 산업안전보건법 560행 · 시행규칙 641행 · 안전보건규칙 1102행) 실측 SQL로
 * 17개 조문 번호가 전부 존재함을 확인했다. 이 테스트는 그 확인을 코드로 고정한다.
 *
 * <p>조문이 시드되지 않은 DB(신규 클론 직후 등)에서는 실패한다 — 먼저 크롤을 실행한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class LawArticleServiceIT {

    @Autowired LawArticleRepository repository;

    @Test
    void 인용표의_모든_조문이_시드에_존재한다() {
        List<LawCitationTable.Citation> all = new ArrayList<>();
        for (AccidentType axis : AccidentType.values()) all.addAll(LawCitationTable.forAxis(axis));
        all.addAll(LawCitationTable.common());
        all.addAll(LawCitationTable.afterIncident());

        for (LawCitationTable.Citation c : all) {
            List<?> rows = repository.findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(
                    c.lawName(), c.articleNo(), c.articleSub());
            assertThat(rows)
                    .as("인용 %s 제%d조%s — %s", c.lawName(), c.articleNo(),
                            c.articleSub() > 0 ? "의" + c.articleSub() : "", c.why())
                    .isNotEmpty();
        }
    }
}
