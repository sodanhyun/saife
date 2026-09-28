# 근거 계층 B1 — 에이전트·도구·UC2·UC1 백엔드 표면 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검색 결과가 대화·작업계획서·사고 등록·사진 판독 응답에 번호 붙은 근거 카드로 나타나게 한다. 모델은 `[#n]`만 쓰고, 번호는 백엔드 원장이 매기며, 환각 번호는 후처리로 지운다.

**Architecture:** `io.saife.evidence.ledger`(원장·후처리), 도구 3종 내부 재배선(`HazardAnalysisTools`), `AgentService`에 `ai.evidence` 발행·`conversation_evidence` 저장·transcript 확장, `WorkPlanTools.createWorkPlan`에 `work_plan_evidence` 저장, `IncidentService`·`VisionAssessmentService`에 근거 필드 추가.

**Tech Stack:** A1~A4 산출물 · Spring AI `ToolContext` · JUnit 5 + Mockito.

**Spec:** §7 전체, §6.4 `checkLatest`, §8.3 타입 계약(백엔드 쪽) · 선행: A1~A4

## Global Constraints

- A1 Global Constraints 적용. 도구는 **6종 그대로**. `ToolCallTracker.execute` 래퍼 유지.
- 원장 번호는 대화 전체에서 유일하고 같은 `(kind, refKey)`는 같은 번호를 재사용한다.
- SSE 이벤트는 `SseService.SseEvent.of()` + `sseService.send()`만. 새 이벤트 `ai.evidence`의 봉투는 `.claude/rules/sse-streaming.md`에 추가한다.
- 모델 출력의 `[#n]` 중 원장에 없는 번호는 **제거**(대괄호째). 있는 번호는 그대로.
- 데모 모드 경로(`DemoConversationScript`)도 같은 원장·이벤트를 낸다(도구가 실제로 돌기 때문에 자동이지만 `ai.evidence` 발행 코드는 `runDemoTurn`에도 넣는다).

## Review Focus

1. 같은 사례가 두 턴에서 다시 검색되면 번호가 바뀌지 않고, 두 번째 턴의 `ai.evidence`에는 새 근거만 실린다. → Task 1 `EvidenceLedgerTest.재등장은_같은_번호_새_턴에는_안_실림`.
2. 모델이 `[#7]`처럼 없는 번호를 쓰면 문장에서 `[#7]`이 사라지고 나머지 텍스트는 그대로다. `[#3][#7]`처럼 붙어 있어도 `[#3]`만 남는다. → Task 1 `CitationSanitizerTest.붙어있는_번호`.
3. 서버 재시작 후 같은 대화가 이어지면 원장이 DB에서 max 번호를 읽어 그 다음 번호부터 매긴다. → Task 1 `EvidenceLedgerTest.DB_max_다음_번호`.
4. `searchCases`가 `query` 없이(구버전 호출) 불려도 축·업종만으로 동작해야 한다. → Task 3 `HazardAnalysisToolsTest.query_없이도_동작`.
5. 사고 등록 시 검색이 예외를 던져도 등록 트랜잭션이 롤백되면 안 된다(근거는 빈 리스트). → Task 5 `IncidentServiceEvidenceTest.검색_실패는_등록을_막지_않는다`.

---

### Task 1: `EvidenceLedger` + `CitationSanitizer`

**Files:**
- Create: `backend/src/main/java/io/saife/evidence/ledger/EvidenceLedger.java`
- Create: `backend/src/main/java/io/saife/evidence/ledger/CitationSanitizer.java`
- Test: `backend/src/test/java/io/saife/evidence/ledger/EvidenceLedgerTest.java`, `CitationSanitizerTest.java`

**Interfaces:**
- Produces:
  - `EvidenceLedger.register(String conversationId, Evidence e) → Evidence`(번호 부여됨), `registerAll(cid, List<Evidence>) → List<Evidence>`
  - `EvidenceLedger.newInTurn(cid) → List<Evidence>`(이번 턴에 처음 등록된 것), `EvidenceLedger.endTurn(cid) → int turnNo`(DB 저장 + 턴 경계), `EvidenceLedger.all(cid) → List<Evidence>`(DB 포함), `EvidenceLedger.knownNumbers(cid) → Set<Integer>`, `EvidenceLedger.discard(cid)`
  - `EvidenceLedger.summaryLine(cid) → String`(`#1 제목, #2 제목 …` 최대 20개, 40자)
  - `CitationSanitizer.sanitize(String text, Set<Integer> known) → String`, `CitationSanitizer.citedNumbers(String) → List<Integer>`

- [ ] **Step 1: 실패하는 테스트**

`EvidenceLedgerTest.java`:
```java
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceLedgerTest {
    private final ConversationEvidenceRepository repo = mock(ConversationEvidenceRepository.class);
    private final EvidenceLedger ledger = new EvidenceLedger(repo, new ObjectMapper());

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
        int turn = ledger.endTurn("c2");
        assertThat(turn).isEqualTo(1);
        verify(repo, times(1)).save(any(ConversationEvidence.class));
        Evidence again = ledger.register("c2", ev("A"));
        assertThat(again.no()).isEqualTo(1);
        Evidence fresh = ledger.register("c2", ev("B"));
        assertThat(fresh.no()).isEqualTo(2);
        assertThat(ledger.newInTurn("c2")).extracting(Evidence::refKey).containsExactly("B");
        assertThat(ledger.knownNumbers("c2")).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void DB_max_다음_번호() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ConversationEvidence row = ConversationEvidence.builder().conversationId("c3").turnNo(1).evidenceNo(5)
                .payload(om.writeValueAsString(ev("OLD").withNo(5))).build();
        when(repo.maxEvidenceNo("c3")).thenReturn(5);
        when(repo.findByConversationIdOrderByEvidenceNo("c3")).thenReturn(List.of(row));
        assertThat(ledger.register("c3", ev("NEW")).no()).isEqualTo(6);
        assertThat(ledger.register("c3", ev("OLD")).no()).isEqualTo(5);   // DB에 있던 것도 재사용
        assertThat(ledger.summaryLine("c3")).contains("#5 제목 OLD").contains("#6 제목 NEW");
    }
}
```

`CitationSanitizerTest.java`:
```java
package io.saife.evidence.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationSanitizerTest {
    @Test
    void 없는_번호는_제거_있는_번호는_유지() {
        assertThat(CitationSanitizer.sanitize("사례가 있습니다 [#1]. 지침 [#7]도 참고.", Set.of(1)))
                .isEqualTo("사례가 있습니다 [#1]. 지침도 참고.");
    }

    @Test
    void 붙어있는_번호() {
        assertThat(CitationSanitizer.sanitize("근거 [#3][#7][#2].", Set.of(2, 3))).isEqualTo("근거 [#3][#2].");
    }

    @Test
    void 공백_변형도_정규화() {
        assertThat(CitationSanitizer.sanitize("A [ # 4 ] B", Set.of(4))).isEqualTo("A [#4] B");
        assertThat(CitationSanitizer.citedNumbers("x [#2] y [#9] [#2]")).containsExactly(2, 9, 2);
    }

    @Test
    void null과_빈문자열() {
        assertThat(CitationSanitizer.sanitize(null, Set.of())).isEmpty();
        assertThat(CitationSanitizer.sanitize("", Set.of(1))).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.ledger.*"`
Expected: FAIL

- [ ] **Step 3: 구현**

`CitationSanitizer.java`:
```java
package io.saife.evidence.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 모델 출력의 [#n] 인용. 원장에 없는 번호는 지운다(환각 인용 차단). 공백 변형은 [#n]으로 정규화 */
public final class CitationSanitizer {
    private static final Pattern CITE = Pattern.compile("\\[\\s*#\\s*(\\d{1,4})\\s*\\]");
    private CitationSanitizer() {}

    public static String sanitize(String text, Set<Integer> known) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = CITE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, known.contains(n) ? Matcher.quoteReplacement("[#" + n + "]") : "");
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]+([.,!?])", "$1").replaceAll("  +", " ");
    }

    public static List<Integer> citedNumbers(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = CITE.matcher(text);
        while (m.find()) out.add(Integer.parseInt(m.group(1)));
        return out;
    }
}
```

`EvidenceLedger.java`:
```java
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

    public synchronized Evidence register(String conversationId, Evidence e) {
        State s = state(conversationId);
        Evidence existing = s.byIdentity.get(e.identity());
        if (existing != null) return existing;
        Evidence numbered = e.withNo(s.next++);
        s.byIdentity.put(numbered.identity(), numbered);
        s.newInTurn.add(numbered);
        return numbered;
    }

    public List<Evidence> registerAll(String conversationId, List<Evidence> list) {
        return list.stream().map(e -> register(conversationId, e)).toList();
    }

    public synchronized List<Evidence> newInTurn(String conversationId) {
        return List.copyOf(state(conversationId).newInTurn);
    }

    public synchronized Set<Integer> knownNumbers(String conversationId) {
        Set<Integer> out = new HashSet<>();
        state(conversationId).byIdentity.values().forEach(e -> out.add(e.no()));
        return out;
    }

    public synchronized List<Evidence> all(String conversationId) {
        return state(conversationId).byIdentity.values().stream().sorted(Comparator.comparingInt(Evidence::no)).toList();
    }

    /** 이번 턴 신규분을 DB에 쓰고 턴 경계를 넘긴다. 반환: 이번 턴 번호 */
    @Transactional
    public synchronized int endTurn(String conversationId) {
        State s = state(conversationId);
        int turn = repository.maxTurnNo(conversationId) + 1;
        for (Evidence e : s.newInTurn) {
            try {
                repository.save(ConversationEvidence.builder().conversationId(conversationId).turnNo(turn)
                        .evidenceNo(e.no()).payload(objectMapper.writeValueAsString(e)).build());
            } catch (Exception ex) {
                log.warn("[LEDGER] 저장 실패 cid={} no={}: {}", conversationId, e.no(), ex.getMessage());
            }
        }
        s.newInTurn.clear();
        return turn;
    }

    public synchronized void discard(String conversationId) { states.remove(conversationId); }

    /** 다음 턴 시스템 프롬프트 끝에 붙일 목록. 최대 20개, 제목 40자 */
    public synchronized String summaryLine(String conversationId) {
        List<Evidence> all = all(conversationId);
        if (all.isEmpty()) return "";
        StringJoiner sj = new StringJoiner(", ");
        all.stream().limit(20).forEach(e -> sj.add("#" + e.no() + " " + (e.title().length() > 40 ? e.title().substring(0, 40) + "…" : e.title())));
        return sj.toString();
    }

    private State state(String conversationId) {
        State s = states.computeIfAbsent(conversationId, k -> new State());
        if (!s.loaded) {
            for (ConversationEvidence row : repository.findByConversationIdOrderByEvidenceNo(conversationId)) {
                try {
                    Evidence e = objectMapper.readValue(row.getPayload(), Evidence.class);
                    s.byIdentity.put(e.identity(), e);
                } catch (Exception ex) { log.warn("[LEDGER] 복원 실패 no={}", row.getEvidenceNo()); }
            }
            s.next = repository.maxEvidenceNo(conversationId) + 1;
            s.loaded = true;
        }
        return s;
    }
}
```
`Evidence`는 record라 Jackson 역직렬화가 된다(Java 16+ record 지원). `Origin`·`EvidenceKind`는 enum 문자열.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests "io.saife.evidence.ledger.*"`
Expected: PASS 7건

```bash
git add backend/src/main/java/io/saife/evidence/ledger backend/src/test/java/io/saife/evidence/ledger
git commit -m "feat(evidence): 근거 원장(대화 전체 유일 번호, 턴별 신규분, DB 복원)과 인용 후처리"
```

---

### Task 2: `AgentService` — `ai.evidence` 발행, 후처리, 프롬프트, transcript 확장

**Files:**
- Modify: `backend/src/main/java/io/saife/ai/agent/AgentService.java`
- Modify: `.claude/rules/sse-streaming.md` (이벤트 2종 추가 — `ai.recall`은 연결성 계획이 채운다)
- Test: `backend/src/test/java/io/saife/ai/agent/AgentServiceEvidenceTest.java`

**Interfaces:**
- Produces: `AgentService.TranscriptLine(role, text, List<Evidence> evidence)`(assistant 줄에 그 턴의 근거), `GET /transcript` 응답 형태 변경(프론트 B2가 맞춘다).
- Consumes: `EvidenceLedger`, `CitationSanitizer`.

- [ ] **Step 1: 실패하는 테스트 (SSE 발행 순서와 후처리)**

```java
package io.saife.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.common.service.SseService;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.Origin;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentServiceEvidenceTest {
    @Test
    void 턴_종료_시_새_근거를_ai_evidence로_보내고_없는_번호를_지운다() {
        SseService sse = mock(SseService.class);
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        Evidence e1 = new Evidence(1, EvidenceKind.CASE_FATALITY, 1L, "F:1", "t", "s", null, null, null, Origin.CACHE, 0.5, OffsetDateTime.now(), Map.of());
        when(ledger.newInTurn("c")).thenReturn(List.of(e1));
        when(ledger.knownNumbers("c")).thenReturn(Set.of(1));
        when(ledger.endTurn("c")).thenReturn(1);

        AgentService.TurnFinisher finisher = new AgentService.TurnFinisher(sse, ledger);
        String text = finisher.finish("sess", "c", "사례 [#1]와 지침 [#9]를 보세요.");

        assertThat(text).isEqualTo("사례 [#1]와 지침를 보세요.".replace("지침를", "지침를"));   // [#9] 제거
        ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
        verify(sse, atLeastOnce()).send(eq("sess"), cap.capture());
        List<String> types = cap.getAllValues().stream().map(SseService.SseEvent::type).toList();
        assertThat(types).containsExactly("ai.evidence", "ai.token");
        Object payload = cap.getAllValues().get(0).payload();
        assertThat(payload).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) payload).get("items")).isEqualTo(List.of(e1));
        verify(ledger).endTurn("c");
    }

    @Test
    void 근거가_없으면_ai_evidence를_보내지_않는다() {
        SseService sse = mock(SseService.class);
        EvidenceLedger ledger = mock(EvidenceLedger.class);
        when(ledger.newInTurn("c")).thenReturn(List.of());
        when(ledger.knownNumbers("c")).thenReturn(Set.of());
        new AgentService.TurnFinisher(sse, ledger).finish("sess", "c", "안녕하세요");
        ArgumentCaptor<SseService.SseEvent> cap = ArgumentCaptor.forClass(SseService.SseEvent.class);
        verify(sse).send(eq("sess"), cap.capture());
        assertThat(cap.getValue().type()).isEqualTo("ai.token");
    }
}
```
(첫 테스트의 기대 문자열은 `"사례 [#1]와 지침를 보세요."`이다 — 후처리는 조사를 고치지 않는다. 테스트에서 `.replace` 장난 없이 그 문자열을 그대로 쓴다.)

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.ai.agent.AgentServiceEvidenceTest`
Expected: FAIL (`TurnFinisher` 없음)

- [ ] **Step 3: 구현 — `AgentService` 수정**

(1) 정적 중첩 클래스 `TurnFinisher`를 추가한다(테스트 가능하도록 SSE·원장만 의존):
```java
    /** 턴 마무리: 인용 후처리 → ai.evidence(신규분) → ai.token(전문) → 원장 턴 경계. 라이브·데모 공통 */
    static final class TurnFinisher {
        private final SseService sse;
        private final EvidenceLedger ledger;
        TurnFinisher(SseService sse, EvidenceLedger ledger) { this.sse = sse; this.ledger = ledger; }

        String finish(String sessionId, String conversationId, String rawText) {
            String text = CitationSanitizer.sanitize(rawText, ledger.knownNumbers(conversationId));
            List<Evidence> fresh = ledger.newInTurn(conversationId);
            if (!fresh.isEmpty()) {
                send(sessionId, "ai.evidence", conversationId, conversationId, Map.of("items", fresh));
            }
            if (!text.isBlank()) {
                send(sessionId, "ai.token", conversationId, null, text);
            }
            ledger.endTurn(conversationId);
            return text;
        }

        private void send(String sessionId, String type, String cid, String target, Object payload) {
            if (sessionId == null) return;
            try { sse.send(sessionId, SseService.SseEvent.of(type, cid, target, payload)); }
            catch (Exception e) { log.warn("SSE 발행 실패 type={}: {}", type, e.getMessage()); }
        }
    }
```
(2) 필드 추가: `private final EvidenceLedger evidenceLedger;` 그리고 `@PostConstruct`가 아니라 생성 시점에 `finisher = new TurnFinisher(sseService, evidenceLedger)` — Lombok `@RequiredArgsConstructor`라 생성자에 못 넣으므로 `private TurnFinisher finisher() { return new TurnFinisher(sseService, evidenceLedger); }` 메서드로 둔다.

(3) `runTurn`에서
```java
            emitSlotHintIfAny(sessionId, conversationId);
            persistToolCalls(conversationId);
            String finalText = finisher().finish(sessionId, conversationId, text);
            messages.add(new AssistantMessage(finalText));
            saveHistory(conversationId, messages);
            emit(sessionId, "ai.done", conversationId, null, Map.of("finished", true));
```
로 바꾼다(기존 `emitToken` 호출 삭제). `runDemoTurn`도 같은 순서로: `String finalText = finisher().finish(sessionId, conversationId, answer);` 후 `messages.add(new AssistantMessage(finalText))`.

(4) 시스템 프롬프트 템플릿 `[진행 순서]` 다음에 추가:
```
            [근거 인용]
            - 사고사례·지침·법 조문·MSDS를 언급할 때는 도구가 준 근거 번호를 문장 끝에 [#n] 형식으로 붙이세요.
            - 번호가 없는 출처를 지어내지 마세요. URL, 파일명, 사진을 직접 쓰지 마세요.
            - searchCases에는 작업 설명을 query로 넘기세요 (예: "사다리 위 천장 도장 작업").
```
그리고 `loadHistory`에서 시스템 메시지를 만들 때 원장 요약을 덧붙인다:
```java
        String summary = evidenceLedger.summaryLine(conversationId);
        String system = summary.isEmpty() ? systemPrompt() : systemPrompt() + "\n[이미 제시한 근거] " + summary;
        messages.add(new SystemMessage(system));
```

(5) transcript 확장:
```java
    public record TranscriptLine(String role, String text, List<Evidence> evidence) {}
```
`transcript()`에서 assistant 줄마다 턴 순서대로 `conversation_evidence`의 `turn_no`별 근거를 붙인다: `conversationEvidenceRepository.findByConversationIdOrderByEvidenceNo(id)`를 `turnNo`로 그룹화하고, assistant 줄의 순번 k(1부터)에 `turnNo == k`인 근거를 매핑한다. user 줄은 `List.of()`.

(6) `.claude/rules/sse-streaming.md` 이벤트 표에 추가:
```
| `ai.evidence` | 턴 종료, `ai.token` 앞 | `{items: Evidence[]}` — 이번 턴에 새로 등록된 근거 카드. `types/evidence.ts` |
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.ai.agent.AgentServiceEvidenceTest`
Expected: PASS 2건

```bash
git add backend/src/main/java/io/saife/ai/agent/AgentService.java .claude/rules/sse-streaming.md backend/src/test/java/io/saife/ai/agent
git commit -m "feat(agent): 턴 종료 시 ai.evidence 발행, [#n] 후처리, 원장 요약 프롬프트, transcript에 근거 포함"
```

---

### Task 3: 도구 재배선 — `searchCases`·`analyzeHazards`·`getMsds`

**Files:**
- Modify: `backend/src/main/java/io/saife/ai/tools/HazardAnalysisTools.java`
- Modify: `backend/src/main/java/io/saife/publicapi/service/PublicApiCrawler.java` (`checkLatest`)
- Test: `backend/src/test/java/io/saife/ai/tools/HazardAnalysisToolsTest.java`

**Interfaces:**
- Consumes: `EvidenceSearchService.search`, `LawArticleService.get`, `LawCitationTable`, `MsdsLiveClient.resolve`, `EvidenceLedger.register`, `AgentContextKeys.CONVERSATION_ID`.
- Produces: `PublicApiCrawler.checkLatest(String dataset) → Optional<LatestInfo(int totalCount, OffsetDateTime checkedAt)>`.
- 도구 결과 텍스트 규약: 근거 줄은 `#n [태그] 제목 (유사도 84%, 캐시 09-21)`.

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.common.service.SseService;
import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.ledger.EvidenceLedger;
import io.saife.evidence.live.*;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.publicapi.service.PublicApiCrawler;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

class HazardAnalysisToolsTest {
    private final EvidenceSearchService search = mock(EvidenceSearchService.class);
    private final LawArticleService laws = mock(LawArticleService.class);
    private final MsdsLiveClient msds = mock(MsdsLiveClient.class);
    private final EvidenceLedger ledger = mock(EvidenceLedger.class);
    private final PublicApiCrawler crawler = mock(PublicApiCrawler.class);
    private final SseService sse = mock(SseService.class);
    private final ToolContext ctx = new ToolContext(Map.of(AgentContextKeys.CONVERSATION_ID, "c1", AgentContextKeys.SESSION_ID, "s1"));

    private Evidence ev(int no, EvidenceKind k, String title, boolean image) {
        Map<String, Object> meta = new HashMap<>(); meta.put("hasImage", image);
        return new Evidence(no, k, 1L, k + ":" + no, title, "s", null, image ? "/api/media/case/1/photo" : null, null, Origin.CACHE, 0.84, OffsetDateTime.now(), meta);
    }

    private HazardAnalysisTools tools() {
        when(ledger.registerAll(eq("c1"), anyList())).thenAnswer(inv -> {
            List<Evidence> in = inv.getArgument(1);
            List<Evidence> out = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) out.add(in.get(i).withNo(i + 1));
            return out;
        });
        return new HazardAnalysisTools(search, laws, msds, ledger, crawler, sse);
    }

    @Test
    void searchCases는_query로_검색하고_번호를_붙인다() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.CASE_FATALITY, "[협착] 스크류에 끼임", true)));
        when(crawler.checkLatest("FATALITY")).thenReturn(Optional.empty());
        String out = tools().searchCases("CAUGHT", "제조업", "설비 내부 청소 중 끼임", ctx);
        assertThat(out).contains("#1").contains("[사진]").contains("스크류에 끼임").contains("84%");
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(cap.capture());
        assertThat(cap.getValue().query()).isEqualTo("설비 내부 청소 중 끼임");
        assertThat(cap.getValue().accidentType()).isEqualTo(AccidentType.CAUGHT);
        assertThat(cap.getValue().business()).isEqualTo("제조업");
    }

    @Test
    void query_없이도_동작() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.CASE_DISASTER, "[추락] 지붕", false)));
        when(crawler.checkLatest(anyString())).thenReturn(Optional.empty());
        String out = tools().searchCases("FALL", null, null, ctx);
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(cap.capture());
        assertThat(cap.getValue().query()).isEqualTo("추락");   // 축 라벨로 대체
        assertThat(out).contains("#1");
    }

    @Test
    void analyzeHazards는_지침_벡터검색과_고정_조문을_붙인다() {
        when(search.search(any(SearchRequest.class))).thenReturn(List.of(ev(0, EvidenceKind.GUIDE, "[KOSHA GUIDE G-1] 사다리", false)));
        io.saife.evidence.domain.LawArticle a = io.saife.evidence.domain.LawArticle.builder().id(7L).lawId("L").lawName("산업안전보건기준에 관한 규칙").articleNo(42).articleSub(0).paragraphNo(1).title("추락의 방지").text("① 사업주는 …").sourceUrl("https://www.law.go.kr/x#J42:0").build();
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(new Fetched<>(List.of(a), Origin.LIVE, OffsetDateTime.now(), null));
        String out = tools().analyzeHazards("도장", "천장", "이동식 사다리", "유성페인트", ctx);
        assertThat(out).contains("추락").contains("KOSHA GUIDE").contains("제42조").contains("#");
        verify(ledger, atLeastOnce()).registerAll(eq("c1"), anyList());
    }

    @Test
    void getMsds는_라이브_결과와_픽토그램을_근거로() {
        MsdsLiveClient.MsdsBundle b = new MsdsLiveClient.MsdsBundle("001032", "톨루엔", "108-88-3", "1294", "GHS02,GHS07",
                Map.of("02", List.of("인화성 액체 : 구분2"), "08", List.of("TWA 50ppm")));
        when(msds.resolve("유성페인트")).thenReturn(new Fetched<>(b, Origin.LIVE, OffsetDateTime.now(), null));
        String out = tools().getMsds("유성페인트", ctx);
        assertThat(out).contains("톨루엔").contains("GHS02").contains("TWA 50ppm").contains("실시간").contains("#1");
        ArgumentCaptor<List<Evidence>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledger).registerAll(eq("c1"), cap.capture());
        assertThat(cap.getValue().get(0).kind()).isEqualTo(EvidenceKind.MSDS);
        assertThat(cap.getValue().get(0).meta()).containsEntry("pictograms", "GHS02,GHS07");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew test --tests io.saife.ai.tools.HazardAnalysisToolsTest`
Expected: FAIL (생성자·시그니처 불일치)

- [ ] **Step 3: 구현**

`PublicApiCrawler`에 추가:
```java
    public record LatestInfo(int totalCount, OffsetDateTime checkedAt) {}

    /** 공단에 최신 등재 건수만 확인한다(1페이지 1건). 카드 메타 "공단 기준" 표시용. 키 없거나 실패면 empty */
    public Optional<LatestInfo> checkLatest(String datasetName) {
        Dataset dataset = DATASETS.stream().filter(d -> d.dataset().equalsIgnoreCase(datasetName)).findFirst().orElse(null);
        String key = dataset == null ? null : keyFor(dataset.dataset());
        if (dataset == null || key == null || key.isBlank()) return Optional.empty();
        try {
            String url = "%s%s?serviceKey=%s&callApiId=%s&numOfRows=1&pageNo=1&type=json".formatted(baseUrl, dataset.path(),
                    URLEncoder.encode(key, StandardCharsets.UTF_8), dataset.callApiId());
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return Optional.empty();
            return Optional.of(new LatestInfo(MAPPER.readTree(res.body()).path("body").path("totalCount").asInt(0), OffsetDateTime.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
```

`HazardAnalysisTools` 전체 교체(생성자 의존: `EvidenceSearchService search, LawArticleService laws, MsdsLiveClient msds, EvidenceLedger ledger, PublicApiCrawler crawler, SseService sseService` — `@RequiredArgsConstructor` 순서대로 필드 선언):

```java
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    @Tool(description = """
            <tool-description>
            <purpose>작업 유형·장소·설비·취급물질 조합으로 발생형태별 위험요인을 도출하고, 관련 KOSHA GUIDE 기술지침과 법 조문을 근거 번호와 함께 반환합니다.</purpose>
            <returns>발생형태 목록과 각 축의 빠진 안전조치, 관련 기술지침(#n)과 법 조문(#n). 위험성 등급은 여기서 정하지 않습니다.</returns>
            <prerequisites>findLocationEquipment로 장소를 확인한 뒤 호출하세요.</prerequisites>
            </tool-description>
            """)
    public String analyzeHazards(@ToolParam(description = "작업 유형 (예: 도장, 용접, 정비, 청소)") String workType,
                                 @ToolParam(description = "작업 장소 설명", required = false) String location,
                                 @ToolParam(description = "사용 설비 (예: 이동식 사다리, 고소작업대)", required = false) String equipment,
                                 @ToolParam(description = "취급 물질 (예: 유성페인트, 시너)", required = false) String material,
                                 ToolContext toolContext) {
        return ToolCallTracker.execute("analyzeHazards", Map.of("workType", String.valueOf(workType), "equipment", String.valueOf(equipment)),
                sseService, toolContext, () -> {
            String combined = String.join(" ", nvl(workType), nvl(location), nvl(equipment), nvl(material)).strip();
            Set<AccidentType> axes = deriveAxes(combined);
            if (axes.isEmpty()) {
                return ToolResult.of("작업 정보가 부족해 위험요인을 도출할 수 없습니다. 작업 유형과 사용 설비를 물어보세요.");
            }
            String cid = AgentContextKeys.conversationId(toolContext);
            StringBuilder sb = new StringBuilder("도출된 발생형태 " + axes.size() + "종:\n");
            for (AccidentType axis : axes) sb.append("- ").append(axis.getLabel()).append(": ").append(axis.getMissingControlHint()).append('\n');

            List<Evidence> guides = ledger.registerAll(cid, search.search(SearchRequest.guides(combined, 3)));
            if (!guides.isEmpty()) {
                sb.append("\n관련 기술지침:\n");
                guides.forEach(g -> sb.append(line(g)).append('\n'));
            }
            List<Evidence> lawCards = new ArrayList<>();
            for (AccidentType axis : axes) {
                for (LawCitationTable.Citation c : LawCitationTable.forAxis(axis).stream().limit(2).toList()) lawCards.addAll(lawEvidence(c));
            }
            for (LawCitationTable.Citation c : LawCitationTable.common()) lawCards.addAll(lawEvidence(c));
            List<Evidence> numberedLaws = ledger.registerAll(cid, lawCards);
            if (!numberedLaws.isEmpty()) {
                sb.append("\n관련 법 조문:\n");
                numberedLaws.forEach(l -> sb.append(line(l)).append('\n'));
            }
            sb.append("\n※ 위험성 등급은 작업높이·안전대 부착설비 등 현장 확인 항목을 받은 뒤 룰 엔진이 결정합니다.\n");
            return ToolResult.of(sb.toString());
        });
    }

    @Tool(description = """
            <tool-description>
            <purpose>유사한 실제 사고사례를 공단 데이터에서 찾아 근거 번호와 함께 첨부합니다. 사진이 있는 사례는 [사진]으로 표시됩니다.</purpose>
            <returns>업종·발생형태·작업 설명과 유사한 사고사례 최대 3건. 각 줄은 #번호, 한 줄 요약, 유사도, 출처 상태.</returns>
            <prerequisites>analyzeHazards로 발생형태를 도출한 뒤, 작업 설명을 query로 넘겨 호출하세요.</prerequisites>
            </tool-description>
            """)
    public String searchCases(@ToolParam(description = "발생형태 코드 (FALL 추락 / CAUGHT 협착 / DROP 낙하 / STRUCK 부딪힘 / FIRE 화재 / PPE 보호구)") String accidentType,
                              @ToolParam(description = "업종 필터 (예: 제조업, 건설업, 조선업). 모르면 생략", required = false) String business,
                              @ToolParam(description = "작업 설명 자연어 (예: 사다리 위에서 천장 도장). 유사도 검색에 쓰입니다", required = false) String query,
                              ToolContext toolContext) {
        return ToolCallTracker.execute("searchCases", Map.of("accidentType", String.valueOf(accidentType), "business", String.valueOf(business), "query", String.valueOf(query)),
                sseService, toolContext, () -> {
            AccidentType axis = parseAxis(accidentType);
            if (axis == null) return ToolResult.of("발생형태를 알 수 없습니다. FALL/CAUGHT/DROP/STRUCK/FIRE/PPE 중 하나로 지정하세요.");
            String q = (query == null || query.isBlank()) ? axis.getLabel() : query.strip();
            String cid = AgentContextKeys.conversationId(toolContext);
            List<Evidence> found = search.search(SearchRequest.cases(q, axis, blankToNull(business), 3));
            if (found.isEmpty() && business != null) found = search.search(SearchRequest.cases(q, axis, null, 3));
            if (found.isEmpty()) return ToolResult.of("해당 발생형태의 유사 사고사례를 찾지 못했습니다.");
            List<Evidence> numbered = ledger.registerAll(cid, found);
            StringBuilder sb = new StringBuilder("유사 사고사례:\n");
            numbered.forEach(e -> sb.append(line(e)).append('\n'));
            crawler.checkLatest("FATALITY").ifPresent(li -> sb.append("(공단 사고사망 게시판 최신 등재 ").append(li.totalCount()).append("건 기준, ")
                    .append(li.checkedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))).append(" 확인)\n"));
            return ToolResult.of(sb.toString());
        });
    }

    @Tool(description = """
            <tool-description>
            <purpose>취급 화학물질의 유해성·폭발화재 위험·취급저장 주의사항·노출기준과 보호구를 조회합니다. 가능하면 공단 MSDS를 실시간으로 조회하고, 안 되면 캐시를 씁니다.</purpose>
            <returns>MSDS 4개 항목의 핵심 내용, GHS 그림문자 코드, 법정 노출기준(TWA/STEL), 근거 번호(#n).</returns>
            <prerequisites>작업자에게 제품명을 먼저 확인하세요. 제품명을 모르면 이 도구를 호출하지 말고 물어보세요.</prerequisites>
            </tool-description>
            """)
    public String getMsds(@ToolParam(description = "제품명 또는 물질명 (예: 톨루엔, 유성페인트, 시너)") String productName, ToolContext toolContext) {
        return ToolCallTracker.execute("getMsds", Map.of("productName", String.valueOf(productName)), sseService, toolContext, () -> {
            if (productName == null || productName.isBlank()) return ToolResult.of("제품명이 없습니다. 작업자에게 사용 제품명을 물어보세요.");
            Fetched<MsdsLiveClient.MsdsBundle> f = msds.resolve(productName.strip());
            if (f.isEmpty()) {
                return ToolResult.of("'" + productName + "'의 MSDS를 찾지 못했습니다(" + f.note() + "). 주요 성분명으로 다시 시도하거나 제품 용기 라벨을 확인하도록 안내하세요.");
            }
            MsdsLiveClient.MsdsBundle b = f.value();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chemId", b.chemId()); meta.put("casNo", b.casNo()); meta.put("pictograms", b.pictograms());
            meta.put("sections", b.sections());
            meta.put("sourceUrl", "https://msds.kosha.or.kr/MSDSInfo/kcic/msdsdetail.do?chem_id=" + b.chemId());
            Evidence card = new Evidence(0, EvidenceKind.MSDS, null, "MSDS:" + b.chemId(), b.chemNameKor() + " MSDS (공단)",
                    String.join(" / ", b.sections().getOrDefault("02", List.of())), (String) meta.get("sourceUrl"), null, null,
                    f.origin(), 1.0, f.fetchedAt(), meta);
            Evidence numbered = ledger.registerAll(AgentContextKeys.conversationId(toolContext), List.of(card)).get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("#").append(numbered.no()).append(" [").append(b.chemNameKor()).append("] MSDS (")
              .append(f.origin() == Origin.LIVE ? "실시간 조회" : "캐시").append(")\n");
            if (b.pictograms() != null) sb.append("그림문자: ").append(b.pictograms()).append('\n');
            b.sections().forEach((sec, lines) -> {
                if (lines.isEmpty()) return;
                sb.append('\n').append(sectionName(sec)).append('\n');
                lines.stream().limit(8).forEach(l -> sb.append("  - ").append(l).append('\n'));
            });
            return ToolResult.of(sb.toString());
        });
    }

    /** 근거 한 줄: #n [사진] 제목 (유사도 84%, 캐시 09-21) */
    static String line(Evidence e) {
        StringBuilder sb = new StringBuilder("#").append(e.no()).append(' ');
        if (e.kind().isCase() && e.mediaUrl() != null) sb.append("[사진] ");
        sb.append(e.title());
        sb.append(" (");
        if (e.kind() != EvidenceKind.LAW && e.kind() != EvidenceKind.MSDS) sb.append("유사도 ").append(Math.round(e.score() * 100)).append("%, ");
        sb.append(switch (e.origin()) { case LIVE -> "실시간 조회"; case CACHE -> "캐시 " + e.fetchedAt().format(MD); case KEYWORD_FALLBACK -> "키워드 검색"; });
        return sb.append(')').toString();
    }

    private List<Evidence> lawEvidence(LawCitationTable.Citation c) {
        Fetched<List<LawArticle>> f = laws.get(c.lawName(), c.articleNo(), c.articleSub());
        if (f.isEmpty()) return List.of();
        LawArticle first = f.value().get(0);
        String text = f.value().stream().map(LawArticle::getText).collect(Collectors.joining("\n"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lawName", c.lawName()); meta.put("articleNo", c.articleNo()); meta.put("articleSub", c.articleSub());
        meta.put("why", c.why()); meta.put("effectiveOn", first.getEffectiveOn() == null ? null : first.getEffectiveOn().toString());
        meta.put("sourceUrl", first.getSourceUrl()); meta.put("fullText", text);
        String snippet = text.length() > 200 ? text.substring(0, 200) + "…" : text;
        return List.of(new Evidence(0, EvidenceKind.LAW, first.getId(), first.getLawId() + ":" + c.articleNo() + ":" + c.articleSub(),
                first.citation().replaceAll(" [①-⑳]$", "") + " — " + c.why(), snippet, first.getSourceUrl(), null, null, f.origin(), 1.0, f.fetchedAt(), meta));
    }

    private static String sectionName(String s) {
        return switch (s) { case "02" -> "유해성·위험성"; case "05" -> "폭발·화재시 대처방법"; case "07" -> "취급 및 저장방법"; case "08" -> "노출방지 및 개인보호구"; default -> "항목 " + s; };
    }
```
`deriveAxes`, `parseAxis`, `nvl`, `blankToNull`은 기존 그대로. `findGuides`·`BRIEFING_SECTIONS`·`msdsCacheRepository`·`koshaGuideRepository`·`publicCaseRepository`·`msdsResolver` 필드는 제거한다. `AgentContextKeys`에 `static String conversationId(ToolContext ctx)` 게터가 이미 있는지 확인하고 없으면 추가한다(`ctx.getContext().get(CONVERSATION_ID)`를 문자열로).

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.ai.tools.HazardAnalysisToolsTest`
Expected: PASS 4건

```bash
git add backend/src/main/java/io/saife/ai backend/src/main/java/io/saife/publicapi/service/PublicApiCrawler.java backend/src/test/java/io/saife/ai/tools
git commit -m "feat(tools): searchCases 벡터 검색·query, analyzeHazards 지침·조문 근거, getMsds 라이브 — 근거 번호 부여"
```

---

### Task 4: `createWorkPlan` 근거 저장 + 작업계획서 상세·서식에 참고 자료

**Files:**
- Modify: `backend/src/main/java/io/saife/ai/tools/WorkPlanTools.java` (`createWorkPlan` 성공 분기)
- Modify: `backend/src/main/java/io/saife/workplan/dto/WorkPlanDtos.java` (`WorkPlanDetail.evidence: List<Evidence>`)
- Modify: `backend/src/main/java/io/saife/workplan/service/WorkPlanService.java` (`detail`에 근거 조회)
- Modify: `backend/src/main/java/io/saife/form/service/FormDataService.java`, `templates/form/work-plan.html` ("참고 자료" 목록)
- Modify: `backend/src/main/java/io/saife/workplan/service/BriefingComposer.java` (`appendSimilarCases`가 `EvidenceSearchService`를 쓰고 `#n`을 병기)
- Test: `backend/src/test/java/io/saife/workplan/WorkPlanEvidenceIT.java`

**Interfaces:**
- Produces: `WorkPlanEvidenceRepository` 사용, `WorkPlanDtos.WorkPlanDetail`에 `List<Evidence> evidence` 필드(맨 뒤), 서식 컨텍스트 변수 `references: List<Map<String,String>>(no, title, sourceUrl, fetchedAt)`.

- [ ] **Step 1: 실패하는 통합 테스트**

```java
package io.saife.workplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.WorkPlanEvidence;
import io.saife.evidence.live.Origin;
import io.saife.evidence.repository.WorkPlanEvidenceRepository;
import io.saife.workplan.domain.WorkPlan;
import io.saife.workplan.repository.WorkPlanRepository;
import io.saife.workplan.service.WorkPlanService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkPlanEvidenceIT {
    @Autowired WorkPlanRepository plans;
    @Autowired WorkPlanEvidenceRepository evidences;
    @Autowired WorkPlanService service;
    @Autowired ObjectMapper om;

    @Test
    void 상세_응답에_근거가_번호순으로_붙는다() throws Exception {
        WorkPlan p = plans.save(WorkPlan.builder().siteId(1L).workName("테스트").workDate(LocalDate.now()).build());
        Evidence e2 = new Evidence(2, EvidenceKind.LAW, 1L, "L:36:0", "산업안전보건법 제36조", "s", "https://law", null, null, Origin.CACHE, 1, OffsetDateTime.now(), Map.of());
        Evidence e1 = new Evidence(1, EvidenceKind.CASE_FATALITY, 1L, "F:1", "사례", "s", null, "/api/media/case/1/photo", null, Origin.CACHE, 0.8, OffsetDateTime.now(), Map.of());
        evidences.save(WorkPlanEvidence.builder().workPlanId(p.getId()).evidenceNo(2).payload(om.writeValueAsString(e2)).build());
        evidences.save(WorkPlanEvidence.builder().workPlanId(p.getId()).evidenceNo(1).payload(om.writeValueAsString(e1)).build());
        var detail = service.detail(p.getId());
        assertThat(detail.evidence()).extracting(Evidence::no).containsExactly(1, 2);
        assertThat(detail.evidence().get(0).mediaUrl()).isEqualTo("/api/media/case/1/photo");
    }
}
```
(`WorkPlan.builder()`의 필수 필드는 실제 엔티티에 맞춘다 — 빌더에 `status`·`workPlace` 등 NOT NULL 컬럼이 있으면 채운다.)

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`WorkPlanTools.createWorkPlan` 성공 분기(브리핑 생성·`submit()` 뒤)에:
```java
            // 이 턴까지 원장에 쌓인 근거를 계획서에 붙인다 — 브리핑 "참고 자료"와 법정 서식 하단 출처 목록이 이걸 읽는다
            String cid = AgentContextKeys.conversationId(toolContext);
            for (Evidence e : evidenceLedger.all(cid)) {
                try {
                    workPlanEvidenceRepository.save(WorkPlanEvidence.builder().workPlanId(plan.getId()).evidenceNo(e.no())
                            .payload(objectMapper.writeValueAsString(e)).build());
                } catch (Exception ex) { log.warn("[WORKPLAN] 근거 저장 실패 no={}: {}", e.no(), ex.getMessage()); }
            }
```
(필드 `EvidenceLedger evidenceLedger`, `WorkPlanEvidenceRepository workPlanEvidenceRepository`, `ObjectMapper objectMapper` 추가.)

`WorkPlanDtos.WorkPlanDetail` 레코드 맨 뒤에 `List<Evidence> evidence` 추가. `WorkPlanService.detail`에서:
```java
        List<Evidence> evidence = workPlanEvidenceRepository.findByWorkPlanIdOrderByEvidenceNo(id).stream()
                .map(r -> { try { return objectMapper.readValue(r.getPayload(), Evidence.class); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull).toList();
```
를 응답에 넣는다.

`BriefingComposer.appendSimilarCases`를 `EvidenceSearchService`로 교체:
```java
    private void appendSimilarCases(StringBuilder sb, WorkPlan plan, List<Hazard> hazards) {
        Set<AccidentType> axes = new LinkedHashSet<>();
        hazards.forEach(h -> axes.add(h.getAccidentType()));
        if (axes.isEmpty()) return;
        List<Evidence> found = new ArrayList<>();
        for (AccidentType axis : axes) {
            found.addAll(evidenceSearchService.search(SearchRequest.cases(plan.getWorkName() + " " + nvl(plan.getWorkPlace(), ""), axis, "제조업", 1).withoutRerank()));
            if (found.size() >= 2) break;
        }
        if (found.isEmpty()) return;
        sb.append("\n[유사 사고사례]\n");
        found.stream().limit(2).forEach(e -> sb.append("- ").append(e.mediaUrl() != null ? "[사진] " : "").append(e.title()).append('\n'));
    }
```
(브리핑은 원장 밖에서 만들어지므로 번호를 붙이지 않는다. 카드는 `work_plan_evidence`에서 온다.)

`FormDataService`의 작업계획서 뷰에 `references` 추가(제목·sourceUrl·fetchedAt(KST) 문자열), `work-plan.html` 하단:
```html
<section th:if="${!references.isEmpty()}" class="references">
  <h3>참고 자료</h3>
  <ol>
    <li th:each="r : ${references}">
      <span th:text="'#' + ${r.no} + ' ' + ${r.title}"></span>
      <a th:if="${r.sourceUrl != null}" th:href="${r.sourceUrl}" th:text="${r.sourceUrl}" target="_blank" rel="noreferrer"></a>
      <span class="muted" th:text="' (조회 ' + ${r.fetchedAt} + ')'"></span>
    </li>
  </ol>
</section>
```

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests io.saife.workplan.WorkPlanEvidenceIT`
Expected: PASS

```bash
git add backend/src/main/java/io/saife/ai/tools/WorkPlanTools.java backend/src/main/java/io/saife/workplan backend/src/main/java/io/saife/form backend/src/main/resources/templates/form/work-plan.html backend/src/test/java/io/saife/workplan
git commit -m "feat(workplan): 계획서에 근거 저장 — 상세 응답·법정 서식 참고 자료, 브리핑 유사 사례를 벡터 검색으로"
```

---

### Task 5: UC2 — 유사 사례·조문 근거와 조사표 인용

**Files:**
- Modify: `backend/src/main/java/io/saife/incident/dto/IncidentDtos.java` (`RecallView.similarCases`, `RegisterResponse.evidence`)
- Modify: `backend/src/main/java/io/saife/incident/service/IncidentService.java`
- Modify: `backend/src/main/java/io/saife/incident/service/IncidentReportDrafter.java`
- Test: `backend/src/test/java/io/saife/incident/service/IncidentServiceEvidenceTest.java`

**Interfaces:**
- Produces: `RecallView(..., List<Evidence> similarCases)`, `RegisterResponse(..., List<Evidence> evidence)`(유사 사례 3 + 사고 후 조문 2, 번호 1부터 응답 내 유일), `IncidentReportDrafter.draft(incident, recall, List<Evidence> evidence)` — 프롬프트에 `#n` 목록을 넣고 결과에 `CitationSanitizer.sanitize` 적용.

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.live.Fetched;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentServiceEvidenceTest {
    @Test
    void 검색_실패는_등록을_막지_않는다() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        when(search.search(any())).thenThrow(new RuntimeException("db down"));
        LawArticleService laws = mock(LawArticleService.class);
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(Fetched.empty("x"));
        IncidentEvidenceCollector collector = new IncidentEvidenceCollector(search, laws);
        var out = collector.collect("스크류에 끼임", io.saife.core.domain.AccidentType.CAUGHT);
        assertThat(out.similarCases()).isEmpty();
        assertThat(out.all()).isEmpty();
    }

    @Test
    void 번호는_1부터_유일하게() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        io.saife.evidence.Evidence c = new io.saife.evidence.Evidence(0, io.saife.evidence.EvidenceKind.CASE_FATALITY, 1L, "F:1", "사례", "s", null, null, null, io.saife.evidence.live.Origin.CACHE, 0.7, java.time.OffsetDateTime.now(), java.util.Map.of());
        when(search.search(any())).thenReturn(List.of(c));
        LawArticleService laws = mock(LawArticleService.class);
        io.saife.evidence.domain.LawArticle a = io.saife.evidence.domain.LawArticle.builder().id(3L).lawId("L").lawName("산업안전보건법 시행규칙").articleNo(73).articleSub(0).paragraphNo(1).title("산업재해 발생 보고").text("① …").build();
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(new Fetched<>(List.of(a), io.saife.evidence.live.Origin.CACHE, java.time.OffsetDateTime.now(), null));
        var out = new IncidentEvidenceCollector(search, laws).collect("끼임", io.saife.core.domain.AccidentType.CAUGHT);
        assertThat(out.all()).extracting(io.saife.evidence.Evidence::no).containsExactly(1, 2, 3);
        assertThat(out.similarCases()).hasSize(1);
    }
}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`incident/service/IncidentEvidenceCollector.java`(새 클래스, 등록 트랜잭션 밖에서 안전하게 실패):
```java
package io.saife.incident.service;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.service.LawCitationTable;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 사고 등록 응답용 근거: 유사 사례 3(사진 우선) + 사고 후 조문 2. 실패는 빈 리스트 — 등록을 막지 않는다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEvidenceCollector {
    public record Collected(List<Evidence> similarCases, List<Evidence> laws) {
        public List<Evidence> all() { List<Evidence> o = new ArrayList<>(similarCases); o.addAll(laws); return o; }
    }

    private final EvidenceSearchService search;
    private final LawArticleService lawArticleService;

    public Collected collect(String description, AccidentType axis) {
        List<Evidence> cases = new ArrayList<>();
        try {
            String q = (description == null || description.isBlank()) ? (axis == null ? "사고" : axis.getLabel()) : description;
            cases.addAll(search.search(SearchRequest.cases(q, axis, "제조업", 3)));
        } catch (Exception e) { log.warn("[UC2] 유사 사례 검색 실패: {}", e.getMessage()); }
        List<Evidence> laws = new ArrayList<>();
        for (LawCitationTable.Citation c : LawCitationTable.afterIncident()) {
            try {
                Fetched<List<LawArticle>> f = lawArticleService.get(c.lawName(), c.articleNo(), c.articleSub());
                if (f.isEmpty()) continue;
                LawArticle first = f.value().get(0);
                String text = f.value().stream().map(LawArticle::getText).collect(Collectors.joining("\n"));
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("lawName", c.lawName()); meta.put("articleNo", c.articleNo()); meta.put("why", c.why()); meta.put("sourceUrl", first.getSourceUrl()); meta.put("fullText", text);
                laws.add(new Evidence(0, EvidenceKind.LAW, first.getId(), first.getLawId() + ":" + c.articleNo() + ":" + c.articleSub(),
                        first.citation().replaceAll(" [①-⑳]$", "") + " — " + c.why(), text.length() > 200 ? text.substring(0, 200) + "…" : text,
                        first.getSourceUrl(), null, null, f.origin(), 1.0, f.fetchedAt(), meta));
            } catch (Exception e) { log.warn("[UC2] 조문 조회 실패 {}: {}", c, e.getMessage()); }
        }
        int no = 1;
        List<Evidence> nc = new ArrayList<>(); for (Evidence e : cases) nc.add(e.withNo(no++));
        List<Evidence> nl = new ArrayList<>(); for (Evidence e : laws) nl.add(e.withNo(no++));
        return new Collected(nc, nl);
    }
}
```

`IncidentDtos`: `RecallView`에 `List<Evidence> similarCases` 추가(맨 뒤, `from(recall, similarCases)` 오버로드), `RegisterResponse`에 `List<Evidence> evidence` 추가(맨 뒤). `IncidentService.register`에서 소환 뒤:
```java
        IncidentEvidenceCollector.Collected collected = evidenceCollector.collect(request.description(), request.accidentType());
        IncidentReportDrafter.Draft draft = drafter.draft(incident, recall, collected.all());
```
응답 생성 시 `IncidentDtos.RecallView.from(recall, collected.similarCases())`, 마지막 인자 `collected.all()`. `detail()`도 같은 수집을 한다.

`IncidentReportDrafter.draft(incident, recall, List<Evidence> evidence)`: `buildUserPrompt` 끝에
```
[근거 목록 — 인용은 [#n] 형식으로]
#1 [사례] 제목 (요약)
#4 [조문] 산업안전보건법 시행규칙 제73조 — 산업재해 발생 보고
```
를 붙이고, SYSTEM에 "재발방지 대책 문장 끝에 근거 번호를 [#n]으로 붙이세요. 목록에 없는 번호는 쓰지 마세요."를 추가. 파싱 후 `cause`·`prevention` 모두 `CitationSanitizer.sanitize(text, evidence번호 집합)`.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test --tests "io.saife.incident.*"`
Expected: PASS

```bash
git add backend/src/main/java/io/saife/incident backend/src/test/java/io/saife/incident
git commit -m "feat(incident): 사고 등록 응답에 유사 사례(사진)·조문 근거, 조사표 초안 [#n] 인용과 후처리"
```

---

### Task 6: UC1 — 후보별 근거 3건

**Files:**
- Modify: `backend/src/main/java/io/saife/ai/vision/VisionAssessmentService.java` (`Candidate.evidence`, `persist`·`result`에서 수집)
- Test: `backend/src/test/java/io/saife/ai/vision/CandidateEvidenceCollectorTest.java`

**Interfaces:**
- Produces: `Candidate(..., List<Evidence> evidence)`(맨 뒤), `CandidateEvidenceCollector.forCandidate(AccidentType axis, String missingControl) → List<Evidence>`(GUIDE 1 + LAW 1 + 사진 있는 CASE 1, 번호 1~3, `rerank=false`).

- [ ] **Step 1: 실패하는 테스트**

```java
package io.saife.ai.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.live.*;
import io.saife.evidence.search.*;
import io.saife.evidence.service.LawArticleService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CandidateEvidenceCollectorTest {
    private Evidence ev(EvidenceKind k, boolean img) {
        Map<String, Object> m = new HashMap<>(); m.put("hasImage", img);
        return new Evidence(0, k, 1L, k + ":" + img, "t", "s", null, img ? "/api/media/case/1/photo" : null, null, Origin.CACHE, 0.6, OffsetDateTime.now(), m);
    }

    @Test
    void 지침_조문_사진사례_순으로_최대_3건_리랭크_없이() {
        EvidenceSearchService search = mock(EvidenceSearchService.class);
        when(search.search(argThat(r -> r != null && r.kinds().contains(EvidenceKind.GUIDE)))).thenReturn(List.of(ev(EvidenceKind.GUIDE, false)));
        when(search.search(argThat(r -> r != null && r.kinds().contains(EvidenceKind.CASE_FATALITY)))).thenReturn(List.of(ev(EvidenceKind.CASE_FATALITY, false), ev(EvidenceKind.CASE_FATALITY, true)));
        LawArticleService laws = mock(LawArticleService.class);
        io.saife.evidence.domain.LawArticle a = io.saife.evidence.domain.LawArticle.builder().id(1L).lawId("L").lawName("산업안전보건기준에 관한 규칙").articleNo(42).articleSub(0).paragraphNo(1).text("①").build();
        when(laws.get(anyString(), anyInt(), anyInt())).thenReturn(new Fetched<>(List.of(a), Origin.CACHE, OffsetDateTime.now(), null));
        List<Evidence> out = new CandidateEvidenceCollector(search, laws).forCandidate(AccidentType.FALL, "안전난간 미설치");
        assertThat(out).extracting(Evidence::kind).containsExactly(EvidenceKind.GUIDE, EvidenceKind.LAW, EvidenceKind.CASE_FATALITY);
        assertThat(out).extracting(Evidence::no).containsExactly(1, 2, 3);
        assertThat(out.get(2).mediaUrl()).isNotNull();   // 사진 있는 사례 우선
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search, times(2)).search(cap.capture());
        assertThat(cap.getAllValues()).allMatch(r -> !r.rerank());
    }
}
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`ai/vision/CandidateEvidenceCollector.java`:
```java
package io.saife.ai.vision;

import io.saife.core.domain.AccidentType;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.domain.LawArticle;
import io.saife.evidence.live.Fetched;
import io.saife.evidence.search.EvidenceSearchService;
import io.saife.evidence.search.SearchRequest;
import io.saife.evidence.service.LawArticleService;
import io.saife.evidence.service.LawCitationTable;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 사진 판독 후보 1건에 붙는 근거 3장: 지침 1, 조문 1, 사진 있는 사례 1. 모델 호출 없음, 리랭크 없음 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateEvidenceCollector {
    private final EvidenceSearchService search;
    private final LawArticleService laws;

    public List<Evidence> forCandidate(AccidentType axis, String missingControl) {
        List<Evidence> out = new ArrayList<>();
        String q = (missingControl == null ? "" : missingControl) + " " + axis.getLabel();
        try { search.search(SearchRequest.guides(q, 2).withoutRerank()).stream().findFirst().ifPresent(out::add); } catch (Exception e) { log.warn("[UC1] 지침 검색 실패: {}", e.getMessage()); }
        try {
            LawCitationTable.Citation c = LawCitationTable.forAxis(axis).get(0);
            Fetched<List<LawArticle>> f = laws.get(c.lawName(), c.articleNo(), c.articleSub());
            if (!f.isEmpty()) {
                LawArticle first = f.value().get(0);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("lawName", c.lawName()); meta.put("articleNo", c.articleNo()); meta.put("why", c.why()); meta.put("sourceUrl", first.getSourceUrl());
                out.add(new Evidence(0, EvidenceKind.LAW, first.getId(), first.getLawId() + ":" + c.articleNo() + ":" + c.articleSub(),
                        first.citation().replaceAll(" [①-⑳]$", "") + " — " + c.why(), first.getText().length() > 200 ? first.getText().substring(0, 200) + "…" : first.getText(),
                        first.getSourceUrl(), null, null, f.origin(), 1.0, f.fetchedAt(), meta));
            }
        } catch (Exception e) { log.warn("[UC1] 조문 조회 실패: {}", e.getMessage()); }
        try {
            List<Evidence> cases = search.search(SearchRequest.cases(q, axis, "제조업", 3).withoutRerank());
            cases.stream().filter(c -> c.mediaUrl() != null).findFirst().or(() -> cases.stream().findFirst()).ifPresent(out::add);
        } catch (Exception e) { log.warn("[UC1] 사례 검색 실패: {}", e.getMessage()); }
        List<Evidence> numbered = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) numbered.add(out.get(i).withNo(i + 1));
        return numbered;
    }
}
```
`VisionAssessmentService.Candidate`에 `List<Evidence> evidence` 필드(맨 뒤) 추가. `persist`에서 후보를 만들 때와 `result()`에서 후보를 복원할 때 `collector.forCandidate(axis, missingControl)`를 부른다. `decideCandidate` 반환에도 같은 수집(빈 리스트 허용).

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd backend && ./gradlew test`
Expected: 전체 PASS

```bash
git add backend/src/main/java/io/saife/ai/vision backend/src/test/java/io/saife/ai/vision
git commit -m "feat(vision): 사진 판독 후보마다 지침·조문·사진 사례 근거 3건"
```

---

## Self-Review

- 스펙 §7.1(도구 3종 재배선, createWorkPlan 저장), §7.2(원장), §7.3(후처리), §7.4(프롬프트), §7.5(`ai.evidence`), §7.6(UC2), §7.7(UC1), §6.4 `checkLatest`, 서식 참고 자료(§8.2) 커버. `ai.recall`은 연결성 계획(C) 소관.
- 타입 일관성: `Evidence.withNo`, `EvidenceLedger.registerAll/all/newInTurn/knownNumbers/endTurn/summaryLine`, `SearchRequest.cases/guides/withoutRerank`, `Fetched.isEmpty/note/origin/fetchedAt`가 A 계획 정의와 일치.
- Review Focus 5건 모두 테스트 존재(Task 1·3·5).
- 데모 스크립트(`DemoConversationScript`)는 도구를 실제로 호출하므로 원장·이벤트가 자동으로 붙는다. 단 스크립트 문장에 `[#n]`을 넣으려면 `respond()` 반환 전에 `ledger.all(cid)`의 번호를 참조해 "유사 사례 [#1]"처럼 붙이는 한 줄을 추가한다(Task 3 커밋에 포함).
