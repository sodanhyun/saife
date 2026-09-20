package io.saife.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 키가 없을 때 기동을 살리는 환경 후처리기.
 *
 * <p><b>왜 필요한가.</b> 심사위원은 API 키를 갖고 있지 않다. 그런데 Spring AI의
 * Google GenAI 임베딩 autoconfiguration은 api-key가 비어 있으면 Vertex AI 모드로
 * 해석해 {@code "Google GenAI project-id must be set!"}로 컨텍스트를 죽인다.
 * {@code DemoModeConfig}가 경고를 찍어도 그 뒤에 기동이 막혀 아무 소용이 없다.
 *
 * <p><b>무엇을 하는가.</b> 키가 비어 있으면 자리표시자 키를 주입해 autoconfiguration을
 * 통과시키고 {@code saife.demo-mode=true}를 켠다. 실제 호출은 데모 모드가 픽스처로
 * 가로채므로 자리표시자 키가 밖으로 나갈 일은 없다.
 *
 * <p>키가 비어 있을 때만 동작하므로 사용자가 준 진짜 키를 덮어쓸 일은 없다.
 *
 * <p>규칙: {@code .claude/rules/deployment.md} 제1원칙.
 */
public class DemoModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 실제 호출에 쓰이지 않는다. autoconfiguration을 통과시키기 위한 자리표시자. */
    static final String PLACEHOLDER_KEY = "demo-mode-no-key";

    private static final String PROPERTY_SOURCE_NAME = "saifeDemoModeDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String key = env.getProperty("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            key = env.getProperty("spring.ai.google.genai.api-key");
        }

        boolean keyMissing = key == null || key.isBlank();
        if (!keyMissing) {
            return; // 진짜 키가 있으면 건드리지 않는다
        }

        Map<String, Object> props = new HashMap<>();
        props.put("spring.ai.google.genai.api-key", PLACEHOLDER_KEY);
        props.put("spring.ai.google.genai.embedding.api-key", PLACEHOLDER_KEY);
        props.put("saife.demo-mode", "true");

        // addFirst여야 한다. application.yml이 ${GEMINI_API_KEY:}를 빈 문자열로 이미
        // 해석해 두기 때문에 addLast로는 그 빈 값에 밀린다. 진짜 키가 있으면 위에서
        // 일찍 반환하므로 사용자 값을 덮어쓸 일은 없다.
        env.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
    }

    @Override
    public int getOrder() {
        // ConfigDataEnvironmentPostProcessor(application.yml 로딩) 이후에 돌아야
        // yml에 적힌 키를 볼 수 있다.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
