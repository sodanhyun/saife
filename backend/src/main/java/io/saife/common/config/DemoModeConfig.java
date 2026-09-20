package io.saife.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 데모 모드 판정.
 *
 * 심사위원은 API 키를 갖고 있지 않다. 키가 없다고 부팅에 실패하면 그 심사위원은
 * 앱을 안 본다. 키가 없으면 경고 한 줄을 남기고 픽스처 응답으로 내려간다.
 *
 * 무대에서도 같은 스위치를 쓴다 — 기본은 라이브이고, 네트워크 장애 시에만
 * {@code SAIFE_DEMO_MODE=true}로 전환한다. 전환 절차 자체가 리허설 대상이다.
 * 규칙: {@code .claude/rules/deployment.md}
 */
@Configuration
@Slf4j
@Getter
public class DemoModeConfig {

    /** 명시적으로 켠 데모 모드 (무대 오프라인 폴백) */
    @Value("${saife.demo-mode:false}")
    private boolean forcedDemoMode;

    @Value("${spring.ai.google.genai.api-key:}")
    private String geminiApiKey;

    private boolean demoMode;

    @PostConstruct
    void resolve() {
        // DemoModeEnvironmentPostProcessor가 자리표시자를 넣었을 수 있다.
        // 자리표시자는 "키 있음"이 아니라 "키 없음"으로 읽어야 한다.
        boolean keyMissing = geminiApiKey == null
                || geminiApiKey.isBlank()
                || DemoModeEnvironmentPostProcessor.PLACEHOLDER_KEY.equals(geminiApiKey);
        this.demoMode = forcedDemoMode || keyMissing;

        if (forcedDemoMode) {
            log.warn("""

                    ════════════════════════════════════════════════════════════
                     SAIFE 데모 모드 — 명시적으로 켜짐 (SAIFE_DEMO_MODE=true)
                     모델 응답을 픽스처로 대체합니다. 도구는 로컬 캐시에 대해
                     실제로 실행됩니다.
                    ════════════════════════════════════════════════════════════""");
        } else if (keyMissing) {
            log.warn("""

                    ════════════════════════════════════════════════════════════
                     GEMINI_API_KEY 가 없습니다 → 데모 모드로 기동합니다.
                     앱은 정상 동작하며, 모델 응답만 픽스처로 대체됩니다.
                     실제 모델을 쓰려면 .env 에 GEMINI_API_KEY 를 넣고 재기동하세요.
                    ════════════════════════════════════════════════════════════""");
        } else {
            log.info("SAIFE 라이브 모드 — Gemini 실호출");
        }
    }
}
