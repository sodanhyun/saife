package io.saife.evidence.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.ConversationEvidence;
import io.saife.evidence.live.Origin;
import io.saife.evidence.repository.ConversationEvidenceRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvidenceLedgerTest {
    private final ConversationEvidenceRepository repo = mock(ConversationEvidenceRepository.class);
    private final EvidenceLedger ledger = new EvidenceLedger(repo, new ObjectMapper().findAndRegisterModules());

    private Evidence ev(String key) {
        return new Evidence(0, EvidenceKind.CASE_FATALITY, 1L, key, "제목 " + key, "s", null, null, null, Origin.CACHE, 0.5, OffsetDateTime.now(), Map.of());
    }

    @Test
    void 번호는_1부터_대화별로() {
        when(repo.maxEvidenceNo("c1")).thenReturn(0);
        when(repo.findByConversationIdOrderByEvidenceNo("c1")).thenReturn(List.of());
        assertThat(ledger.register("c1", ev("A")).no()).isEqualTo(1);
        assertThat(ledger.register("c1", ev("B")).no()).isEqualTo(2);
        assertThat(ledger.newInTurn("c1")).extracting(Evidence::no).containsExactly(1, 2);
    }

    @Test
    void 재등장은_같은_번호_새_턴에는_안_실림() {
        when(repo.maxEvidenceNo("c2")).thenReturn(0);
        when(repo.findByConversationIdOrderByEvidenceNo("c2")).thenReturn(List.of());
        when(repo.maxTurnNo("c2")).thenReturn(0);
        ledger.register("c2", ev("A"));
        // fix round 2 (H1): turnNo는 더 이상 이 메서드가 계산하지 않는다 — 호출자가 넘긴다
        int turn = ledger.endTurn("c2", 1);
        assertThat(turn).isEqualTo(1);
        verify(repo, times(1)).save(any(ConversationEvidence.class));
        Evidence again = ledger.register("c2", ev("A"));
        assertThat(again.no()).isEqualTo(1);
        Evidence fresh = ledger.register("c2", ev("B"));
        assertThat(fresh.no()).isEqualTo(2);
        assertThat(ledger.newInTurn("c2")).extracting(Evidence::refKey).containsExactly("B");
        assertThat(ledger.knownNumbers("c2")).containsExactlyInAnyOrder(1, 2);
    }

    /**
     * fix round 2 (H1): {@code endTurn()}은 더 이상 {@code maxTurnNo+1}로 turnNo를 스스로
     * 계산하지 않는다 — 호출자(AgentService)가 assistant 메시지 순번을 넘긴다. 근거 없는
     * 턴이 1·2번째로 먼저 와도(이 메서드는 그 턴들에서는 아예 호출되지 않거나, 호출돼도
     * newInTurn이 비어 저장할 게 없다) 3번째 턴에서 비로소 근거가 생기면 DB에는 turnNo=1이
     * 아니라 turnNo=3으로 저장돼야 한다 — transcript()의 "3번째 assistant 줄"과 맞아야 하기
     * 때문이다(2026-09-29 evidence_smoke.py UC3 실측, task-5-report.md).
     */
    @Test
    void 근거_없는_턴을_건너뛴_turnNo도_그대로_저장된다() {
        when(repo.maxEvidenceNo("c5")).thenReturn(0);
        when(repo.findByConversationIdOrderByEvidenceNo("c5")).thenReturn(List.of());
        when(repo.maxTurnNo("c5")).thenReturn(0);   // DB에는 아직 이 대화의 근거가 하나도 없다

        ledger.register("c5", ev("A"));
        int turn = ledger.endTurn("c5", 3);   // 1·2번째 턴은 근거가 없어 건너뛰고 3번째에 등록

        assertThat(turn).isEqualTo(3);
        ArgumentCaptor<ConversationEvidence> cap = ArgumentCaptor.forClass(ConversationEvidence.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getTurnNo()).isEqualTo(3);
    }

    @Test
    void DB_max_다음_번호() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        ConversationEvidence row = ConversationEvidence.builder().conversationId("c3").turnNo(1).evidenceNo(5)
                .payload(om.writeValueAsString(ev("OLD").withNo(5))).build();
        when(repo.maxEvidenceNo("c3")).thenReturn(5);
        when(repo.findByConversationIdOrderByEvidenceNo("c3")).thenReturn(List.of(row));
        assertThat(ledger.register("c3", ev("NEW")).no()).isEqualTo(6);
        assertThat(ledger.register("c3", ev("OLD")).no()).isEqualTo(5);   // DB에 있던 것도 재사용
        assertThat(ledger.summaryLine("c3")).contains("#5 제목 OLD").contains("#6 제목 NEW");
    }

    // 새 대화의 첫 접근(state() 최초 적재)이 서로 다른 스레드에서 동시에 일어나도
    // (같은 턴의 도구 호출이 다른 스레드에서 실행될 수 있다) 번호가 겹치거나 DB 복원이
    // 두 번 실행되면 안 된다. 20회 반복해 타이밍에 따른 우연한 통과를 배제한다.
    @Test
    void 동시_첫_등록도_번호가_겹치지_않는다() throws Exception {
        when(repo.maxEvidenceNo(anyString())).thenReturn(5);
        when(repo.findByConversationIdOrderByEvidenceNo(anyString())).thenReturn(List.of());

        int threadCount = 8;
        for (int iter = 0; iter < 20; iter++) {
            String cid = "concurrent-" + iter;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Evidence>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                String key = "T" + t;
                futures.add(pool.submit(() -> {
                    start.await();
                    return ledger.register(cid, ev(key));
                }));
            }
            start.countDown();

            Set<Integer> numbers = new HashSet<>();
            for (Future<Evidence> f : futures) {
                numbers.add(f.get(5, TimeUnit.SECONDS).no());
            }
            pool.shutdown();

            assertThat(numbers).as("iter=%d", iter).containsExactlyInAnyOrder(6, 7, 8, 9, 10, 11, 12, 13);
            verify(repo, times(1)).findByConversationIdOrderByEvidenceNo(cid);
        }
    }

    /**
     * fix round 1 (M1): {@code AgentService.loadEvidenceByTurn()}를 이 클래스로 옮긴 것 —
     * turn_no로 묶고, 손상된 payload 행은 {@code ensureLoaded}와 같은 정책(warn만 남기고
     * 건너뜀)으로 처리한다.
     */
    @Test
    void allGroupedByTurn_턴별로_묶고_손상된_payload는_건너뛴다() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        ConversationEvidence row1 = ConversationEvidence.builder().conversationId("c4").turnNo(1).evidenceNo(1)
                .payload(om.writeValueAsString(ev("A").withNo(1))).build();
        ConversationEvidence badRow = ConversationEvidence.builder().conversationId("c4").turnNo(1).evidenceNo(2)
                .payload("{이건 유효한 JSON이 아니다").build();
        ConversationEvidence row3 = ConversationEvidence.builder().conversationId("c4").turnNo(2).evidenceNo(3)
                .payload(om.writeValueAsString(ev("B").withNo(3))).build();
        when(repo.findByConversationIdOrderByEvidenceNo("c4")).thenReturn(List.of(row1, badRow, row3));

        Map<Integer, List<Evidence>> grouped = ledger.allGroupedByTurn("c4");

        assertThat(grouped).containsOnlyKeys(1, 2);
        assertThat(grouped.get(1)).extracting(Evidence::refKey).containsExactly("A");
        assertThat(grouped.get(2)).extracting(Evidence::refKey).containsExactly("B");
    }
}
