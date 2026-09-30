package io.saife.evidence.repository;
import io.saife.evidence.domain.ConversationEvidence;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface ConversationEvidenceRepository extends JpaRepository<ConversationEvidence, Long> {
    List<ConversationEvidence> findByConversationIdOrderByEvidenceNo(String conversationId);
    @Query("select coalesce(max(e.evidenceNo), 0) from ConversationEvidence e where e.conversationId = :cid")
    int maxEvidenceNo(@Param("cid") String conversationId);
    @Query("select coalesce(max(e.turnNo), 0) from ConversationEvidence e where e.conversationId = :cid")
    int maxTurnNo(@Param("cid") String conversationId);
    Optional<ConversationEvidence> findByConversationIdAndEvidenceNo(String conversationId, int evidenceNo);
}
