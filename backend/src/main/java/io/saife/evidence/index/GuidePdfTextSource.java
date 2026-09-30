package io.saife.evidence.index;

import io.saife.evidence.chunk.PdfTextExtractor;
import io.saife.evidence.media.MediaCache;
import io.saife.evidence.media.MediaKeys;
import io.saife.publicapi.domain.KoshaGuide;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KOSHA GUIDE 지침 PDF → 페이지별 텍스트. 파일 자체는 {@link MediaCache}가 갖고 있고,
 * 캐시 키 규약({@code "guide/{guideNo}"})을 {@code MediaController}(지침 PDF 프록시)와
 * 공유한다 — 인덱스 빌드와 프록시가 항상 같은 디스크 파일을 본다.
 *
 * <p><b>fix round 1(R26)</b>: {@code guideNo} 검증도 프록시와 <b>동일한 규칙</b>을 쓴다
 * ({@link MediaKeys#isValidGuideNo(String)}). 이전 버전은 허용 문자 밖을 {@code _}로
 * 치환해 프록시(검증 후 404)와 다른 키를 만들 수 있었다 — 치환은 하지 않는다. 규칙을
 * 벗어난 {@code guideNo}는 아예 캐시를 건드리지 않고 빈 리스트로 건너뛴다.
 *
 * <p>다운로드 실패·원본 URL 없음·PDF 파싱 실패는 전부 빈 리스트로 흡수하고 절대 던지지 않는다.
 * 지침 본문은 "있으면 근거가 두꺼워지는" 부가 자료이지 필수 경로가 아니다 — 이 클래스가 예외를
 * 던지면 {@code IndexBuilder}가 멈추고, 표지 청크만으로도 충분히 동작해야 할 인덱스 빌드 전체가
 * 지침 PDF 한 건 때문에 막힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuidePdfTextSource implements GuideTextSource {
    private final MediaCache cache;

    /** 절단선 ③: false면 PDF를 아예 받지 않고 빈 목록을 돌려준다 — 표지 청크만 생성된다 */
    @Value("${saife.index.guide-pdf-text:true}")
    private boolean guidePdfTextEnabled = true;

    @PostConstruct
    void logIfDisabled() {
        if (!guidePdfTextEnabled) {
            log.info("[GUIDE-PDF] saife.index.guide-pdf-text=false — 지침 PDF 본문을 받지 않고 표지 청크만 생성한다");
        }
    }

    @Override
    public List<String> pagesOf(KoshaGuide guide) {
        if (!guidePdfTextEnabled) {
            return List.of();
        }
        if (guide.getFileDownloadUrl() == null) {
            return List.of();
        }
        String guideNo = guide.getGuideNo();
        if (!MediaKeys.isValidGuideNo(guideNo)) {
            log.warn("[GUIDE-PDF] guideNo 형식 불허 — 본문 생략 guideNo={}", guideNo);
            return List.of();
        }
        Optional<Path> cached = cache.fetch("guide/" + guideNo, guide.getFileDownloadUrl(), "pdf");
        if (cached.isEmpty()) {
            log.warn("[GUIDE-PDF] PDF 원본을 가져오지 못함 guideNo={}", guideNo);
            return List.of();
        }
        try {
            return PdfTextExtractor.extract(Files.readAllBytes(cached.get()));
        } catch (Exception e) {
            log.warn("[GUIDE-PDF] PDF 읽기 실패 guideNo={}: {}", guideNo, e.getMessage());
            return List.of();
        }
    }
}
