package io.saife.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.core.domain.*;
import io.saife.core.repository.*;
import io.saife.evidence.Evidence;
import io.saife.form.dto.FormViews;
import io.saife.form.dto.WorkPlanFormView;
import io.saife.common.error.ApiExceptions.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 법정 서식에 들어갈 값을 모은다.
 *
 * <p>템플릿에서 계산하지 않는다. 서식은 법정 요건이라 표시 로직이 값을 바꾸면 안 되고,
 * 무엇보다 <b>화면과 출력물이 다른 값을 말하면 그 순간 신뢰를 잃는다.</b>
 *
 * <p>모든 서식에 "작성 보조" 고지를 넣는다. 법률 자문이 아니고 최종 확정·제출은 사람이 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormDataService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 서식의 모든 시각은 한국 시간으로 찍는다.
     *
     * <p>DB의 {@code timestamptz}를 Hibernate가 UTC 오프셋으로 돌려주므로
     * {@code OffsetDateTime.format()}을 그냥 쓰면 <b>9시간 틀린 시각이 법정 문서에 박힌다.</b>
     * 실제로 14:20 사고가 조사표에 05:20으로 찍혔다 (2026-09-20 실측).
     * API 응답은 Jackson이 변환해 주지만 서식은 직접 변환해야 한다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String AI_NOTICE =
            "이 문서는 SAIFE의 작성 보조 결과를 포함합니다. 법률 자문이 아니며, "
                    + "최종 확정과 제출은 사업장 담당자가 합니다. "
                    + "위험성 등급은 생성형 모델이 아니라 사전 정의된 규칙 엔진이 판정했습니다.";



    private final SiteRepository siteRepository;
    private final EquipmentRepository equipmentRepository;
    private final ProcessRepository processRepository;
    private final HazardRepository hazardRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentHazardRepository assessmentHazardRepository;
    private final ActionRepository actionRepository;
    private final ObjectMapper objectMapper;
    private final WorkPlanFormService workPlanFormService;

    // 위험성평가표는 AssessmentFormService가 맡는다

    // 산업재해조사표와 재발방지 검토서는 IncidentFormService가 맡는다

    // ---------- 작업 전 안전점검표 ----------

    /** 서식 값은 WorkPlanFormService가 만든다(화면 결과 카드와 같은 판정을 쓴다) */
    @Transactional(readOnly = true)
    public WorkPlanFormView workPlanForm(Long workPlanId) {
        return workPlanFormService.build(workPlanId);
    }

    // ---------- 라벨 ----------

    private String equipmentName(Long equipmentId) {
        if (equipmentId == null) {
            return "-";
        }
        return equipmentRepository.findById(equipmentId)
                .map(e -> {
                    String loc = e.getProcessId() == null ? e.getLocationTag()
                            : processRepository.findById(e.getProcessId())
                                    .map(WorkProcess::getLocationTag).orElse(e.getLocationTag());
                    return loc == null ? e.getName() : "%s (%s)".formatted(e.getName(), loc);
                })
                .orElse("-");
    }

    private String format(OffsetDateTime ts) {
        return ts == null ? "-" : ts.atZoneSameInstant(KST).format(TS);
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
