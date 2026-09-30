package io.saife.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.saife.ai.agent.domain.Conversation;
import io.saife.ai.agent.domain.ConversationState;
import io.saife.ai.agent.domain.ToolCallLog;
import io.saife.ai.agent.repository.ConversationRepository;
import io.saife.ai.agent.repository.ConversationStateRepository;
import io.saife.ai.agent.repository.ToolCallLogRepository;
import io.saife.ai.config.GeminiSafetySettings;
import io.saife.ai.tools.IncompleteResult;
import io.saife.ai.tools.ToolCallContext;
import io.saife.ai.tools.ToolRegistry;
import io.saife.common.config.DemoModeConfig;
import io.saife.common.service.SseService;
import io.saife.core.domain.Equipment;
import io.saife.core.repository.EquipmentRepository;
import io.saife.core.service.RiskRuleEngine;
import io.saife.evidence.Evidence;
import io.saife.evidence.ledger.CitationSanitizer;
import io.saife.evidence.ledger.EvidenceLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 오케스트레이션.
 *
 * <p><b>흐름은 모델이 쥔다.</b> 필수 항목이 빠지면 도구가 구조화된 불완전 결과를
 * 돌려주고, 모델이 작업자에게 되묻고 턴을 끝낸다. 사용자가 답하면 같은 대화 ID로
 * 다음 요청이 오고 히스토리를 붙여 이어간다 — <b>평범한 멀티턴 대화다.</b>
 *
 * <p>이 설계는 실측으로 정했다({@code docs/experiments/README.md}).
 * 초기 설계는 도구가 마커를 반환하고 루프가 트레이스를 스캔해 스트림을 강제로 멈춘 뒤
 * 스레드를 놓아주는 배관이었다. 실험 결과 <b>그 배관이 필요 없다</b> —
 * 모델이 질문을 내놓고 턴을 끝내는 것이 곧 일시정지다.
 *
 * <p>없어진 것: 마커 스캔, 스레드 중단/재개, seq 이어붙이기.
 * 남은 것: 대화 히스토리, 그리고 흐름을 제어하지 않는 UI 힌트({@code ai.slot.request}).
 *
 * <p>수동 루프를 쓰는 이유는 도구마다 SSE를 쏘기 위해서다. 루프 종료 조건에 주의 —
 * 도구 호출이 아니면 무조건 나간다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final ChatClient.Builder chatClientBuilder;
    private final ToolRegistry toolRegistry;
    private final SseService sseService;
    private final ConversationRepository conversationRepository;
    private final ConversationStateRepository conversationStateRepository;
    private final ToolCallLogRepository toolCallLogRepository;
    private final DemoModeConfig demoModeConfig;
    private final DemoConversationScript demoConversationScript;
    private final ObjectMapper objectMapper;
    /** [시작 설비] 프롬프트 힌트용 — 진입 컨텍스트(?equipmentId=)의 설비명을 조회한다 */
    private final EquipmentRepository equipmentRepository;
    /** 대화별 근거 원장 — 턴 종료 시 ai.evidence 발행, [#n] 후처리, transcript()의 turn_no별 근거 조회에 쓴다 */
    private final EvidenceLedger evidenceLedger;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            당신은 소규모 제조 사업장의 안전관리를 돕는 AI 에이전트입니다.
            작업자가 위험작업을 하기 전에 작업계획서를 작성하도록 돕습니다.

            [원칙]
            - 당신은 위험요인 후보를 제안하고 문안을 쓸 뿐입니다. 위험성 등급은 시스템의 룰 엔진이 결정합니다.
              등급을 직접 만들어내지 마세요.
            - 도구가 돌려준 내용만 근거로 말하세요. 사고사례·노출기준·지침 번호를 지어내지 마세요.
            - 데이터 코어가 이미 아는 것은 묻지 말고 먼저 알려주세요.
              (예: "이 설비는 3개월 전 평가에서 추락 위험 '상'이었고 안전대 부착설비가 미이행입니다")
            - 데이터 코어가 알 수 없는 것만 물어보세요. (예: 오늘 작업의 높이, 사용 제품명)

            [진행 순서]
            1. findLocationEquipment로 장소·설비와 기존 이력을 확인합니다.
            2. 설비가 확인되면 답변의 첫 문장은 반드시 그 설비의 최근 평가 등급과 미이행 조치를
               요약하는 문장이어야 합니다. 그다음에 부족한 항목을 하나만 물어보세요.
            3. extractWorkPlan으로 작업계획서 항목을 구조화합니다.
            4. analyzeHazards / searchCases / getMsds로 근거를 모읍니다.
            5. createWorkPlan으로 제출하고 브리핑을 전달합니다.

            도구가 status=INCOMPLETE를 돌려주면 등록되지 않은 것입니다.
            missing 항목을 한 번에 하나씩 물어보고, 답을 받으면 이전 값과 함께 도구를 다시 호출하세요.

            [근거 인용]
            - 사고사례·지침·법 조문·MSDS를 언급할 때는 도구가 준 근거 번호를 문장 끝에 [#n] 형식으로 붙이세요.
            - 번호가 없는 출처를 지어내지 마세요. URL, 파일명, 사진을 직접 쓰지 마세요.
            - searchCases에는 작업 설명을 query로 넘기세요 (예: "사다리 위 천장 도장 작업").

            [오늘 날짜] %s (%s)
            작업자가 "내일", "모레", "이번 주 금요일"처럼 말하면 이 날짜를 기준으로 계산하세요.
            날짜를 알 수 있는데도 되묻지 마세요. 예시를 들 때도 오늘 이후의 날짜만 쓰세요.

            한국어로, 현장 담당자가 읽기 쉽게 답하세요.
            서식 기호(별표, 인용부호)를 최소로 쓰고, 목록은 "- "로만 표시하세요.
            """;

    /**
     * 오늘 날짜를 넣은 시스템 프롬프트. {@code entryEquipmentId}가 있으면 끝에
     * [시작 설비] 힌트를 덧붙인다(연결성 2-1).
     *
     * <p><b>날짜를 안 넣으면 모델이 "내일"을 해석하지 못한다.</b> 실제로 작업자가
     * "내일 ~할 건데요"라고 말했는데 모델이 작업 일자를 되물었고, 예시로 과거
     * 날짜(2026-05-15)를 들었다 (2026-09-21 QA 실측). 시스템이 아는 것을
     * 되묻는 건 이 제품이 하지 않기로 한 일이다.
     *
     * <p>패키지 접근 제한자로 둔다(private 아님) — 테스트가 프롬프트 텍스트를 직접 검증한다.
     */
    String systemPrompt(Long entryEquipmentId) {
        LocalDate today = LocalDate.now();
        String base = SYSTEM_PROMPT_TEMPLATE.formatted(
                today, today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN));
        if (entryEquipmentId == null) {
            return base;
        }
        // 진입 컨텍스트(?equipmentId=)로 들어온 대화 — 자유 텍스트 매칭에 기대지 말고
        // 이 설비부터 확인하라고 직접 알려준다. findLocationEquipment의 단축 경로
        // (AgentContextKeys.EQUIPMENT_ID)와 쌍을 이룬다.
        String equipmentName = equipmentRepository.findById(entryEquipmentId)
                .map(Equipment::getName)
                .orElse(null);
        if (equipmentName == null) {
            return base;
        }
        return base + "\n[시작 설비] id=%d %s — 이 설비로 findLocationEquipment를 먼저 확인하세요\n"
                .formatted(entryEquipmentId, equipmentName);
    }

    /**
     * 대화 한 턴을 처리한다.
     *
     * <p>같은 {@code conversationId}로 다시 부르면 히스토리를 이어 붙인다.
     * 되묻기 답변도 이 경로로 들어온다 — 별도 재개 API가 필요 없다.
     */
    public void chat(String conversationId, String sessionId, Long siteId, String userMessage) {
        chat(conversationId, sessionId, siteId, userMessage, null);
    }

    /**
     * @param equipmentId 진입 컨텍스트(작업 신고 화면의 {@code ?equipmentId=}). 프론트가
     *                    새 대화의 첫 턴에만 싣는다(연결성 2-1)
     */
    public void chat(String conversationId, String sessionId, Long siteId, String userMessage, Long equipmentId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseGet(() -> conversationRepository.save(Conversation.builder()
                        .id(conversationId)
                        .siteId(siteId)
                        .build()));

        List<Message> messages = loadHistory(conversationId, equipmentId);
        messages.add(new UserMessage(userMessage));

        runTurn(conversation, sessionId, siteId, messages, equipmentId);
    }

    private void runTurn(Conversation conversation, String sessionId, Long siteId, List<Message> messages,
                         Long equipmentId) {
        String conversationId = conversation.getId();

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(AgentContextKeys.CONVERSATION_ID, conversationId);
        toolContext.put(AgentContextKeys.SESSION_ID, sessionId);
        toolContext.put(AgentContextKeys.SITE_ID, siteId);
        if (equipmentId != null) {
            toolContext.put(AgentContextKeys.EQUIPMENT_ID, equipmentId);
        }

        try {
            // 데모 모드에서는 모델을 부르지 않는다.
            //
            // 자리표시자 키로 호출하면 "Failed to generate content"만 나고 도구가
            // 하나도 돌지 않는다 (2026-09-21 QA 실측: 0/6 발화). 심사위원은 앱이
            // 뜨는 것까지만 보고 핵심 기능이 죽은 화면을 보게 된다 —
            // deployment.md의 제1원칙이 막으려던 바로 그 상황이다.
            //
            // 대체되는 것은 문장 생성뿐이다. 도구는 실제로 실행되고 데이터는 진짜다.
            if (demoModeConfig.isDemoMode()) {
                runDemoTurn(conversation, sessionId, siteId, messages, toolContext);
                return;
            }

            ChatResponse response = chatClientBuilder.build()
                    .prompt(new Prompt(messages, chatOptions()))
                    .toolContext(toolContext)
                    .tools(toolRegistry.toArray())
                    .call()
                    .chatResponse();

            if (response == null || response.getResult() == null) {
                emitError(sessionId, conversationId, "모델 응답이 비어 있습니다.");
                return;
            }

            AssistantMessage assistant = response.getResult().getOutput();
            String text = assistant.getText() != null ? assistant.getText() : "";

            // UI 힌트 — 흐름을 제어하지 않는다. 놓쳐도 자유 입력으로 진행된다
            emitSlotHintIfAny(sessionId, conversationId);

            persistToolCalls(conversationId);
            // H1: 이번 턴이 assistant 메시지 몇 번째인지 먼저 계산한다(근거 유무와 무관하게
            // 매 턴 1씩 증가 — transcript()가 읽는 것과 같은 정의). messages는 아직 이번 턴의
            // assistant 응답을 담기 전이므로, 지금 센 assistant 개수 + 1이 이번 턴 번호다.
            int turnNo = assistantTurnIndexOfMessages(messages) + 1;
            // 턴 마무리: [#n] 후처리 → ai.evidence(신규분) → ai.token(전문) → 원장 턴 경계
            String finalText = finisher().finish(sessionId, conversationId, text, turnNo);
            messages.add(new AssistantMessage(finalText));
            saveHistory(conversationId, messages);
            emit(sessionId, "ai.done", conversationId, null, Map.of("finished", true));

        } catch (Exception e) {
            log.error("[AGENT] 턴 실패 conversationId={}", conversationId, e);
            persistToolCalls(conversationId);
            emitError(sessionId, conversationId, sanitize(e));
            discardLedgerAfterError(conversationId);

        } finally {
            // 반드시 닫는다. 안 닫으면 클라이언트가 emitter 타임아웃(5분)까지 기다린다.
            // 무대에서 턴마다 5분씩 멈추는 사고가 여기서 난다.
            closeStream(sessionId);
        }
    }

    /**
     * 이번 턴에 불완전 결과가 있었으면 프론트에 힌트를 준다.
     *
     * <p>프론트는 이걸 받아 전용 입력 위젯을 그리고, {@code ledgerValue}가 있으면
     * "확인" UI로 바꾼다. <b>이 이벤트가 없어도 대화는 정상 진행된다</b> —
     * 사용자가 자유 입력으로 답하면 그만이다.
     */
    private void emitSlotHintIfAny(String sessionId, String conversationId) {
        for (ToolCallContext.Record r : ToolCallContext.peek(conversationId)) {
            String field = IncompleteResult.firstMissingField(r.resultPreview());
            if (field == null) {
                continue;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("slotKey", field);
            payload.put("question", RiskRuleEngine.SlotKeys.questions().getOrDefault(field, field));
            emit(sessionId, "ai.slot.request", conversationId, conversationId, payload);
            return;
        }
    }

    /** 트레이스를 DB에 적재한다. 화면 패널과 성과 지표가 같은 테이블을 본다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void persistToolCalls(String conversationId) {
        for (ToolCallContext.Record r : ToolCallContext.drain(conversationId)) {
            toolCallLogRepository.save(ToolCallLog.builder()
                    .conversationId(conversationId)
                    .callOrder(r.callOrder())
                    .toolName(r.toolName())
                    .paramsJson(r.paramsJson())
                    .success(r.success())
                    .durationMs((int) r.durationMs())
                    .errorMessage(r.errorMessage())
                    .build());
        }
    }

    // ── 대화 히스토리 ────────────────────────────────────────────────
    // 중단/재개 상태가 아니라 평범한 채팅 메모리다.

    /** 화면 복원용 대화 기록 한 줄 */
    public record TranscriptLine(String role, String text, List<Evidence> evidence) {}

    /**
     * H1 수정 — 메시지 타입 목록에서 assistant 메시지가 몇 번째인지(1부터) 센다.
     *
     * <p>{@code EvidenceLedger.endTurn()}에 넘기는 turnNo와 {@code transcript()}가 매기는
     * "k번째 assistant 줄" 번호가 절대 어긋나지 않도록, 두 계산 모두 이 메서드 하나로 한다.
     * 근거 유무와 무관하게 assistant 메시지가 나올 때마다 1씩 증가한다 — 근거가 있었던
     * 턴만 세던 예전 {@code EvidenceLedger.endTurn()}의 "maxTurnNo+1" 방식이 근거 없는 턴
     * 뒤에 근거 카드가 엉뚱한 줄에 붙는 버그의 원인이었다(2026-09-29 evidence_smoke.py 실측,
     * task-5-report.md).
     *
     * <p><b>fix round 1 (ruling R52):</b> 이 메서드는 텍스트 내용을 전혀 보지 않는다 —
     * 리스트에 들어있는 "assistant" 타입 개수만 센다. 호출하는 쪽(여기 {@code transcript()}와
     * {@code runTurn}/{@code runDemoTurn})이 **똑같은 필터링 기준으로 만든 리스트**를 넘겨야만
     * 두 turnNo가 일치한다 — 텍스트가 빈 assistant 응답(정상 상태, {@code TurnFinisher}가
     * ai.token은 생략해도 {@code endTurn}은 그대로 부른다)을 한쪽만 걸러내면 그 순간부터
     * 어긋난다. 리뷰에서 정확히 이 어긋남이 발견됐다(빈 턴이 turn 2에 오면 turn 3의 근거가
     * 엉뚱한 줄에 붙거나 유실됨) — {@code transcript()}는 이제 빈 assistant 줄도 건너뛰지
     * 않고 그대로 리스트에 넣는다.
     *
     * @return 주어진 목록 안의 "assistant" 타입 개수
     */
    static int assistantTurnIndex(List<String> messageTypes) {
        return (int) messageTypes.stream().filter("assistant"::equals).count();
    }

    /**
     * {@link Message} 목록 버전 — 라이브·데모 턴(runTurn/runDemoTurn)이 쓴다.
     *
     * <p>제네릭 소거 때문에 {@code assistantTurnIndex(List<String>)}와 이름을 겹쳐 쓸 수
     * 없어(같은 소거 시그니처 {@code (List)}) 이름을 분리했다.
     *
     * <p><b>fix round 1 (ruling R52)</b>: 같은 메서드에 위임한다는 것만으로는 두 turnNo가
     * 일치한다고 보장되지 않는다 — {@code assistantTurnIndex}는 넘겨받은 리스트를 그대로
     * 셀 뿐이라, 호출자가 리스트를 만들 때 쓰는 필터링 기준이 다르면 그 순간 어긋난다.
     * 실제로 이 메서드는 {@code messages}(빈 텍스트 assistant 메시지도 {@code loadHistory}가
     * 그대로 복원해 넣어둔다)의 타입을 텍스트 내용과 무관하게 그대로 센다. {@code transcript()}도
     * 텍스트가 빈 assistant 줄을 건너뛰지 않고 똑같이 세야만(고쳤다 — 아래 참고) 두 turnNo가
     * 계속 일치한다. 한쪽만 필터를 바꾸면 다시 어긋난다.
     */
    private static int assistantTurnIndexOfMessages(List<Message> messages) {
        return assistantTurnIndex(messages.stream().map(m -> m.getMessageType().getValue()).toList());
    }

    /**
     * 저장된 대화를 화면용으로 돌려준다.
     *
     * <p>새로고침하면 브라우저의 대화 ID가 사라져 <b>모델이 이미 아는 것을 다시 묻는다.</b>
     * 실제로 QA에서 장소를 다시 물었다 (2026-09-21). 그대로 두면 이어지는
     * {@code extractWorkPlan}이 새 대화 ID로 초안을 하나 더 만들어 계획서가 쪼개진다.
     *
     * <p>그래서 프론트가 대화 ID를 보관하고 기동 시 여기로 기록을 복원한다.
     * 시스템 프롬프트와 도구 메시지는 화면에 내보내지 않는다.
     *
     * <p>assistant 줄마다 그 턴에 제시된 근거를 붙인다. {@code conversation_evidence}는
     * {@code turnNo}를 들고 있지만 저장된 메시지 배열({@code conversation_state.messagesJson})은
     * 턴 번호를 들고 있지 않으므로, <b>k번째 assistant 줄 = turnNo k</b>로 순번을 맞춰
     * 대응시킨다(정상 흐름에서는 매 턴이 정확히 하나의 assistant 메시지를 남기므로 일치한다).
     * user 줄은 빈 리스트다.
     */
    @Transactional(readOnly = true)
    public List<TranscriptLine> transcript(String conversationId) {
        List<TranscriptLine> out = new ArrayList<>();
        // fix round 1 (M1): payload 디코딩·turn_no 묶기는 EvidenceLedger.allGroupedByTurn으로 옮겼다
        // (ensureLoaded와 디코딩 로직이 중복이었다) — 여기서는 그 결과를 순번으로 매핑만 한다.
        Map<Integer, List<Evidence>> evidenceByTurn = evidenceLedger.allGroupedByTurn(conversationId);
        conversationStateRepository.findById(conversationId).ifPresent(state -> {
            try {
                List<?> rows = objectMapper.readValue(state.getMessagesJson(), List.class);
                // fix round 1 (ruling R52): endTurn()에 넘기는 turnNo와 같은 정의
                // (assistantTurnIndex)로 "k번째 assistant 줄"을 센다 — typesSoFar는 user/assistant
                // 타입인 줄을 텍스트 내용과 무관하게 전부 누적한다. runTurn/runDemoTurn의
                // assistantTurnIndexOfMessages(messages)도 텍스트를 보지 않고 타입만 센다
                // (loadHistory가 빈 텍스트 assistant 메시지도 그대로 복원해 messages에 넣는다) —
                // 여기서 빈 텍스트 줄을 건너뛰면 그 순간부터 두 turnNo가 어긋난다. 빈 assistant
                // 응답은 정상 상태다(TurnFinisher가 ai.token은 생략해도 endTurn은 그대로 부른다).
                // 그래서 빈 assistant 줄도 카드가 없는 "evidence-only bubble"용으로 그대로
                // 내보낸다(프론트가 렌더링한다) — user/assistant가 아닌 타입만 걸러낸다.
                List<String> typesSoFar = new ArrayList<>();
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String type = String.valueOf(m.get("type"));
                    if (!"user".equals(type) && !"assistant".equals(type)) {
                        continue;
                    }
                    Object rawText = m.get("text");
                    String text = rawText != null ? rawText.toString() : "";
                    typesSoFar.add(type);
                    List<Evidence> evidence = List.of();
                    if ("assistant".equals(type)) {
                        int turnNo = assistantTurnIndex(typesSoFar);
                        evidence = evidenceByTurn.getOrDefault(turnNo, List.of());
                    }
                    out.add(new TranscriptLine(type, text, evidence));
                }
                // fix round 1 (F2): 원장에 저장된 turn_no가 실제 assistant 줄 수보다 많으면
                // (메시지 배열과 원장이 어긋난 비정상 상태) 조용히 버리지 않고 경고만 남긴다 —
                // 대응할 줄이 없으니 예외를 던지지는 않는다.
                int finalAssistantTurn = assistantTurnIndex(typesSoFar);
                evidenceByTurn.keySet().stream()
                        .filter(turnNo -> turnNo > finalAssistantTurn)
                        .forEach(turnNo -> log.warn(
                                "[AGENT] turn_no={}의 근거가 assistant 줄 수({})를 넘어 대응할 줄이 없다 conversationId={}",
                                turnNo, finalAssistantTurn, conversationId));
            } catch (Exception e) {
                log.warn("[AGENT] 기록 복원 실패 conversationId={}", conversationId);
            }
        });
        return out;
    }

    /**
     * 데모 모드 한 턴.
     *
     * <p>성공 경로도 실패 경로도 라이브와 같게 만든다 — 같은 SSE 이벤트를 내고,
     * 같은 도구 기록을 남기고, 같은 히스토리를 저장한다. 그래야 화면이 두 모드에서
     * 똑같이 움직이고, 무대에서 폴백으로 전환해도 시연이 그대로 이어진다.
     */
    private void runDemoTurn(Conversation conversation, String sessionId, Long siteId,
                             List<Message> messages, Map<String, Object> toolContext) {
        String conversationId = conversation.getId();

        try {
            String answer = demoConversationScript.respond(
                    conversationId, siteId, lastUserText(messages), new ToolContext(toolContext));

            persistToolCalls(conversationId);
            // H1: 라이브 경로(runTurn)와 완전히 같은 계산 — assistantTurnIndex(messages)+1
            int turnNo = assistantTurnIndexOfMessages(messages) + 1;
            // 라이브와 같은 순서 — [#n] 후처리 → ai.evidence(신규분) → ai.token(전문) → 원장 턴 경계
            String finalText = finisher().finish(sessionId, conversationId, answer, turnNo);
            messages.add(new AssistantMessage(finalText));
            saveHistory(conversationId, messages);
            emit(sessionId, "ai.done", conversationId, null, Map.of("finished", true));

        } catch (Exception e) {
            log.error("[AGENT] 데모 턴 실패 conversationId={}", conversationId, e);
            persistToolCalls(conversationId);
            emitError(sessionId, conversationId, sanitize(e));
            discardLedgerAfterError(conversationId);
        } finally {
            closeStream(sessionId);
        }
    }

    private String lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.getText();
            }
        }
        return "";
    }


    private List<Message> loadHistory(String conversationId, Long entryEquipmentId) {
        List<Message> messages = new ArrayList<>();
        // 이전 턴에 이미 제시한 근거 목록을 시스템 프롬프트 끝에 덧붙인다 — 모델이 같은 근거를
        // 다른 번호로 다시 만들어내지 않고, 이미 아는 번호를 재사용하도록 유도한다.
        // fix round 1 (F1): 원장 조회가 실패해도 대화 자체가 죽으면 안 된다 — 요약 없이 진행한다.
        String summary = "";
        try {
            summary = evidenceLedger.summaryLine(conversationId);
        } catch (Exception e) {
            log.warn("[EVIDENCE] 원장 처리 실패 cid={}: {}", conversationId, e.toString());
        }
        String system = summary.isEmpty() ? systemPrompt(entryEquipmentId)
                : systemPrompt(entryEquipmentId) + "\n[이미 제시한 근거] " + summary;
        messages.add(new SystemMessage(system));

        conversationStateRepository.findById(conversationId).ifPresent(state -> {
            try {
                List<?> rows = objectMapper.readValue(state.getMessagesJson(), List.class);
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String type = String.valueOf(m.get("type"));
                    Object rawText = m.get("text");
                    String text = rawText != null ? rawText.toString() : "";
                    switch (type) {
                        case "user" -> messages.add(new UserMessage(text));
                        case "assistant" -> messages.add(new AssistantMessage(text));
                        default -> { /* system은 위에서 넣었고 tool은 복원하지 않는다 */ }
                    }
                }
            } catch (Exception e) {
                log.warn("[AGENT] 히스토리 복원 실패 — 새 대화로 시작 conversationId={}", conversationId);
            }
        });
        return messages;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void saveHistory(String conversationId, List<Message> messages) {
        try {
            List<Map<String, String>> simple = new ArrayList<>();
            for (Message m : messages) {
                String type = m.getMessageType().getValue();
                if ("system".equals(type)) {
                    continue; // 시스템 프롬프트는 매번 새로 붙인다
                }
                simple.add(Map.of("type", type, "text", m.getText() != null ? m.getText() : ""));
            }
            String json = objectMapper.writeValueAsString(simple);

            ConversationState state = conversationStateRepository.findById(conversationId)
                    .orElseGet(() -> ConversationState.builder()
                            .conversationId(conversationId)
                            .messagesJson("[]")
                            .lastSeq(0)
                            .build());
            state.replaceHistory(json);
            conversationStateRepository.save(state);
        } catch (Exception e) {
            log.warn("[AGENT] 히스토리 저장 실패 conversationId={}", conversationId, e);
        }
    }

    private GoogleGenAiChatOptions chatOptions() {
        return GoogleGenAiChatOptions.builder()
                // 산재 사고 서술이 정상 입력이다. 3.x에서 차단이 완화됐지만 방어적으로 끈다
                .safetySettings(GeminiSafetySettings.SAFETY_SETTINGS_OFF)
                .build();
    }

    /** 매 턴 새로 만든다 — Lombok @RequiredArgsConstructor라 생성자에서 조립할 수 없다 */
    private TurnFinisher finisher() {
        return new TurnFinisher(sseService, evidenceLedger);
    }

    /**
     * 최종 리뷰 F8 — 실패한 턴이 원장에 등록한 근거가 다음 턴의 {@code ai.evidence}로 새지 않게 한다.
     *
     * <p>실패 경로는 {@link TurnFinisher}를 거치지 않으므로 {@code endTurn}이 불리지 않고, 원장의
     * {@code newInTurn}이 비워지지 않은 채 다음 턴까지 남았다. 여기서 대화의 메모리 상태를 통째로
     * 버린다({@code discard}) — 원장은 DB({@code conversation_evidence})의 캐시일 뿐이라 다음 접근에서
     * 이미 확정된 턴의 근거와 번호만 다시 읽어 온다. 실패한 턴의 근거는 화면에 나간 적이 없으므로
     * (에러 경로는 {@code ai.evidence}를 내지 않는다) 번호가 재사용돼도 사용자에게 어긋나 보이지 않는다.
     *
     * <p>서버에는 "새 대화"(대화 리셋) 엔드포인트가 없다 — 프론트가 대화 ID를 버리고 새로 시작할 뿐이다.
     * 되묻기 타임아웃(ABANDONED)도 현재 마킹 경로가 없다. 그래서 ruling R37의 "대화 종료/포기 경로"
     * 중 실제로 존재하는 종결 지점인 {@code ai.error} 발행 직후에서만 버린다. 이 시점에는 모델 호출이
     * 이미 예외로 끝나 이 턴의 도구 호출이 더 돌지 않는다(R37: 턴 진행 중에는 절대 버리지 않는다).
     */
    private void discardLedgerAfterError(String conversationId) {
        try {
            evidenceLedger.discard(conversationId);
        } catch (Exception ex) {
            log.warn("[LEDGER] 실패 턴 뒤 원장 정리 실패 cid={}: {}", conversationId, ex.getMessage());
        }
    }

    private void emitError(String sessionId, String conversationId, String message) {
        emit(sessionId, "ai.error", conversationId, null,
                Map.of("errorType", "INTERNAL", "message", message));
    }

    private void emit(String sessionId, String type, String correlationId, String targetId, Object payload) {
        if (sessionId == null) {
            return;
        }
        try {
            sseService.send(sessionId, SseService.SseEvent.of(type, correlationId, targetId, payload));
        } catch (Exception e) {
            log.warn("SSE 발행 실패 type={}: {}", type, e.getMessage());
        }
    }

    /** 턴이 끝나면 스트림을 닫는다. 클라이언트의 for-await 루프가 여기서 빠져나온다 */
    private void closeStream(String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            sseService.complete(sessionId);
        } catch (Exception e) {
            log.warn("SSE 종료 실패 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private String sanitize(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }

    /**
     * 턴 마무리: 인용 후처리 → ai.evidence(신규분) → ai.token(전문) → 원장 턴 경계. 라이브·데모 공통.
     *
     * <p>테스트 가능하도록 SSE·원장만 의존하는 정적 중첩 클래스로 뺐다. {@code discard()}는
     * 절대 여기서 부르지 않는다 — 정상 턴 종료는 원장을 지우는 경로가 아니다(ruling R37).
     * discard는 되묻기 중단·타임아웃·에러 종결 같은 별도 경로의 몫이다.
     *
     * <p><b>ai.evidence의 payload는 감싸지 않은 배열이다</b>({@code Evidence[]}) — 프론트
     * {@code types/sse.ts}의 {@code EvidencePayload}·{@code useAgentStream} 구현이 이 계약을
     * 기준으로 이미 만들어졌다({@code {items: [...]}}로 감싸지 않는다).
     *
     * <p><b>fix round 1 (F1):</b> 원장(ledger)의 세 호출 각각을 독립적으로 try/catch한다 —
     * 모델이 애써 만든 좋은 답변을 근거 원장의 DB 예외 하나가 통째로 삼켜서는 안 된다.
     * {@code knownNumbers} 실패 → 인용 후처리를 건너뛰고 원문을 그대로 스트리밍한다(빈
     * known 집합으로 sanitize하면 모든 인용이 지워지므로 그게 더 나쁘다). {@code newInTurn}
     * 실패 → {@code ai.evidence}만 생략한다. {@code endTurn} 실패 → 조용히 넘어간다(다음 턴
     * {@code ensureLoaded}가 DB 재조회로 복구한다). 세 경우 모두 텍스트는 항상 스트리밍되고
     * 호출부의 {@code ai.done}은 항상 발행된다 — 이 메서드가 예외를 밖으로 던지지 않는다.
     *
     * <p><b>fix round 2 (H1):</b> {@code finish()}의 {@code turnNo} 인자는 호출부
     * ({@code runTurn}/{@code runDemoTurn})가 {@code assistantTurnIndex(messages)+1}로
     * 계산해 넘긴다. {@code EvidenceLedger.endTurn()}이 더 이상 스스로 turnNo를 계산하지
     * 않으므로, 근거 없는 턴도 이 값이 1씩 증가해 {@code transcript()}의 "k번째 assistant
     * 줄"과 항상 같은 값을 가리킨다.
     */
    static final class TurnFinisher {
        private final SseService sse;
        private final EvidenceLedger ledger;

        TurnFinisher(SseService sse, EvidenceLedger ledger) {
            this.sse = sse;
            this.ledger = ledger;
        }

        String finish(String sessionId, String conversationId, String rawText, int turnNo) {
            String text;
            try {
                text = CitationSanitizer.sanitize(rawText, ledger.knownNumbers(conversationId));
            } catch (Exception e) {
                log.warn("[EVIDENCE] 원장 처리 실패 cid={}: {}", conversationId, e.toString());
                // 빈 known 집합으로 sanitize하면 모든 인용이 지워진다 — 그러느니 후처리를
                // 건너뛰고 원문을 그대로 내보낸다(환각 인용이 섞여 있을 수 있으나, 답변
                // 자체를 삼키는 것보다 낫다).
                text = rawText != null ? rawText : "";
            }

            List<Evidence> fresh = List.of();
            try {
                fresh = ledger.newInTurn(conversationId);
            } catch (Exception e) {
                log.warn("[EVIDENCE] 원장 처리 실패 cid={}: {}", conversationId, e.toString());
            }
            if (!fresh.isEmpty()) {
                send(sessionId, "ai.evidence", conversationId, conversationId, fresh);
            }

            if (!text.isBlank()) {
                send(sessionId, "ai.token", conversationId, null, text);
            }

            try {
                ledger.endTurn(conversationId, turnNo);
            } catch (Exception e) {
                log.warn("[EVIDENCE] 원장 처리 실패 cid={}: {}", conversationId, e.toString());
            }
            return text;
        }

        private void send(String sessionId, String type, String cid, String target, Object payload) {
            if (sessionId == null) {
                return;
            }
            try {
                sse.send(sessionId, SseService.SseEvent.of(type, cid, target, payload));
            } catch (Exception e) {
                log.warn("SSE 발행 실패 type={}: {}", type, e.getMessage());
            }
        }
    }
}
