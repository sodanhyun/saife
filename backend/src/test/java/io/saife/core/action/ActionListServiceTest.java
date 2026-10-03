package io.saife.core.action;

import io.saife.common.dto.PageResponse;
import io.saife.common.error.ApiExceptions.InvalidRequestException;
import io.saife.core.domain.AccidentType;
import io.saife.core.domain.Action;
import io.saife.core.domain.ActionStatus;
import io.saife.core.domain.Equipment;
import io.saife.core.domain.Hazard;
import io.saife.core.domain.HazardSource;
import io.saife.core.repository.ActionRepository;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.repository.HazardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 개선대책 목록: 필터(미이행/기한 경과/완료/전체), 키워드, 정렬, 경과 일수, 탭 건수.
 * 자체 행을 넣고 고유 키워드로 걸러 본다(시드 날짜가 상대값이라 시드 개수에 기대지 않는다). 트랜잭션 롤백.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ActionListServiceTest {

    private static final String MARK = "ZQX목록검증";

    @Autowired ActionListService service;
    @Autowired ActionController controller;
    @Autowired ActionRepository actions;
    @Autowired HazardRepository hazards;
    @Autowired EquipmentRepository equipments;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private Equipment equipment;
    private Hazard hazard;
    private ActionDtos.ActionCounts before;

    private Action late, soon, noDue, staleOverdueFlag, done1, done2;

    @BeforeEach
    void setUp() {
        before = service.counts();
        equipment = equipments.findAll().get(0);
        hazard = hazards.saveAndFlush(Hazard.builder().siteId(equipment.getSiteId()).equipmentId(equipment.getId())
                .accidentType(AccidentType.FALL).missingControl("작업발판 미확보").description("d")
                .source(HazardSource.MANUAL).aiSuggested(false).build());

        late = save(MARK + " 기한 지남", "생산반장", today.minusDays(3), ActionStatus.PENDING, null);
        soon = save(MARK + " 곧 마감", "설비팀", today.plusDays(5), ActionStatus.PENDING, null);
        noDue = save(MARK + " 기한 없음", null, null, ActionStatus.PENDING, null);
        // 저장값이 OVERDUE면 기한이 남아 있어도 기한 경과로 본다(TodayService와 같다)
        staleOverdueFlag = save(MARK + " 표시만 경과", null, today.plusDays(10), ActionStatus.OVERDUE, null);
        done1 = save(MARK + " 완료 이전", null, today.minusDays(20), ActionStatus.DONE, OffsetDateTime.now().minusDays(9));
        done2 = save(MARK + " 완료 최근", null, today.minusDays(1), ActionStatus.DONE, OffsetDateTime.now().minusDays(1));
    }

    private Action save(String content, String owner, LocalDate due, ActionStatus status, OffsetDateTime completedAt) {
        return actions.saveAndFlush(Action.builder().hazardId(hazard.getId()).content(content).owner(owner)
                .dueDate(due).status(status).completedAt(completedAt).build());
    }

    private List<Long> ids(PageResponse<ActionDtos.ActionListItem> page) {
        return page.content().stream().map(ActionDtos.ActionListItem::id).toList();
    }

    @Test
    void 기본은_미이행이고_기한_오름차순_기한_없음은_끝() {
        PageResponse<ActionDtos.ActionListItem> page = service.list(null, MARK, null, null);
        assertThat(ids(page)).containsExactly(late.getId(), soon.getId(), staleOverdueFlag.getId(), noDue.getId());
        assertThat(page.totalElements()).isEqualTo(4);
        assertThat(page.size()).isEqualTo(ActionListService.DEFAULT_SIZE);
    }

    @Test
    void 기한_경과는_판정_상태와_경과_일수를_싣는다() {
        PageResponse<ActionDtos.ActionListItem> page = service.list("OVERDUE", MARK, 0, 20);
        assertThat(ids(page)).containsExactly(late.getId(), staleOverdueFlag.getId());

        ActionDtos.ActionListItem first = page.content().get(0);
        assertThat(first.status()).isEqualTo(ActionStatus.OVERDUE);
        assertThat(first.overdueDays()).isEqualTo(3L);
        assertThat(first.equipmentId()).isEqualTo(equipment.getId());
        assertThat(first.equipmentName()).isEqualTo(equipment.getName());
        assertThat(first.accidentType()).isEqualTo(AccidentType.FALL);
        assertThat(first.missingControl()).isEqualTo("작업발판 미확보");
        assertThat(first.hazardId()).isEqualTo(hazard.getId());
        assertThat(page.content().get(1).overdueDays()).isZero();
    }

    @Test
    void 기한이_남은_미이행은_PENDING이고_경과_일수가_없다() {
        ActionDtos.ActionListItem row = service.list("OPEN", MARK + " 곧", 0, 20).content().get(0);
        assertThat(row.id()).isEqualTo(soon.getId());
        assertThat(row.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(row.overdueDays()).isNull();
    }

    @Test
    void 완료는_완료_시각_내림차순() {
        PageResponse<ActionDtos.ActionListItem> page = service.list("done", MARK, 0, 20);
        assertThat(ids(page)).containsExactly(done2.getId(), done1.getId());
        assertThat(page.content().get(0).status()).isEqualTo(ActionStatus.DONE);
        assertThat(page.content().get(0).overdueDays()).isNull();
        assertThat(page.content().get(0).completedAt()).isNotNull();
    }

    @Test
    void 전체는_미완료가_먼저_그다음_기한순() {
        PageResponse<ActionDtos.ActionListItem> page = service.list("ALL", MARK, 0, 20);
        assertThat(ids(page)).containsExactly(late.getId(), soon.getId(), staleOverdueFlag.getId(), noDue.getId(),
                done1.getId(), done2.getId());
    }

    @Test
    void 키워드는_담당과_설비명에도_걸린다() {
        assertThat(ids(service.list("ALL", "생산반장", 0, 100))).contains(late.getId()).doesNotContain(soon.getId());
        assertThat(ids(service.list("ALL", equipment.getName(), 0, 100))).contains(late.getId(), done1.getId());
        // LIKE 특수문자는 글자 그대로 찾는다
        assertThat(service.list("ALL", MARK + "%", 0, 20).totalElements()).isZero();
    }

    @Test
    void 페이징은_DB에서_자른다() {
        PageResponse<ActionDtos.ActionListItem> p0 = service.list("ALL", MARK, 0, 4);
        PageResponse<ActionDtos.ActionListItem> p1 = service.list("ALL", MARK, 1, 4);
        assertThat(p0.content()).hasSize(4);
        assertThat(p1.content()).hasSize(2);
        assertThat(p0.totalPages()).isEqualTo(2);
        assertThat(p0.totalElements()).isEqualTo(6);
    }

    @Test
    void 탭_건수는_미이행에_기한_경과를_포함한다() {
        ActionDtos.ActionCounts after = service.counts();
        assertThat(after.open() - before.open()).isEqualTo(4);
        assertThat(after.overdue() - before.overdue()).isEqualTo(2);
        assertThat(after.done() - before.done()).isEqualTo(2);
        assertThat(controller.counts().getBody()).isEqualTo(after);
    }

    @Test
    void 이행_완료하면_미이행에서_빠지고_완료로_간다() {
        controller.complete(late.getId());
        actions.flush();
        assertThat(ids(service.list("OPEN", MARK, 0, 20))).doesNotContain(late.getId());
        assertThat(ids(service.list("DONE", MARK, 0, 20))).first().isEqualTo(late.getId());
    }

    @Test
    void 잘못된_필터와_페이지는_400() {
        assertThatThrownBy(() -> service.list("LATE", null, 0, 20)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list("OPEN", null, -1, 20)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list("OPEN", null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }
}
