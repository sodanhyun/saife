package io.saife;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * SAIFE — 소규모 제조 사업장 위험성평가 AI Agent.
 */
@SpringBootApplication
public class SaifeApplication {

    /**
     * 애플리케이션 기준 시간대를 한국으로 고정한다.
     *
     * <p><b>법정 문서를 만드는 앱이다.</b> 산업재해조사표의 발생 일시, 브리핑 확인 시각,
     * 조치 기한이 전부 시각 그대로 의미를 갖는다. 기본 시간대를 두면 JVM이 도는
     * 환경(도커 이미지는 대개 UTC)에 따라 <b>같은 사고가 14:20으로도 05:20으로도</b>
     * 찍힌다. 실제로 조사표에 05:20이 찍혔다 (2026-09-20 실측).
     *
     * <p>컨테이너·개발 머신·심사위원 노트북에서 같은 값이 나와야 하므로 코드에서 고정한다.
     * 환경변수 {@code TZ}에 의존하면 docker-compose를 안 쓰는 경로에서 다시 어긋난다.
     */
    @PostConstruct
    void fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        // @PostConstruct보다 먼저 도는 코드(Flyway 등)도 같은 시간대를 보게 한다
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(SaifeApplication.class, args);
    }
}
