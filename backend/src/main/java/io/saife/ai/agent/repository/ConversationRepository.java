package io.saife.ai.agent.repository;

import io.saife.ai.agent.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
}
