package io.saife.ai.agent.repository;

import io.saife.ai.agent.domain.ToolCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {
    List<ToolCallLog> findByConversationIdOrderByCallOrder(String conversationId);
    long countBySuccess(boolean success);
}
