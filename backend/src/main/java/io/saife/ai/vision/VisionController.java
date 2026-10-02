package io.saife.ai.vision;

import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.service.SseService;
import io.saife.core.action.ActionDtos;
import io.saife.core.action.ActionService;
import io.saife.core.domain.Equipment;
import io.saife.core.repository.EquipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UC1 — 사진 업로드 → 빠진 안전조치 탐지.
 *
 * <p>업로드 응답은 SSE 스트림이다. 판독이 3~10초 걸려(2026-09-20 실측) 동기 응답으로
 * 두면 화면이 그 시간 동안 죽은 것처럼 보인다.
 */
@RestController
@RequestMapping("/api/vision")
@RequiredArgsConstructor
@Slf4j
public class VisionController {

    private static final Long DEMO_SITE_ID = 1L;
    private static final long STREAM_TIMEOUT_MS = 120_000;

    private final VisionAssessmentService visionAssessmentService;
    private final EquipmentRepository equipmentRepository;
    private final SseService sseService;
    private final ActionService actionService;

    /** 판독은 블로킹이라 요청 스레드를 붙잡지 않는다 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Value("${saife.upload-dir:./data/uploads}")
    private String uploadDir;

    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyze(@RequestParam("image") MultipartFile image,
                              @RequestParam(value = "equipmentId", required = false) Long equipmentId)
            throws IOException {

        if (image == null || image.isEmpty()) {
            throw new InvalidRequestException("이미지가 비어 있습니다. 사진을 선택해 주세요.");
        }

        byte[] bytes = image.getBytes();
        String contentType = image.getContentType();

        // 이미지가 아닌 파일은 여기서 막는다. 모델에 보내봐야 실패하고,
        // 그때는 이미 SSE를 열고 평가 레코드까지 만든 뒤다
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new InvalidRequestException(
                    "이미지 파일만 올릴 수 있습니다 (받은 형식: %s)".formatted(contentType));
        }
        String photoPath = store(bytes, image.getOriginalFilename());

        Long processId = equipmentId == null ? null
                : equipmentRepository.findById(equipmentId).map(Equipment::getProcessId).orElse(null);

        // 비동기 디스패치 "전에" 판독 중 상태를 커밋한다 (sse-streaming.md)
        Long assessmentId = visionAssessmentService.markAnalyzing(DEMO_SITE_ID);

        String correlationId = UUID.randomUUID().toString().replace("-", "");
        SseService.SseSession session =
                sseService.createSession(correlationId, STREAM_TIMEOUT_MS, null);

        log.info("[UC1] 사진 판독 시작 평가={} 설비={} 파일={}",
                assessmentId, equipmentId, image.getOriginalFilename());

        executor.submit(() -> visionAssessmentService.analyze(
                assessmentId, DEMO_SITE_ID, equipmentId, processId,
                bytes, contentType, photoPath, session.sessionId(), correlationId));

        return session.emitter();
    }

    /** 새로고침 복원용 — 진행 중이면 status가 ANALYZING으로 온다 */
    @GetMapping("/result/{assessmentId}")
    public ResponseEntity<VisionAssessmentService.AnalysisResult> result(
            @PathVariable Long assessmentId) {
        return ResponseEntity.ok(visionAssessmentService.result(assessmentId));
    }

    /** 후보 채택 — 사람이 하는 일이다 */
    @PostMapping("/hazard/{hazardId}/adopt")
    public ResponseEntity<VisionAssessmentService.Candidate> adopt(@PathVariable Long hazardId) {
        return ResponseEntity.ok(visionAssessmentService.decideCandidate(hazardId, true));
    }

    @PostMapping("/hazard/{hazardId}/reject")
    public ResponseEntity<VisionAssessmentService.Candidate> reject(@PathVariable Long hazardId) {
        return ResponseEntity.ok(visionAssessmentService.decideCandidate(hazardId, false));
    }

    /**
     * 감소대책 등록. <b>채택한 위험요인에만</b> 걸 수 있다(아니면 409).
     * 만든 조치는 PENDING으로 시작하고, 이행 완료는 {@code POST /api/action/{id}/complete}.
     */
    @PostMapping("/hazard/{hazardId}/action")
    public ResponseEntity<ActionDtos.ActionView> createAction(
            @PathVariable Long hazardId, @RequestBody ActionDtos.CreateActionRequest request) {
        return ResponseEntity.ok(actionService.createForHazard(hazardId, request));
    }

    /** 후보 채택률 — 성과 지표. 정확도가 아니다 */
    @GetMapping("/adoption-rate")
    public ResponseEntity<Map<String, Object>> adoptionRate() {
        return ResponseEntity.ok(visionAssessmentService.adoptionRate(DEMO_SITE_ID));
    }

    /**
     * 업로드 파일 저장.
     *
     * <p>저장 경로는 {@code /data/}라 gitignore 대상이다. 현장 사진이 저장소에
     * 딸려 들어가면 안 된다.
     */
    private String store(byte[] bytes, String originalName) throws IOException {
        String ext = extensionOf(originalName);
        Path dir = Paths.get(uploadDir, LocalDate.now().toString());
        Files.createDirectories(dir);

        String name = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = dir.resolve(name);
        Files.write(target, bytes);
        return target.toString().replace('\\', '/');
    }

    private String extensionOf(String originalName) {
        if (originalName == null) {
            return ".jpg";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return ".jpg";
        }
        String ext = originalName.substring(dot).toLowerCase();
        // 확장자를 그대로 믿지 않는다. 알려진 이미지 확장자만 통과시킨다
        return switch (ext) {
            case ".jpg", ".jpeg", ".png", ".webp", ".heic" -> ext;
            default -> ".jpg";
        };
    }
}
