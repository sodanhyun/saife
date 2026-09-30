package io.saife.evidence;

import io.saife.evidence.index.IndexBuilder;
import io.saife.evidence.index.SeedExporter;
import io.saife.evidence.media.MediaPrefetcher;
import io.saife.evidence.service.LawArticleService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 근거 계층 운영 도구. 시연 중에 부르지 않는다 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class EvidenceAdminController {
    /** 기본 내보내기 위치. 앱 작업 디렉터리 기준 — 리포 루트에서 기동했을 때 실제 시드 폴더를 가리킨다 */
    private static final Path DEFAULT_EXPORT_DIR = Path.of("backend/src/main/resources/seed");


    private final LawArticleService lawArticleService;
    private final IndexBuilder indexBuilder;
    private final SeedExporter seedExporter;
    private final MediaPrefetcher mediaPrefetcher;

    /** 3개 법령 전체 조문 수집 (OC 필요) */
    @PostMapping("/law/crawl")
    public ResponseEntity<Map<String, Integer>> crawlLaw() {
        return ResponseEntity.ok(lawArticleService.crawlAll());
    }

    /**
     * kind=CASE_FATALITY|CASE_DISASTER|CASE|GUIDE|LAW|ALL. 키 필요(임베딩). 재실행은 체크포인트부터.
     * CASE는 사망(1040)·재해(1060) 두 kind를 모두 재구축한다 — EvidenceKind에 통합 CASE 값이 없어서다.
     *
     * <p>최종 리뷰 F5: 데모 모드면 아무것도 지우지 않고 SKIPPED. 완료(DONE)된 kind는 {@code force=true}일
     * 때만 지우고 다시 만든다 — 없으면 기존 리포트만 돌려준다.
     */
    @PostMapping("/index/rebuild")
    public ResponseEntity<List<IndexBuilder.IndexReport>> rebuild(@RequestParam(defaultValue = "ALL") String kind,
                                                                  @RequestParam(defaultValue = "false") boolean force) {
        List<EvidenceKind> kinds = switch (kind.toUpperCase()) {
            case "ALL" -> List.of(EvidenceKind.CASE_FATALITY, EvidenceKind.CASE_DISASTER, EvidenceKind.GUIDE, EvidenceKind.LAW);
            case "CASE" -> List.of(EvidenceKind.CASE_FATALITY, EvidenceKind.CASE_DISASTER);
            default -> List.of(EvidenceKind.valueOf(kind.toUpperCase()));
        };
        return ResponseEntity.ok(kinds.stream().map(k -> indexBuilder.rebuild(k, force)).toList());
    }

    /**
     * 5개 캐시 테이블 → seed/*.jsonl.gz. 기본 위치는 {@link #DEFAULT_EXPORT_DIR}이고,
     * dir 파라미터로 다른 위치를 지정할 수 있다.
     *
     * <p>최종 리뷰 F17: 파일을 쓰는 동작이라 GET이 아니라 POST다. {@code dir}은 앱 작업 디렉터리
     * <b>아래</b>로만 허용한다 — 정규화한 경로가 작업 디렉터리 밖(절대경로·{@code ..} 탈출)이면 거부한다.
     */
    @PostMapping("/index/export")
    public ResponseEntity<Map<String, Integer>> export(@RequestParam(required = false) String dir) throws IOException {
        Path target = resolveExportDir(dir);
        log.info("[SEED] /api/admin/index/export 요청 dir={}", target.toAbsolutePath());
        return ResponseEntity.ok(seedExporter.exportAll(target));
    }

    /** 작업 디렉터리 아래로 정규화한 내보내기 위치. 밖으로 나가면 {@link IllegalArgumentException} */
    static Path resolveExportDir(String dir) {
        Path base = Path.of("").toAbsolutePath().normalize();
        Path target = (dir == null || dir.isBlank() ? base.resolve(DEFAULT_EXPORT_DIR) : base.resolve(dir)).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("작업 디렉터리 밖 경로는 허용되지 않습니다: " + dir);
        }
        return target;
    }

    /**
     * 리허설 전 1회 실행(무대 전날 재기동 직후에도 한 번 더). 무대에서 원본 호스트가 안 열려도 카드가
     * 비지 않도록 사진·PDF를 디스크(볼륨 {@code saife-media})에 미리 채워 둔다
     * (.claude/rules/deployment.md — 무대 외부 API 호출 0).
     *
     * <p>최종 리뷰 F6: 시연이 실제로 띄우는 카드부터 받는다 — 시드 설비의 위험요인 축으로 도구와 같은
     * 검색을 돌려 나온 사례 사진·지침 PDF가 먼저, 그다음 상한까지 채운다. 상세는 {@link MediaPrefetcher}.
     * {@code scenario} 값은 향후 다른 시나리오를 두기 위한 자리이고 현재는 값과 무관하게 같은 범위로 동작한다.
     */
    @PostMapping("/media/prefetch")
    public ResponseEntity<Map<String, Integer>> prefetchMedia(@RequestParam(defaultValue = "demo") String scenario) {
        MediaPrefetcher.Result r = mediaPrefetcher.prefetchDemo();
        log.info("[MEDIA] 프리페치 scenario={} photos={} pdfs={}", scenario, r.photos(), r.pdfs());
        Map<String, Integer> body = new LinkedHashMap<>();
        body.put("photos", r.photos());
        body.put("pdfs", r.pdfs());
        body.put("demoPhotos", r.demoPhotos());
        body.put("demoPdfs", r.demoPdfs());
        return ResponseEntity.ok(body);
    }
}
