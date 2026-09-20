package io.saife.ai.agent.repository;

import io.saife.ai.agent.domain.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationStateRepository extends JpaRepository<ConversationState, String> {
}
