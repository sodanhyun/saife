package io.saife.ai.config;

import io.saife.ai.tools.ToolResult;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 도구 호출 설정.
 * GoogleGenAiChatModel의 parseJsonToMap()은 도구 결과를 반드시 JSON으로 파싱하므로,
 * 도구 실행 예외 시에도 JSON 형식으로 감싸서 반환해야 한다.
 */
@Configuration
@Slf4j
public class ToolCallingConfig {

    /**
     * 도구 실행 예외 처리기 — 에러 메시지를 JSON으로 감싸서 반환.
     * 기본 DefaultToolExecutionExceptionProcessor는 plain text를 반환하여
     * GoogleGenAiChatModel.parseJsonToMap()에서 JsonParseException이 발생한다.
     */
    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        // 기본 프로세서를 위임 대상으로 사용 (rethrow 로직 유지)
        DefaultToolExecutionExceptionProcessor delegate =
                DefaultToolExecutionExceptionProcessor.builder().build();

        return exception -> {
            try {
                // 기본 프로세서가 rethrow하면 그대로 전파
                String rawMessage = delegate.process(exception);
                // plain text를 JSON으로 감싸서 반환
                log.warn("[AI Tool] 도구 실행 예외 (JSON 래핑): {}", rawMessage);
                return ToolResult.of(rawMessage != null ? rawMessage : "도구 실행 중 오류가 발생했습니다.");
            } catch (RuntimeException rethrown) {
                // 기본 프로세서가 rethrow 판단한 예외는 그대로 전파
                throw rethrown;
            }
        };
    }

    /**
     * ToolCallingManager 빈 — DefaultToolCallingManager를 FuzzyToolCallingManager로 감싸 등록.
     * Gemini가 등록된 camelCase 툴 이름을 snake_case로 환각해 호출하는 경우 자동 정규화한다.
     * GoogleGenAiChatModel 생성자가 GoogleGenAiToolCallingManager로 추가 래핑하므로,
     * 최종 체인은 GoogleGenAiToolCallingManager → FuzzyToolCallingManager → DefaultToolCallingManager.
     */
    @Bean
    ToolCallingManager toolCallingManager(
            ObservationRegistry observationRegistry,
            ToolCallbackResolver toolCallbackResolver,
            ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
        DefaultToolCallingManager defaultManager = DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .toolCallbackResolver(toolCallbackResolver)
                .toolExecutionExceptionProcessor(toolExecutionExceptionProcessor)
                .build();
        return new FuzzyToolCallingManager(defaultManager);
    }
}
