package io.saife.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * 최종 리뷰 F1 가드 — 저장소에 동봉되는 시드와 실험 결과 JSON에 자격증명이 없는지.
 *
 * <p>법제처 OC(기관코드)가 조문 링크에 섞여 {@code law_article.jsonl.gz} 2,303행과
 * {@code evidence_smoke_result.json}으로 새어 나간 적이 있다. OC는 재발급이 안 되는 값이라
 * 다시 새지 않도록 파일을 통째로 스트리밍해 URL 파라미터 모양만 검사한다.
 *
 * <p><b>실패 메시지에 일치한 값을 싣지 않는다</b> — 파일명·줄 번호·패턴 이름만 남긴다.
 * 임베딩(base64) 필드는 무작위 문자열이라 오탐을 막기 위해 검사 전에 걷어낸다.
 */
class SeedSecretsScanTest {

    private static final Path SEED_DIR = Path.of("src/main/resources/seed");
    private static final Path EXPERIMENTS_DIR = Path.of("../docs/experiments");

    private static final Pattern EMBEDDING = Pattern.compile("\"embedding\":\"[^\"]*\"");
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("[?&]OC="),
            Pattern.compile("serviceKey="),
            Pattern.compile("AIza[0-9A-Za-z_-]{20,}"));

    @Test
    void 동봉_시드와_실험_결과에_자격증명이_없다() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(SEED_DIR)) {
            s.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        assertThat(files).as("시드 디렉터리가 비어 있으면 검사가 무의미하다").isNotEmpty();
        // 실험 결과는 저장소 루트의 docs/ 아래라 테스트 classpath에 없다 — 작업 디렉터리(backend/) 기준으로 닿으면 같이 본다
        if (Files.isDirectory(EXPERIMENTS_DIR)) {
            try (Stream<Path> s = Files.list(EXPERIMENTS_DIR)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
            }
        }

        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            scan(f, hits);
        }
        assertThat(hits).as("자격증명 모양이 발견됐다(값은 싣지 않는다)").isEmpty();
    }

    private static void scan(Path f, List<String> hits) throws IOException {
        try (InputStream raw = Files.newInputStream(f);
             InputStream in = f.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int no = 0;
            while ((line = r.readLine()) != null) {
                no++;
                String text = EMBEDDING.matcher(line).replaceAll("");
                for (Pattern p : SECRETS) {
                    if (p.matcher(text).find()) {
                        hits.add(f.getFileName() + ":" + no + " /" + p.pattern() + "/");
                    }
                }
            }
        }
    }
}
