package io.saife.evidence.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.Evidence;
import io.saife.evidence.domain.ConversationEvidence;
import io.saife.evidence.repository.ConversationEvidenceRepository;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화별 근거 원장. ToolCallContext처럼 conversationId 키의 메모리 맵 + DB(conversation_evidence).
 * 번호는 대화 전체에서 유일. 같은 (kind, refKey)는 같은 번호. 턴 종료 시 이번 턴 신규분만 DB에 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceLedger {
    private static class State {
        final Map<String, Evidence> byIdentity = new LinkedHashMap<>();   // identity → 번호 붙은 Evidence
        final List<Evidence> newInTurn = new ArrayList<>();
        int next;
        boolean loaded;
    }

    private final ConversationEvidenceRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    // 대화(State)별로 잠근다 — 같은 턴의 도구 호출이 서로 다른 스레드에서 register()를
    // 동시에 부를 수 있으므로, 인스턴스 전체가 아니라 conversationId 엔트리 단위로 직렬화한다.
    //
    // ⚠️ 예전 버전은 state()가 잠금 밖에서 s.loaded를 읽는 "빠른 경로"를 두고 있었다(고전적인
    // double-checked locking 버그): s.loaded는 volatile이 아니므로, 잠금 밖에서 true를 관찰해도
    // 그 값을 만든 스레드가 채운 next/byIdentity와의 happens-before가 보장되지 않는다. 게다가 같은
    // 신규 대화에 대해 register()를 동시에 호출한 스레드 두 개가 나란히 "아직 안 실렸다"를 보고
    // DB 복원을 각자 실행하거나, 복원 중 next 값을 서로 덮어써 같은 번호를 두 번 내줄 수 있었다.
    // 지금은 모든 메서드가 synchronized(s) **안에서** ensureLoaded()로 loaded를 확인·적재하므로,
    // 첫 접근 스레드가 락을 쥔 채로 복원을 끝내고 loaded=true를 세울 때까지 다른 스레드는 아예
    // 들어오지 못한다 — loaded는 그래서 plain 필드로 둬도 안전하다.
    public Evidence register(String conversationId, Evidence e) {
        State s = state(conversationId);
        synchronized (s) {
            ensureLoaded(s, conversationId);
            Evidence existing = s.byIdentity.get(e.identity());
            if (existing != null) return existing;
            Evidence numbered = e.withNo(s.next++);
            s.byIdentity.put(numbered.identity(), numbered);
            s.newInTurn.add(numbered);
            return numbered;
        }
    }

    public List<Evidence> registerAll(String conversationId, List<Evidence> list) {
        return list.stream().map(e -> register(conversationId, e)).toList();
    }

    public List<Evidence> newInTurn(String conversationId) {
        State s = state(conversationId);
        synchronized (s) {
            ensureLoaded(s, conversationId);
            return List.copyOf(s.newInTurn);
        }
    }

    public Set<Integer> knownNumbers(String conversationId) {
        State s = state(conversationId);
        synchronized (s) {
            ensureLoaded(s, conversationId);
            Set<Integer> out = new HashSet<>();
            s.byIdentity.values().forEach(e -> out.add(e.no()));
            return out;
        }
    }

    public List<Evidence> all(String conversationId) {
        State s = state(conversationId);
        synchronized (s) {
            ensureLoaded(s, conversationId);
            return s.byIdentity.values().stream().sorted(Comparator.comparingInt(Evidence::no)).toList();
        }
    }

    /**
     * 이번 턴 신규분을 DB에 쓰고 턴 경계를 넘긴다.
     *
     * <p><b>fix round 2 (H1)</b>: {@code turnNo}는 더 이상 이 메서드가 스스로 계산하지 않는다.
     * 예전에는 {@code repository.maxTurnNo(conversationId) + 1}로 냈는데, 이건 "몇 번째
     * 실제 대화 턴인가"가 아니라 "몇 번째로 근거가 있었던 턴인가"를 센다 — 근거 없는 턴은
     * {@code newInTurn}이 비어 이 메서드 자체가 호출은 되어도(TurnFinisher가 매 턴 부른다)
     * 그 턴이 전용으로 쓸 turnNo가 존재하지 않았다. 근거 발생 전에 근거 없는 턴이 하나라도
     * 있으면 {@code AgentService.transcript()}가 "k번째 assistant 줄"로 매핑하는 turnNo와
     * DB에 저장된 turnNo가 어긋난다(실측: 2026-09-29 evidence_smoke.py UC3, task-5-report.md).
     *
     * <p>이제 호출자({@code AgentService.TurnFinisher}, 그리고 그 위의 {@code AgentService}
     * 자신)가 {@code assistantTurnIndex(messages)}로 계산한 값을 넘긴다.
     *
     * <p><b>fix round 1 (ruling R52)</b>: "같은 메시지 구조에서 뽑으니 어긋날 수 없다"는
     * 처음 버전의 주장은 틀렸다 — {@code transcript()}가 텍스트가 빈 assistant 줄을
     * 세는 대상에서 건너뛰고 있었고, {@code AgentService}의 계산은 텍스트를 보지 않았다.
     * 빈 턴이 근거 있는 턴보다 먼저 오면 두 turnNo가 어긋나 근거가 엉뚱한 줄에 붙거나
     * 유실됐다(리뷰에서 발견). {@code transcript()}가 빈 assistant 줄도 건너뛰지 않도록
     * 고친 지금은 두 계산이 정말로 같은 필터링 기준(텍스트 무관, user/assistant 타입만)을
     * 쓰므로 일치한다 — 이 동등성은 두 쪽의 필터링 기준이 계속 같아야만 유지된다.
     *
     * @param turnNo 이번에 끝내는 턴의 assistant 메시지 순번(1부터). 근거 유무와 무관하게 매
     *               턴 1씩 증가해야 한다
     * @return turnNo (호출자가 넘긴 값 그대로 반환 — 반환값을 쓰는 코드와의 호환을 위해 유지)
     */
    @Transactional
    public int endTurn(String conversationId, int turnNo) {
        State s = state(conversationId);
        synchronized (s) {
            ensureLoaded(s, conversationId);
            // 방어적 점검 — 정상 흐름에서 턴은 되돌아가지 않으므로 새 turnNo는 항상 DB의
            // 이전 최댓값보다 커야 한다. 어긋나면 새 회귀의 신호이니 저장은 그대로 진행하되
            // 경고를 남긴다(maxTurnNo는 이제 turnNo 계산이 아니라 이 DB 복원 정합성 점검에만 쓴다).
            int previousMax = repository.maxTurnNo(conversationId);
            if (turnNo <= previousMax) {
                log.warn("[LEDGER] turnNo={}가 이전 최댓값={} 이하다 — transcript 매핑 점검 필요 cid={}",
                        turnNo, previousMax, conversationId);
            }
            for (Evidence e : s.newInTurn) {
                try {
                    repository.save(ConversationEvidence.builder().conversationId(conversationId).turnNo(turnNo)
                            .evidenceNo(e.no()).payload(objectMapper.writeValueAsString(e)).build());
                } catch (Exception ex) {
                    log.warn("[LEDGER] 저장 실패 cid={} no={}: {}", conversationId, e.no(), ex.getMessage());
                }
            }
            s.newInTurn.clear();
            return turnNo;
        }
    }

    public void discard(String conversationId) { states.remove(conversationId); }

    /** 다음 턴 시스템 프롬프트 끝에 붙일 목록. 최대 20개, 제목 40자 */
    public String summaryLine(String conversationId) {
        List<Evidence> all = all(conversationId);
        if (all.isEmpty()) return "";
        StringJoiner sj = new StringJoiner(", ");
        all.stream().limit(20).forEach(e -> sj.add("#" + e.no() + " " + (e.title().length() > 40 ? e.title().substring(0, 40) + "…" : e.title())));
        return sj.toString();
    }

    /**
     * fix round 1 (M1): {@code AgentService.transcript()}가 assistant 줄마다 그 턴의 근거를
     * 붙일 때 쓴다. DB를 직접 읽어 turn_no로 묶는다(메모리 State는 conversationId당 "지금까지
     * 등록된 전체"만 갖고 있어 턴별로 쪼갤 수 없다 — turn_no는 DB 행에만 있다).
     *
     * <p>payload 디코딩 실패는 {@link #ensureLoaded}와 같은 정책으로 조용히 건너뛰고 warn만
     * 남긴다 — 근거 한 건 복원 실패로 대화 전체 기록 화면이 깨지면 안 된다.
     *
     * @return turn_no 오름차순 {@link TreeMap}. 각 턴의 리스트는 evidence_no(={@link Evidence#no()})
     *         오름차순
     */
    public Map<Integer, List<Evidence>> allGroupedByTurn(String conversationId) {
        Map<Integer, List<Evidence>> out = new TreeMap<>();
        for (ConversationEvidence row : repository.findByConversationIdOrderByEvidenceNo(conversationId)) {
            try {
                Evidence e = objectMapper.readValue(row.getPayload(), Evidence.class);
                out.computeIfAbsent(row.getTurnNo(), k -> new ArrayList<>()).add(e);
            } catch (Exception ex) {
                log.warn("[LEDGER] 복원 실패 no={}", row.getEvidenceNo());
            }
        }
        out.values().forEach(list -> list.sort(Comparator.comparingInt(Evidence::no)));
        return out;
    }

    // conversationId당 State 객체 생성만 ConcurrentHashMap에 맡긴다(atomic). 이 메서드 자체는
    // 잠그지 않는다 — DB 적재 확인·수행은 항상 호출자가 이미 잡고 있는 synchronized(s) 구간
    // 안에서 ensureLoaded()로 일어나야 하기 때문에, 여기서 따로 잠갔다가 풀면 그 사이에 다른
    // 스레드가 끼어들 틈이 생긴다(바로 이번에 고친 경쟁 조건).
    private State state(String conversationId) {
        return states.computeIfAbsent(conversationId, k -> new State());
    }

    // 호출자가 이미 synchronized(s)를 잡은 상태에서만 불러야 한다(그래서 스스로는 잠그지 않는다).
    // DB(JDBC) 호출을 대화별 잠금 안에서 그대로 실행하는 것은 의도적인 선택이다 — 원장은 대화
    // 하나당 매 턴 몇 건 저장·조회하는 수준이라 잠금 구간이 길어져도 실질적인 경합 비용이 없고,
    // 그 대가로 "적재 확인"과 "번호 부여"를 하나의 원자적 구간으로 묶어 첫 접근 경쟁으로
    // 번호가 중복 발급되는 것을 구조적으로 막는다.
    private void ensureLoaded(State s, String conversationId) {
        if (s.loaded) return;
        for (ConversationEvidence row : repository.findByConversationIdOrderByEvidenceNo(conversationId)) {
            try {
                Evidence e = objectMapper.readValue(row.getPayload(), Evidence.class);
                s.byIdentity.put(e.identity(), e);
            } catch (Exception ex) { log.warn("[LEDGER] 복원 실패 no={}", row.getEvidenceNo()); }
        }
        s.next = repository.maxEvidenceNo(conversationId) + 1;
        s.loaded = true;
    }
}
