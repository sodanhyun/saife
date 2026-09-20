package io.saife.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 보안 설정.
 *
 * <p>이 프로젝트의 권한 모델은 의도적으로 얕다. 역할은 {@code WORKER} / {@code MANAGER}
 * 둘뿐이고 가상 사업장 1곳이라 테넌시도 없다. 세분화된 원자 권한·카탈로그·해시 동기화를
 * 만들지 않는다 — 이번 범위에서 보상받지 못하는 표면적이다.
 * (상세: {@code .claude/rules/api-contract.md})
 *
 * <p><b>현재 상태</b>: 로그인 UI가 아직 없어 데모 경로를 열어두었다.
 * JWT 발급·검증 필터는 승인 큐(MANAGER 전용 화면)를 붙일 때 같이 넣는다.
 * 그때까지 쓰기 API도 열려 있으므로 <b>공개 네트워크에 띄우지 않는다.</b>
 *
 * <p>CSRF를 끄는 이유: REST + SSE이고 세션을 쓰지 않는다(무상태).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 헬스체크 — docker-compose healthcheck가 읽는다
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // SSE 프리플라이트
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 데모 경로 (JWT 도입 전까지)
                        .requestMatchers("/api/**").permitAll()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }
}
