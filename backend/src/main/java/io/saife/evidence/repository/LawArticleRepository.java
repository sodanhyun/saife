package io.saife.evidence.repository;
import io.saife.evidence.domain.LawArticle;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LawArticleRepository extends JpaRepository<LawArticle, Long> {
    List<LawArticle> findByLawNameAndArticleNoAndArticleSubOrderByParagraphNo(String lawName, int articleNo, int articleSub);
    Optional<LawArticle> findByLawIdAndArticleNoAndArticleSubAndParagraphNo(String lawId, int articleNo, int articleSub, int paragraphNo);
    long countByLawId(String lawId);
}
