package io.saife.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;

import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Assessment;
import io.saife.core.domain.AssessmentHazard;
import io.saife.core.domain.AssessmentKind;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.Hazard;
import io.saife.core.domain.HazardSource;
import io.saife.core.domain.RiskLevel;
import io.saife.core.repository.AssessmentHazardRepository;
import io.saife.core.repository.AssessmentRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 사진만 올린 순회점검: 판독(상황, 예방 방법)과 설비 기록 연결 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PhotoSceneTest {

    @Autowired VisionAssessmentService service;
    @Autowired AssessmentRepository assessments;
    @Autowired AssessmentHazardRepository links;
    @Autowired HazardRepository hazards;
    @Autowired EquipmentRepository equipments;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 사진_상황과_주요_설비_문제별_예방_방법을_읽는다() {
        VisionAnalyzer.Analysis a = VisionAnalyzer.parseScene("""
                {"scene":"작업자가 사다리에 올라 작업 중임","equipment":"A형 이동식 사다리",
                 "findings":[{"accidentType":"FALL","missingControl":"최상부 디딤대 사용","evidence":"맨 위 발판에 서 있음",
                   "confidence":0.9,"box":[100,400,600,560],"prevention":["이동식 비계 사용","최상부 두 칸 사용 금지","x","y"]}]}""");
        assertThat(a.scene()).isEqualTo("작업자가 사다리에 올라 작업 중임");
        assertThat(a.equipment()).isEqualTo("A형 이동식 사다리");
        assertThat(a.findings()).hasSize(1);
        assertThat(a.findings().get(0).prevention()).containsExactly("이동식 비계 사용", "최상부 두 칸 사용 금지", "x");
        assertThat(VisionAnalyzer.parseScene("not json").findings()).isEmpty();
    }

    @Test
    void 설비에_기록하면_설비_대장의_같은_위험요인으로_합치고_새_위험요인은_설비를_지정한다() {
        Equipment eq = equipments.findAll().get(0);
        Hazard known = hazards.saveAndFlush(Hazard.builder().siteId(eq.getSiteId()).equipmentId(eq.getId())
                .accidentType(AccidentType.FALL).missingControl("ZQX 최상부 디딤대 사용").description("대장")
                .source(HazardSource.MANUAL).aiSuggested(false).build());
        Assessment a = assessments.saveAndFlush(Assessment.builder().siteId(eq.getSiteId()).kind(AssessmentKind.ROUTINE)
                .triggerType("PATROL").assessedOn(LocalDate.now()).status("ANALYZED").build());
        Hazard same = hazards.saveAndFlush(Hazard.builder().siteId(eq.getSiteId())
                .accidentType(AccidentType.FALL).missingControl("ZQX 최상부 디딤대사용").description("오늘 사진")
                .source(HazardSource.PHOTO).aiSuggested(true).build());
        Hazard fresh = hazards.saveAndFlush(Hazard.builder().siteId(eq.getSiteId())
                .accidentType(AccidentType.PPE).missingControl("ZQX 안전모 미착용").description("오늘 사진")
                .source(HazardSource.PHOTO).aiSuggested(true).build());
        links.saveAndFlush(AssessmentHazard.builder().assessmentId(a.getId()).hazardId(same.getId()).riskLevel(RiskLevel.HIGH).ruleTrace("r").build());
        links.saveAndFlush(AssessmentHazard.builder().assessmentId(a.getId()).hazardId(fresh.getId()).riskLevel(RiskLevel.MEDIUM).ruleTrace("r").build());

        service.assignEquipment(a.getId(), eq.getId());

        assertThat(jdbc.queryForList("SELECT hazard_id FROM assessment_hazard WHERE assessment_id = ?", Long.class, a.getId()))
                .containsExactlyInAnyOrder(known.getId(), fresh.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hazard WHERE id = ?", Integer.class, same.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT equipment_id FROM hazard WHERE id = ?", Long.class, fresh.getId())).isEqualTo(eq.getId());
    }
}
