package io.saife.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatProperties;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * GoogleGenAiChatModel 빈 등록 — 자동 구성된 빈을 SafeCandidateGoogleGenAiChatModel로 대체.
 *
 * autoconfig({@code GoogleGenAiChatAutoConfiguration#googleGenAiChatModel})는
 * {@code @ConditionalOnMissingBean} 패턴이므로, 본 @Bean 등록이 우선한다.
 *
 * 시그니처는 autoconfig와 동일하게 맞춰 의존성 누락 위험을 최소화한다.
 */
@Configuration(proxyBeanMethods = false)
public class GoogleGenAiChatModelConfig {

    @Bean
    GoogleGenAiChatModel googleGenAiChatModel(
            Client genAiClient,
            GoogleGenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObjectMapper objectMapper,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {

        ObservationRegistry registry = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        ToolExecutionEligibilityPredicate eligibilityPredicate = toolExecutionEligibilityPredicate
                .getIfUnique(DefaultToolExecutionEligibilityPredicate::new);

        SafeCandidateGoogleGenAiChatModel model = new SafeCandidateGoogleGenAiChatModel(
                genAiClient,
                chatProperties.getOptions(),
                toolCallingManager,
                retryTemplate,
                registry,
                eligibilityPredicate,
                objectMapper);

        observationConvention.ifAvailable(model::setObservationConvention);

        return model;
    }
}
