package io.saife.incident.service;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.RiskLevel;
import io.saife.core.domain.WorkProcess;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.ProcessRepository;
import io.saife.core.service.EquipmentMatcher;
import io.saife.dashboard.dto.TimelineDtos;
import io.saife.evidence.Evidence;
import io.saife.incident.domain.Incident;
import io.saife.incident.domain.IncidentSeverity;
import io.saife.incident.domain.IncidentType;
import io.saife.incident.domain.ReportStatus;
import io.saife.incident.repository.FollowUpRecordStore;
import io.saife.incident.dto.IncidentDtos;
import io.saife.incident.repository.IncidentRepository;
import io.saife.common.error.ApiExceptions.ConflictException;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.common.error.ApiExceptions.NotFoundException;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.domain.WorkPlanStatus;
import io.saife.workplan.repository.WorkPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UC2 오케스트레이션 — 사고 등록 한 번으로 네 가지가 동시에 일어난다.
 *
 * <ol>
 *   <li>법정 제출 기한 판정 (휴업 3일 이상 → 1개월)</li>
 *   <li>같은 설비의 이력 소환 — 평가·미이행 조치·작업 브리핑·과거 사고</li>
 *   <li>수시평가 자동 생성 + 재등급</li>
 *   <li>산업재해조사표 문안 초안</li>
 * </ol>
 *
 * <p>1~3은 결정론적 코드다. 모델은 4번에서만 문장을 쓴다. <b>모델이 죽어도 1~3은 돈다.</b>
 *
 * <p>이 순서는 의미가 있다. 소환(2)이 먼저 끝나야 그 결과를 근거로 문안(4)을 쓸 수 있고,
 * 소환 없이 쓴 문안은 일반론이 된다 — 그건 이미 있는 서식 템플릿과 다를 게 없다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentMatcher equipmentMatcher;
    private final EquipmentHistoryRecaller recaller;
    private final FollowUpAssessmentService followUpAssessmentService;
    private final IncidentReportDrafter drafter;
    private final IncidentEvidenceCollector evidenceCollector;
    private final WorkPlanRepository workPlanRepository;
    private final ProcessRepository processRepository;
    private final FollowUpRecordStore followUpRecords;
    private final io.saife.core.repository.AssessmentRepository assessmentRepository;

    // 자기 호출로 @Transactional 경계를 새로 열려면 프록시를 거쳐야 한다(VisionAssessmentService와 동일 패턴).
    // register()는 트랜잭션 밖에서 근거 수집을 해야 해서 그 자체는 @Transactional이 아니다 —
    // registerTransactional()/attachDraft()만 각자의 트랜잭션 경계를 갖고, 이 필드를 통해서만 불러야 한다.
    private final ObjectProvider<IncidentService> self;

    private static final String DISCLAIMER =
            "작성 보조 결과입니다. 법률 자문이 아니며 최종 확정과 제출은 담당자가 합니다.";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 시행규칙 제37조제2항제3호: 산업재해가 발생한 경우 관련 작업을 시작하기 전까지 수시평가 */
    public static final String FOLLOW_UP_LEGAL_BASIS = "시행규칙 제37조제2항제3호";

    /** 발생 일시 입력의 시계 오차 허용폭. 이보다 미래면 받지 않는다 */
    private static final long FUTURE_TOLERANCE_MINUTES = 5;

    /** 작업 보류 문구. 작업계획서 warningNote에 남고, 홈 "오늘 할 일"이 이 상태를 읽는다 */
    public static final String HOLD_WARNING = "작업 보류: 수시평가 완료 전 작업 재개 금지";

    /** 보류 대상 상태 — 진행 중인 작업 전 점검만 (완료·반려·초안은 대상이 아니다) */
    private static final List<WorkPlanStatus> AFFECTED_WORK_PLAN_STATUSES =
            List.of(WorkPlanStatus.SUBMITTED, WorkPlanStatus.APPROVED, WorkPlanStatus.CONDITIONAL);

    /**
     * 등록 트랜잭션이 끝난 뒤에도 필요한 조각을 담아 넘긴다.
     * {@code register()}가 이 레코드를 근거로 근거 수집·초안 작성을 트랜잭션 밖에서 이어간다.
     *
     * <p>패키지 전용(private 아님) — {@code IncidentServiceEvidenceTest}가 같은 패키지에서
     * {@code registerTransactional}을 스텁할 때 반환값을 직접 만들어야 한다.
     */
    record RegisterCore(Incident incident, EquipmentHistoryRecaller.Recall recall,
            TimelineDtos.RecallView recallView, IncidentDtos.ReportDuty reportDuty,
            IncidentDtos.FollowUpView followUpView, List<IncidentDtos.AffectedWorkPlan> affectedWorkPlans) {}

    /**
     * UC2 오케스트레이션 진입점. 등록 트랜잭션(1~3)과 근거 수집·조사표 초안(4)을 <b>의도적으로 분리</b>한다.
     *
     * <p><b>근거 검색 실패가 이미 커밋된 등록을 되돌리면 안 된다</b> — 사례 검색·조문 조회는
     * 외부 상태(캐시·pgvector·DB)에 기대므로 실패 가능성이 등급 판정·기한 계산보다 훨씬 높다.
     * 그래서 {@link #registerTransactional}이 끝나 트랜잭션이 커밋된 <b>뒤에야</b> 근거를 모은다
     * ({@code IncidentServiceEvidenceTest}가 근거 수집기 단위로 이 안전망을 검증한다).
     */
    public IncidentDtos.RegisterResponse register(Long siteId, IncidentDtos.RegisterRequest request) {
        RegisterCore core = self.getObject().registerTransactional(siteId, request);

        String caseQuery = caseQueryText(core.recall(), core.incident());
        IncidentEvidenceCollector.Collected collected =
                safeCollectEvidence(caseQuery, core.incident().getIncidentType());

        IncidentReportDrafter.Draft draft = drafter.draft(core.incident(), core.recall(), collected.all());
        Incident incident = safeAttachDraft(core.incident(), draft);

        List<IncidentDtos.CascadeStep> cascade = buildCascade(incident, core.recallView(), core.followUpView(),
                core.reportDuty(), core.affectedWorkPlans(), collected.similarCases());

        log.info("[UC2] 사고 {} 등록 완료, 설비 {}, 제출 {}, 수시평가 {}, 작업 보류 {}건, 근거 {}건",
                incident.getId(), incident.getEquipmentId(), incident.getReportStatus(),
                incident.getFollowUpAssessmentId(), core.affectedWorkPlans().size(), collected.all().size());

        return new IncidentDtos.RegisterResponse(
                summary(incident, core.recall().equipmentName()),
                core.reportDuty(),
                core.recallView(),
                core.followUpView(),
                new IncidentDtos.DraftView(draft.cause(), draft.prevention(),
                        draft.aiGenerated(), DISCLAIMER),
                cascade,
                core.affectedWorkPlans(),
                collected.similarCases(),
                collected.all());
    }

    /**
     * 사고 등록의 결정론적 구간(1~3) — 전부 한 트랜잭션 안에서 원자적으로 끝난다.
     * 근거 수집·조사표 초안(4)은 여기 없다 — {@link #register}가 이 메서드가 반환한
     * 뒤(=커밋된 뒤)에 트랜잭션 밖에서 이어간다.
     *
     * <p><b>반드시 {@code self.getObject()}를 거쳐 호출한다.</b> 같은 빈 안에서 {@code this}로
     * 직접 부르면 프록시를 안 거쳐 {@code @Transactional}이 적용되지 않는다
     * (VisionAssessmentService와 동일한 자기호출 함정 — {@code .claude/rules} 참고).
     */
    @Transactional
    public RegisterCore registerTransactional(Long siteId, IncidentDtos.RegisterRequest request) {
        validate(request);

        OffsetDateTime occurredAt = request.occurredAt() != null
                ? request.occurredAt() : OffsetDateTime.now();

        Long equipmentId = resolveEquipmentId(siteId, request);

        Incident incident = Incident.builder()
                .siteId(siteId)
                .equipmentId(equipmentId)
                .workPlanId(request.workPlanId())
                .occurredAt(occurredAt)
                .victimName(request.victimName())
                .severity(request.severity())
                .leaveDays(request.severity() == IncidentSeverity.LOST_TIME ? request.leaveDays()
                        : request.severity() == IncidentSeverity.NEAR_MISS ? null : request.leaveDays())
                .incidentType(request.incidentType())
                .description(trimToNull(request.description()))
                .injuryType(request.severity() == IncidentSeverity.NEAR_MISS ? null : trimToNull(request.injuryType()))
                .injuryPart(request.severity() == IncidentSeverity.NEAR_MISS ? null : trimToNull(request.injuryPart()))
                .build();

        incident.decideReportDuty();
        incident = incidentRepository.save(incident);

        // knownAsOf = 사고가 기록된 시각. 이후에 생기는 수시평가를
        // "사고 전에 알고 있었다"의 근거로 쓰면 순환 논증이 된다
        EquipmentHistoryRecaller.Recall recall = recaller.recall(
                equipmentId, incident.getAccidentType(), occurredAt, incident.getCreatedAt(),
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);

        // 평가일은 사고보다 앞설 수 없다. 수시평가는 재해 발생 뒤,
        // 작업 재개 전에 하는 것이다. today를 그대로 쓰면 UC4 타임라인에서
        // 수시평가가 그걸 만든 사고보다 앞에 놀이고 화살표가 거꾸로 간다.
        LocalDate today = LocalDate.now(KST);
        LocalDate occurredOn = occurredAt.atZoneSameInstant(KST).toLocalDate();
        LocalDate assessedOn = today.isBefore(occurredOn) ? occurredOn : today;

        FollowUpAssessmentService.Result followUp =
                followUpAssessmentService.create(incident, assessedOn);
        incident.attachFollowUpAssessment(followUp.assessmentId());
        incident = incidentRepository.save(incident);

        // 사고 연쇄(2-2): 같은 설비의 진행 중 작업 전 점검을 작업 보류(HOLD)로 바꾼다.
        // 수시평가는 "관련 작업을 시작하기 전까지" 해야 하므로(시행규칙 제37조제2항제3호) 그 전에는 재개하지 않는다.
        List<IncidentDtos.AffectedWorkPlan> affectedWorkPlans = holdWorkPlans(incident);

        TimelineDtos.RecallView recallView = TimelineDtos.RecallView.from(recall, processNameOf(incident.getEquipmentId()));
        IncidentDtos.ReportDuty reportDuty = reportDuty(incident, today);
        IncidentDtos.FollowUpView followUpView = new IncidentDtos.FollowUpView(followUp.assessmentId(), "수시",
                FOLLOW_UP_LEGAL_BASIS, followUp.regraded(), followUp.newHazardId(), "DRAFT", null);

        return new RegisterCore(incident, recall, recallView, reportDuty, followUpView, affectedWorkPlans);
    }

    /**
     * 조사표 초안(원인·재발방지)을 별도 트랜잭션으로 저장한다. 근거 수집·모델 호출이
     * 끝난 뒤(이미 등록 트랜잭션이 커밋된 뒤) 불리므로, 여기서 실패해도 등록 자체는 살아 있다.
     */
    @Transactional
    public Incident attachDraft(Long incidentId, IncidentReportDrafter.Draft draft) {
        Incident incident = incidentRepository.findById(incidentId).orElseThrow(
                () -> new NotFoundException("사고를 찾을 수 없습니다."));
        incident.attachDraft(draft.cause(), draft.prevention());
        return incidentRepository.save(incident);
    }

    /**
     * 초안 저장 실패가 등록 응답을 깨면 안 된다(R54, CRITICAL). {@link #registerTransactional}이
     * 이미 커밋한 뒤라서, 여기서 예외를 던지면 클라이언트는 500을 받지만 사고·수시평가·경고는
     * 이미 DB에 남아 있다 — 그 상태에서 클라이언트가 재시도하면 사고가 중복 생성된다.
     *
     * <p>실패하면 커밋된 {@code committed}(초안 필드는 비어 있다)를 그대로 쓰고, 응답 조립은
     * 인메모리 {@code draft} 텍스트로 한다 — 화면엔 초안이 보이고, DB 저장은 다음 조회 때까지
     * 비어 있다. 그 다음 {@code detail()} 재조회는 초안을 다시 만들지 않으므로(저장된 값만
     * 읽는다) 빈 채로 남는다 — 담당자가 화면에서 확인하고 저장을 재시도할 여지를 남긴다.
     */
    private Incident safeAttachDraft(Incident committed, IncidentReportDrafter.Draft draft) {
        try {
            return self.getObject().attachDraft(committed.getId(), draft);
        } catch (Exception e) {
            log.warn("[UC2] 조사표 초안 저장 실패 — 등록 자체는 이미 커밋됐으므로 그대로 응답한다: {}",
                    e.getMessage());
            return committed;
        }
    }

    /**
     * 유사 사례 검색어 — <b>설비명 + 사고 경위</b>만 쓴다(R51). 발생형태 축 라벨은
     * {@link IncidentEvidenceCollector}가 앞에 붙인다. <b>장소 태그는 절대 섞지 않는다</b> —
     * 국내재해사례 원문에는 사업장 위치 개념이 없어 위치로 검색하면 관련 없는 사례가 걸린다.
     */
    private String caseQueryText(EquipmentHistoryRecaller.Recall recall, Incident incident) {
        String equipmentName = recall.equipmentName();
        boolean hasEquipment = equipmentName != null && !equipmentName.isBlank()
                && !"(미등록 설비)".equals(equipmentName);
        StringBuilder sb = new StringBuilder();
        if (hasEquipment) {
            sb.append(equipmentName);
        }
        if (incident.getDescription() != null && !incident.getDescription().isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(incident.getDescription());
        }
        return sb.toString();
    }

    /**
     * 근거 수집기가 이미 각 검색을 개별 try/catch로 흡수하지만, 여기서 한 번 더 감싼다 —
     * 근거 때문에 등록 자체가 실패하는 경로를 원천적으로 남기지 않기 위해서다.
     */
    private IncidentEvidenceCollector.Collected safeCollectEvidence(String queryText, IncidentType type) {
        try {
            return evidenceCollector.collect(queryText, type);
        } catch (Exception e) {
            log.warn("[UC2] 근거 수집 실패 — 등록은 그대로 진행한다: {}", e.getMessage());
            return new IncidentEvidenceCollector.Collected(List.of(), List.of());
        }
    }

    /**
     * 입력 검증.
     *
     * <p>휴업일수는 <b>법정 기한 계산에 그대로 쓰인다.</b> 음수가 통과하면
     * "휴업 -5일(3일 미만)이라 제출 의무 없음"이라는 문장이 조사표에 찍힌다.
     * 있을 수 없는 값은 기록 단계에서 막는다 (2026-09-21 QA 실측).
     */
    private void validate(IncidentDtos.RegisterRequest request) {
        if (request.incidentType() == null) {
            throw new InvalidRequestException("발생형태를 선택하십시오.");
        }
        if (request.severity() == null) {
            throw new InvalidRequestException("재해 정도를 선택하십시오.");
        }
        if (request.severity() == IncidentSeverity.LOST_TIME
                && (request.leaveDays() == null || request.leaveDays() < 1)) {
            throw new InvalidRequestException("휴업이면 휴업예상일수를 입력하십시오.");
        }
        if (request.occurredAt() != null
                && request.occurredAt().isAfter(OffsetDateTime.now().plusMinutes(FUTURE_TOLERANCE_MINUTES))) {
            throw new InvalidRequestException("발생 일시는 지금보다 늦을 수 없습니다.");
        }
        if (tooLong(request.injuryType()) || tooLong(request.injuryPart())) {
            throw new InvalidRequestException("상해 종류와 부위는 100자 이하로 입력하십시오.");
        }
        Integer days = request.leaveDays();
        if (days != null && days < 0) {
            throw new InvalidRequestException("휴업예상일수는 0 이상이어야 합니다.");
        }
        if (days != null && days > 3650) {
            throw new InvalidRequestException("휴업예상일수를 다시 확인하십시오.");
        }
    }

    private static boolean tooLong(String v) {
        return v != null && v.strip().length() > 100;
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    /**
     * 산업재해조사표 제출 완료 처리. 제출 의무가 있는 건(제출 필요, 기한 경과)만 바꾼다.
     * 제출일은 오늘이다. 이미 제출 완료면 그대로 돌려준다(멱등).
     */
    @Transactional
    public IncidentDtos.IncidentListItem markSubmitted(Long incidentId) {
        Incident incident = incidentRepository.findById(incidentId).orElseThrow(
                () -> new NotFoundException("사고를 찾을 수 없습니다."));
        if (incident.getReportStatus() != ReportStatus.SUBMITTED) {
            if (incident.getReportStatus() != ReportStatus.REQUIRED
                    && incident.getReportStatus() != ReportStatus.OVERDUE) {
                throw new ConflictException("산업재해조사표 제출 대상이 아닙니다.");
            }
            incident.markSubmitted(LocalDate.now(KST));
            incident = incidentRepository.save(incident);
        }
        return listItem(incident, LocalDate.now(KST));
    }

    /**
     * 이미 등록된 사고를 다시 펼친다 (UC4 타임라인에서 진입).
     *
     * <p>{@code similarCases}·{@code evidence}는 <b>저장하지 않고 조회 시점에 다시 계산</b>한다 —
     * {@code recall}과 같은 방식이다(둘 다 결정론적이거나, 실패해도 조회 자체를 막지 않는 값).
     * 검색 실패는 빈 리스트로 흡수한다({@link #safeCollectEvidence}).
     */
    @Transactional(readOnly = true)
    public IncidentDtos.RegisterResponse detail(Long incidentId) {
        Incident incident = incidentRepository.findById(incidentId).orElseThrow(
                () -> new NotFoundException("사고를 찾을 수 없습니다."));

        EquipmentHistoryRecaller.Recall recall = recaller.recall(
                incident.getEquipmentId(), incident.getAccidentType(),
                incident.getOccurredAt(), incident.getCreatedAt(),
                EquipmentHistoryRecaller.Purpose.POST_INCIDENT);

        // 재구성 경로라 새로 경고를 붙이지 않는다 — register()가 이미 저장해 둔
        // warning_note를 그대로 읽는다. 다시 붙이면 같은 문구가 매 조회마다 누적된다.
        List<IncidentDtos.AffectedWorkPlan> affectedWorkPlans = readAffectedWorkPlans(incident);

        String caseQuery = caseQueryText(recall, incident);
        IncidentEvidenceCollector.Collected collected =
                safeCollectEvidence(caseQuery, incident.getIncidentType());

        TimelineDtos.RecallView recallView = TimelineDtos.RecallView.from(recall, processNameOf(incident.getEquipmentId()));
        IncidentDtos.ReportDuty reportDuty = reportDuty(incident, LocalDate.now(KST));
        // 최종 리뷰 F9: 재조회에서도 2단계(수시평가)가 "재평가 대상 위험요인이 없습니다"로 틀리게 나오지 않도록
        // 저장된 assessment_hazard에서 등급 변화를 다시 읽는다(새로 채점하지 않는다)
        FollowUpAssessmentService.Result followUp =
                followUpAssessmentService.reconstruct(incident.getFollowUpAssessmentId());
        IncidentDtos.FollowUpView followUpView = followUpView(incident.getFollowUpAssessmentId(), followUp);
        List<IncidentDtos.CascadeStep> cascade =
                buildCascade(incident, recallView, followUpView, reportDuty, affectedWorkPlans, collected.similarCases());

        return new IncidentDtos.RegisterResponse(
                summary(incident, recall.equipmentName()),
                reportDuty,
                recallView,
                followUpView,
                // 예전 기록(시드 포함)의 "기한 작업 재개 전", "MM-DD" 같은 기한도 화면에서는 날짜로 맞춰 보인다
                new IncidentDtos.DraftView(incident.getCause(),
                        PreventionFormat.normalize(incident.getPrevention(),
                                incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate(),
                                IncidentReportDrafter.SAFETY_MANAGER),
                        false, DISCLAIMER),
                cascade,
                affectedWorkPlans,
                collected.similarCases(),
                collected.all());
    }

    /**
     * 목록. 조회할 때마다 기한 경과를 반영한다.
     *
     * <p>스케줄러를 두지 않았다. 대회 기간에 돌아가는 배치 프로세스는 무대에서
     * 관찰할 수 없는 상태를 만들고, 기한 판정은 조회 시점에 계산해도 결과가 같다.
     */
    @Transactional
    public Page<IncidentDtos.IncidentListItem> list(Long siteId, Pageable pageable) {
        LocalDate today = LocalDate.now(KST);
        return incidentRepository.findBySiteIdOrderByOccurredAtDesc(siteId, pageable)
                .map(incident -> {
                    ReportStatus before = incident.getReportStatus();
                    incident.refreshOverdue(today);
                    if (before != incident.getReportStatus()) {
                        incidentRepository.save(incident);
                    }
                    return listItem(incident, today);
                });
    }

    /**
     * 설비 매칭. UC3과 같은 매처를 쓴다.
     *
     * <p><b>후보가 애매하면 만들지 않는다.</b> 잘못 붙은 설비 ID는 조용히 틀리고,
     * 그 순간 "하나의 설비 ID" 위에 쌓은 이력이 둘로 갈라진다. 확신이 없으면
     * {@code equipmentId} 없이 기록하고 사람이 나중에 붙이게 둔다.
     */
    private Long resolveEquipmentId(Long siteId, IncidentDtos.RegisterRequest request) {
        if (request.equipmentId() != null) {
            return request.equipmentId();
        }
        if (request.equipmentQuery() == null || request.equipmentQuery().isBlank()) {
            return null;
        }
        EquipmentMatcher.MatchResult match =
                equipmentMatcher.match(siteId, request.equipmentQuery(), null);

        if (match.isConfirmed()) {
            return match.equipment().getId();
        }
        log.warn("[UC2] 설비 매칭 실패 '{}' — 후보 {}건. 설비 없이 기록한다",
                request.equipmentQuery(), match.candidates().size());
        return null;
    }

    private IncidentDtos.IncidentSummary summary(Incident incident, String equipmentName) {
        return new IncidentDtos.IncidentSummary(
                incident.getId(), incident.getSiteId(), incident.getEquipmentId(), equipmentName,
                incident.getWorkPlanId(), incident.getOccurredAt(), incident.getVictimName(),
                incident.getSeverity(), incident.getLeaveDays(), incident.getIncidentType(),
                incident.getIncidentType() == null ? null : incident.getIncidentType().getLabel(),
                incident.getAccidentType(), incident.getDescription(),
                incident.getInjuryType(), incident.getInjuryPart(), incident.getFollowUpAssessmentId());
    }

    /**
     * 법정 기한 카드.
     *
     * <p>{@code basis}는 왜 이 판정이 나왔는지를 그대로 문자열로 남긴 것이다.
     * 등급과 마찬가지로, 근거를 화면에 띄울 수 없는 자동 판정은 만들지 않는다.
     */
    private IncidentDtos.ReportDuty reportDuty(Incident incident, LocalDate today) {
        return new IncidentDtos.ReportDuty(
                incident.getReportStatus(), incident.getReportStatus().getLabel(),
                incident.getReportDueDate(), incident.daysUntilDue(today), reportBasis(incident),
                incident.isSeriousAccidentPossible(), incident.getReportSubmittedOn());
    }

    /** 재조회 경로의 수시평가 뷰: 저장된 등급 변화 + 지금 상태(작성 중, 확정일) */
    private IncidentDtos.FollowUpView followUpView(Long assessmentId, FollowUpAssessmentService.Result followUp) {
        String status = "DRAFT";
        LocalDate confirmedOn = null;
        if (assessmentId != null) {
            io.saife.core.domain.Assessment a = assessmentRepository.findById(assessmentId).orElse(null);
            if (a != null && "CONFIRMED".equals(a.getStatus())) {
                status = "CONFIRMED";
                confirmedOn = followUpRecords.confirmedAt(assessmentId)
                        .map(t -> t.atZoneSameInstant(KST).toLocalDate())
                        .orElse(a.getAssessedOn());
            }
        }
        return new IncidentDtos.FollowUpView(assessmentId, "수시", FOLLOW_UP_LEGAL_BASIS,
                followUp.regraded(), followUp.newHazardId(), status, confirmedOn);
    }

    /**
     * 제출 대상 판단 근거 한 줄. 서식(IncidentFormService)도 같은 문구를 쓴다.
     * 시행규칙 제73조: 사망 또는 3일 이상 휴업이 필요한 재해, 발생일부터 1개월 이내.
     */
    public static String reportBasis(Incident incident) {
        Integer leaveDays = incident.getLeaveDays();
        if (incident.getSeverity() == IncidentSeverity.FATALITY) {
            return "사망 재해, 발생일부터 1개월 이내 제출 (시행규칙 제73조)";
        }
        if (incident.getSeverity() == IncidentSeverity.NEAR_MISS) {
            return "아차사고, 조사표 제출 대상 아님";
        }
        if (leaveDays == null) {
            return "휴업예상일수 미입력, 제출 대상 판단 보류";
        }
        if (leaveDays >= Incident.REPORTABLE_LEAVE_DAYS) {
            return "휴업예상일수 %d일, 3일 이상 휴업이 필요한 재해로 발생일부터 1개월 이내 제출 (시행규칙 제73조)"
                    .formatted(leaveDays);
        }
        return "휴업예상일수 %d일, 제출 대상 아님 (시행규칙 제73조, 3일 이상 휴업)".formatted(leaveDays);
    }

    private IncidentDtos.IncidentListItem listItem(Incident incident, LocalDate today) {
        String equipmentName = incident.getEquipmentId() == null ? null
                : equipmentRepository.findById(incident.getEquipmentId())
                        .map(Equipment::getName).orElse(null);

        return new IncidentDtos.IncidentListItem(
                incident.getId(), incident.getEquipmentId(), equipmentName,
                incident.getOccurredAt(), incident.getIncidentType(),
                incident.getIncidentType() == null ? null : incident.getIncidentType().getLabel(),
                incident.getSeverity(),
                incident.getLeaveDays(), incident.getReportStatus(),
                incident.getReportStatus().getLabel(), incident.getReportDueDate(),
                incident.daysUntilDue(today), incident.getReportSubmittedOn(), incident.getFollowUpAssessmentId());
    }

    // ────────────────────────── 사고 연쇄 (2-2) ──────────────────────────

    /**
     * 같은 설비의 진행 중 작업 전 점검을 작업 보류(HOLD)로 바꾸고 보류 문구를 남긴다.
     *
     * <p><b>대상: 같은 설비, 상태 SUBMITTED/APPROVED/CONDITIONAL, 작업일이 사고일 이후.</b>
     * 사고일 이전에 이미 끝났거나 예정됐던 작업까지 보류하면 의미가 없다(IncidentServiceTest가 이 경계를 검증한다).
     * 재개는 수시평가가 끝난 뒤 사람이 한다(시행규칙 제37조제2항제3호).
     *
     * @return 보류한 계획서. {@code warning}은 이번에 붙인 문구만 담는다
     */
    private List<IncidentDtos.AffectedWorkPlan> holdWorkPlans(Incident incident) {
        if (incident.getEquipmentId() == null) {
            return List.of();
        }
        LocalDate incidentDate = incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate();
        List<WorkPlan> plans = workPlanRepository
                .findBySiteIdAndEquipmentIdAndStatusInAndWorkDateGreaterThanEqualOrderByWorkDateAsc(
                        incident.getSiteId(), incident.getEquipmentId(),
                        AFFECTED_WORK_PLAN_STATUSES, incidentDate);

        List<IncidentDtos.AffectedWorkPlan> out = new ArrayList<>();
        for (WorkPlan plan : plans) {
            plan.hold();
            plan.appendWarning(HOLD_WARNING);
            workPlanRepository.save(plan);
            out.add(new IncidentDtos.AffectedWorkPlan(plan.getId(), plan.getWorkName(),
                    plan.getWorkDate(), plan.getStatus().name(), HOLD_WARNING));
        }
        return out;
    }

    /**
     * 설비가 속한 공정명. 설비 홈·작업 신고 진입({@code EquipmentTimelineService.recall})과 같은 규칙으로
     * 구해 {@code RecallView.knownSlots}에 "공정/작업유형"이 빠지지 않게 한다(수정 목록 C 1a).
     */
    private String processNameOf(Long equipmentId) {
        if (equipmentId == null) {
            return null;
        }
        return equipmentRepository.findById(equipmentId)
                .map(Equipment::getProcessId)
                .flatMap(processRepository::findById)
                .map(WorkProcess::getName)
                .orElse(null);
    }

    /**
     * {@code detail()} 재구성 경로: 새로 보류하지 않고, 이 사고가 보류 문구를 붙인 계획서를 읽는다.
     * 수시평가 확정으로 보류가 풀린 것(재승인 대기 등)도 담는다. {@code status}가 지금 상태다.
     */
    private List<IncidentDtos.AffectedWorkPlan> readAffectedWorkPlans(Incident incident) {
        if (incident.getEquipmentId() == null) {
            return List.of();
        }
        return workPlanRepository
                .findBySiteIdAndEquipmentIdAndStatusInAndWorkDateGreaterThanEqualOrderByWorkDateAsc(
                        incident.getSiteId(), incident.getEquipmentId(),
                        Arrays.asList(WorkPlanStatus.values()),
                        incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate())
                .stream()
                // 최종 리뷰 F9: 사고 뒤에 새로 만든(보류 문구가 없는) 계획서는 대상이 아니다
                .filter(FollowUpConfirmService::heldByIncident)
                .map(plan -> new IncidentDtos.AffectedWorkPlan(plan.getId(), plan.getWorkName(),
                        plan.getWorkDate(), plan.getStatus().name(), HOLD_WARNING))
                .toList();
    }

    /**
     * 사고 연쇄 4단계를 백엔드가 순서대로 만든다(order 1~4). 화면마다 다르게 판단하면
     * 강조 색이 흔들린다. 순서와 강조 규칙을 전부 여기서 고정한다. 문구는 사실형 명사구다.
     */
    private List<IncidentDtos.CascadeStep> buildCascade(Incident incident,
            TimelineDtos.RecallView recall, IncidentDtos.FollowUpView followUp,
            IncidentDtos.ReportDuty reportDuty, List<IncidentDtos.AffectedWorkPlan> affectedWorkPlans,
            List<Evidence> similarCases) {
        return List.of(
                recallStep(recall, similarCases),
                followUpStep(followUp),
                reportStep(reportDuty, incident.getId()),
                workPlanStep(affectedWorkPlans));
    }

    /**
     * 1단계: 사고 전 기록. 같은 발생형태의 위험요인이 사고 전에 있었으면 CRITICAL,
     * 미이행 조치만 있으면 WARNING. 유사 사례가 있으면 " 유사 사례 N건."을 덧붙인다.
     */
    private IncidentDtos.CascadeStep recallStep(TimelineDtos.RecallView recall, List<Evidence> similarCases) {
        TimelineDtos.Emphasis emphasis = recall.predicted()
                ? TimelineDtos.Emphasis.CRITICAL
                : recall.unfinishedActions().isEmpty()
                        ? TimelineDtos.Emphasis.NORMAL
                        : TimelineDtos.Emphasis.WARNING;
        String detail = recall.headline();
        if (similarCases != null && !similarCases.isEmpty()) {
            detail += " 유사 사례 %d건.".formatted(similarCases.size());
        }
        return new IncidentDtos.CascadeStep(1, "RECALL", "사고 전 기록",
                detail, emphasis, recall.equipmentId(), "EQUIPMENT");
    }

    /** 2단계: 수시평가(작업 재개 전). 등급 변화 요약을 붙인다. 확정했으면 NORMAL */
    private IncidentDtos.CascadeStep followUpStep(IncidentDtos.FollowUpView followUp) {
        boolean confirmed = "CONFIRMED".equals(followUp.status());
        return new IncidentDtos.CascadeStep(2, "FOLLOW_UP",
                confirmed ? "수시평가 확정 %s".formatted(followUp.confirmedOn()) : "수시평가 작성 중",
                regradeSummary(followUp.regraded()),
                confirmed ? TimelineDtos.Emphasis.NORMAL : TimelineDtos.Emphasis.WARNING,
                followUp.assessmentId(), "ASSESSMENT");
    }

    /** 3단계: 산업재해조사표 기한. 3일 이내면 CRITICAL, 의무가 있으면 WARNING, 없으면 NORMAL */
    private IncidentDtos.CascadeStep reportStep(IncidentDtos.ReportDuty reportDuty, Long incidentId) {
        String detail;
        TimelineDtos.Emphasis emphasis;
        if (reportDuty.status() == ReportStatus.SUBMITTED) {
            detail = reportDuty.submittedOn() == null ? "제출 완료" : "제출 완료 %s".formatted(reportDuty.submittedOn());
            emphasis = TimelineDtos.Emphasis.NORMAL;
        } else if (reportDuty.dueDate() != null) {
            detail = "기한 %s (발생일부터 1개월)".formatted(reportDuty.dueDate());
            emphasis = reportDuty.daysRemaining() != null && reportDuty.daysRemaining() <= 3
                    ? TimelineDtos.Emphasis.CRITICAL : TimelineDtos.Emphasis.WARNING;
        } else {
            detail = "제출 의무 없음";
            emphasis = TimelineDtos.Emphasis.NORMAL;
        }
        return new IncidentDtos.CascadeStep(3, "REPORT", "산업재해조사표",
                detail, emphasis, incidentId, "INCIDENT");
    }

    /** 4단계: 작업 보류. 0건이면 "해당 없음", NORMAL. 수시평가 확정으로 모두 풀렸으면 "보류 해제 n건" */
    private IncidentDtos.CascadeStep workPlanStep(List<IncidentDtos.AffectedWorkPlan> affectedWorkPlans) {
        long held = affectedWorkPlans.stream().filter(p -> WorkPlanStatus.HOLD.name().equals(p.status())).count();
        String title = held == 0 && !affectedWorkPlans.isEmpty()
                ? "보류 해제 %d건".formatted(affectedWorkPlans.size())
                : "작업 보류 %d건".formatted(held);
        String detail = affectedWorkPlans.isEmpty()
                ? "해당 없음"
                : affectedWorkPlans.stream()
                        .map(p -> "%s(%s)".formatted(p.workName(), p.workDate()))
                        .collect(Collectors.joining(", "));
        TimelineDtos.Emphasis emphasis = held == 0
                ? TimelineDtos.Emphasis.NORMAL : TimelineDtos.Emphasis.WARNING;
        Long refId = affectedWorkPlans.isEmpty() ? null : affectedWorkPlans.get(0).workPlanId();
        return new IncidentDtos.CascadeStep(4, "WORK_PLAN", title, detail, emphasis, refId, "WORK_PLAN");
    }

    /**
     * 재평가 등급 변화를 "중에서 상 2건, 유지 1건" 형태로 요약한다.
     * 신규 위험요인은 이전 등급이 없으므로 "신규 상 1건"이다.
     */
    private String regradeSummary(List<FollowUpAssessmentService.Regrade> regraded) {
        if (regraded.isEmpty()) {
            return "재평가 대상 위험요인 없음";
        }
        Map<String, Long> transitions = regraded.stream()
                .filter(r -> r.changed() || r.before() == null)
                .collect(Collectors.groupingBy(
                        r -> r.before() == null
                                ? "신규 %s".formatted(riskLabel(r.after()))
                                : "%s에서 %s".formatted(riskLabel(r.before()), riskLabel(r.after())),
                        LinkedHashMap::new, Collectors.counting()));
        long unchanged = regraded.stream().filter(r -> !r.changed() && r.before() != null).count();

        List<String> parts = new ArrayList<>();
        transitions.forEach((transition, count) -> parts.add("%s %d건".formatted(transition, count)));
        if (unchanged > 0) {
            parts.add("유지 %d건".formatted(unchanged));
        }
        return parts.isEmpty() ? "등급 변화 없음" : String.join(", ", parts);
    }

    private String riskLabel(RiskLevel level) {
        return level == null ? "신규" : level.getLabel();
    }
}
