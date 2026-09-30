package io.saife.publicapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.saife.publicapi.repository.CrawlCheckpointRepository;
import io.saife.publicapi.repository.KoshaGuideRepository;
import io.saife.publicapi.repository.PublicCaseRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code checkLatest}는 무대에서 도구를 막으면 안 되므로, 키가 없거나 HTTP가 실패해도
 * {@link java.util.Optional#empty()}로 조용히 떨어지는지를 검증한다.
 *
 * <p>실제 HttpClient는 생성자 주입이 아니라 인스턴스 초기화 필드라 목(mock)으로
 * 갈아끼울 수 없다 — 대신 JDK 내장 {@link HttpServer}로 로컬 서버를 띄워 실제 왕복을 확인한다.
 */
class PublicApiCrawlerCheckLatestTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private PublicApiCrawler crawler() {
        return new PublicApiCrawler(mock(PublicCaseRepository.class), mock(KoshaGuideRepository.class),
                mock(CrawlCheckpointRepository.class), mock(AccidentTypeClassifier.class));
    }

    private HttpServer startServer(String path, int status, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        s.start();
        return s;
    }

    @Test
    void 키가_있으면_공단_응답에서_totalCount를_읽는다() throws Exception {
        server = startServer("/news_api02/getNews_api02", 200, "{\"body\":{\"totalCount\":2940}}");
        PublicApiCrawler crawler = crawler();
        ReflectionTestUtils.setField(crawler, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(crawler, "fatalityKey", "test-key");

        Optional<PublicApiCrawler.LatestInfo> result = crawler.checkLatest("FATALITY");

        assertThat(result).isPresent();
        assertThat(result.get().totalCount()).isEqualTo(2940);
        assertThat(result.get().checkedAt()).isNotNull();
    }

    @Test
    void 키가_없으면_호출하지_않고_비운다() {
        PublicApiCrawler crawler = crawler();
        ReflectionTestUtils.setField(crawler, "baseUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(crawler, "fatalityKey", "");

        assertThat(crawler.checkLatest("FATALITY")).isEmpty();
    }

    @Test
    void 알수없는_데이터셋이면_비운다() {
        PublicApiCrawler crawler = crawler();
        ReflectionTestUtils.setField(crawler, "fatalityKey", "test-key");

        assertThat(crawler.checkLatest("NOT_A_DATASET")).isEmpty();
    }

    @Test
    void HTTP_실패면_예외를_던지지_않고_비운다() throws Exception {
        server = startServer("/news_api02/getNews_api02", 500, "{}");
        PublicApiCrawler crawler = crawler();
        ReflectionTestUtils.setField(crawler, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(crawler, "fatalityKey", "test-key");

        assertThat(crawler.checkLatest("FATALITY")).isEmpty();
    }
}
