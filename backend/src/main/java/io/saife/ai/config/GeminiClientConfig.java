package io.saife.ai.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini API 클라이언트 설정.
 * Spring AI 자동설정의 Client 빈을 오버라이드하여 HTTP 타임아웃을 명시적으로 설정한다.
 * 복잡한 툴 호출 체인에서 기본 타임아웃이 부족할 수 있으므로 180초로 확장한다.
 *
 * SDK 레벨 HttpRetryOptions는 사용하지 않음 — 429/5xx 재시도는 ChatAgentService 커스텀 로직이 전담.
 * 재시도 제어권을 애플리케이션 계층에 일원화하여 최대 횟수/카운팅을 명확하게 관리한다.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class GeminiClientConfig {

    private static final int HTTP_TIMEOUT_MS = 180_000; // 180초

    @Bean
    public Client googleGenAiClient(GoogleGenAiConnectionProperties connectionProperties) {
        log.info("Gemini Client 생성 — HTTP 타임아웃: {}ms, SDK 재시도: 없음 (커스텀 로직 전담)",
                HTTP_TIMEOUT_MS);

        return Client.builder()
                .apiKey(connectionProperties.getApiKey())
                .httpOptions(HttpOptions.builder()
                        .timeout(HTTP_TIMEOUT_MS)
                        .build())
                .build();
    }
}
