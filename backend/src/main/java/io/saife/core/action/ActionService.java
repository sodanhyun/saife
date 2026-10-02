package io.saife.core.action;

import io.saife.common.error.ApiExceptions.ConflictException;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.AssessmentHazard;
import io.saife.core.domain.Hazard;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.HazardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 감소대책 등록과 이행 완료.
 *
 * <p>UC1 루프의 끝이다: 사진 → 후보 → <b>사람이 채택</b> → 대책 등록 → 이행 완료.
 * 여기서 만든 조치는 같은 위험요인(=같은 설비 ID)에 매달리므로 별도 연동 없이
 * UC4 타임라인, 홈 카드(미이행 수), 오늘 할 일(기한 임박), UC3 브리핑(미이행 경고)이
 * 같은 행을 읽는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActionService {

    private static final int CONTENT_MAX = 500;
    private static final int OWNER_MAX = 100;
    private static final int GUIDE_REF_MAX = 200;

    private final ActionRepository actionRepository;
    private final HazardRepository hazardRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;

    /**
     * 채택된 위험요인에 감소대책을 등록한다.
     *
     * <p><b>채택 전 후보에는 대책을 걸 수 없다.</b> AI가 올린 후보는 사람이 채택해야
     * 위험요인이 되고, 대책은 위험요인에만 붙는다. 이 순서가 무너지면 "AI가 대책까지
     * 확정했다"가 되어 핵심 원칙(AI는 후보만 제안한다)이 깨진다.
     */
    @Transactional
    public ActionDtos.ActionView createForHazard(Long hazardId, ActionDtos.CreateActionRequest req) {
        Hazard hazard = hazardRepository.findById(hazardId).orElseThrow(
                () -> NotFoundException.of("위험요인", hazardId));

        if (hazard.isAiSuggested() && !Boolean.TRUE.equals(hazard.getAiAdopted())) {
            throw new ConflictException("채택한 위험요인에만 감소대책을 등록할 수 있습니다. 먼저 후보를 채택하십시오.");
        }
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw new InvalidRequestException("감소대책 내용을 입력하십시오.");
        }
        String content = req.content().strip();
        if (content.length() > CONTENT_MAX) {
            throw new InvalidRequestException("감소대책 내용은 %d자 이하로 입력하십시오.".formatted(CONTENT_MAX));
        }
        String owner = trimToNull(req.owner());
        if (owner != null && owner.length() > OWNER_MAX) {
            throw new InvalidRequestException("담당자는 %d자 이하로 입력하십시오.".formatted(OWNER_MAX));
        }
        String guideRef = trimToNull(req.guideRef());
        if (guideRef != null && guideRef.length() > GUIDE_REF_MAX) {
            guideRef = guideRef.substring(0, GUIDE_REF_MAX);
        }

        Long assessmentId = resolveAssessment(hazardId, req.assessmentId());

        // 같은 평가에서 같은 위험요인에 대책을 두 번 걸지 않는다 (버튼 연타 방어)
        Optional<Action> existing = findFor(hazardId, assessmentId);
        if (existing.isPresent()) {
            throw new ConflictException("이 평가에서 이미 등록한 감소대책이 있습니다.");
        }

        Action saved = actionRepository.save(Action.builder()
                .hazardId(hazardId)
                .assessmentId(assessmentId)
                .content(content)
                .owner(owner)
                .dueDate(req.dueDate())
                .status(ActionStatus.PENDING)
                .guideRef(guideRef)
                .build());

        log.info("[UC1] 감소대책 등록 조치={} 위험요인={} 평가={} 기한={}",
                saved.getId(), hazardId, assessmentId, saved.getDueDate());
        return ActionDtos.ActionView.of(saved, hazard.getEquipmentId());
    }

    /**
     * 이행 완료. <b>멱등이다</b> — 이미 완료된 조치는 완료 시각을 바꾸지 않고 그대로 돌려준다.
     * 완료 시각은 증빙이라 두 번 눌렀다고 뒤로 밀리면 안 된다.
     */
    @Transactional
    public ActionDtos.ActionView complete(Long actionId) {
        Action action = actionRepository.findById(actionId).orElseThrow(
                () -> NotFoundException.of("조치", actionId));

        if (action.getStatus() != ActionStatus.DONE) {
            action.complete(action.getEvidencePath());
            action = actionRepository.save(action);
            log.info("[UC1] 조치 {} 이행 완료", actionId);
        }
        Long equipmentId = hazardRepository.findById(action.getHazardId())
                .map(Hazard::getEquipmentId).orElse(null);
        return ActionDtos.ActionView.of(action, equipmentId);
    }

    /** 이 평가에서 이 위험요인에 등록된 조치 (가장 최근 1건) */
    @Transactional(readOnly = true)
    public Optional<Action> findFor(Long hazardId, Long assessmentId) {
        if (hazardId == null || assessmentId == null) {
            return Optional.empty();
        }
        return actionRepository.findByHazardId(hazardId).stream()
                .filter(a -> assessmentId.equals(a.getAssessmentId()))
                .max(Comparator.comparing(Action::getId));
    }

    /** 다른 평가에서 걸어 둔, 아직 끝나지 않은 조치 (가장 오래된 기한 1건). 재확인 후보에 띄운다 */
    @Transactional(readOnly = true)
    public Optional<Action> findPriorOpen(Long hazardId, Long exceptAssessmentId) {
        if (hazardId == null) {
            return Optional.empty();
        }
        return actionRepository.findByHazardId(hazardId).stream()
                .filter(a -> a.getStatus() != ActionStatus.DONE)
                .filter(a -> exceptAssessmentId == null || !exceptAssessmentId.equals(a.getAssessmentId()))
                .min(Comparator.comparing(Action::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /** 요청에 평가가 있으면 그 평가에 이 위험요인이 있는지 확인하고, 없으면 가장 최근 평가를 고른다 */
    private Long resolveAssessment(Long hazardId, Long requested) {
        List<AssessmentHazard> history = assessmentHazardRepository.findHistoryByHazardId(hazardId);
        if (requested != null) {
            boolean linked = history.stream().anyMatch(l -> requested.equals(l.getAssessmentId()));
            if (!linked) {
                throw new InvalidRequestException(
                        "평가 %d에 이 위험요인(%d)이 없습니다.".formatted(requested, hazardId));
            }
            return requested;
        }
        return history.stream()
                .max(Comparator.comparing(AssessmentHazard::getAssessmentId))
                .map(AssessmentHazard::getAssessmentId)
                .orElse(null);
    }

    private String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
