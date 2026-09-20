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
import io.saife.core.service.RiskRuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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
    private final ObjectMapper objectMapper;

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
            2. 확인된 이력 중 경고할 것이 있으면 먼저 말합니다.
            3. extractWorkPlan으로 작업계획서 항목을 구조화합니다.
            4. analyzeHazards / searchCases / getMsds로 근거를 모읍니다.
            5. createWorkPlan으로 제출하고 브리핑을 전달합니다.

            도구가 status=INCOMPLETE를 돌려주면 등록되지 않은 것입니다.
            missing 항목을 한 번에 하나씩 물어보고, 답을 받으면 이전 값과 함께 도구를 다시 호출하세요.

            [오늘 날짜] %s (%s)
            작업자가 "내일", "모레", "이번 주 금요일"처럼 말하면 이 날짜를 기준으로 계산하세요.
            날짜를 알 수 있는데도 되묻지 마세요. 예시를 들 때도 오늘 이후의 날짜만 쓰세요.

            한국어로, 현장 담당자가 읽기 쉽게 답하세요.
            서식 기호(별표, 인용부호)를 최소로 쓰고, 목록은 "- "로만 표시하세요.
            """;

    /**
     * 오늘 날짜를 넣은 시스템 프롬프트.
     *
     * <p><b>날짜를 안 넣으면 모델이 "내일"을 해석하지 못한다.</b> 실제로 작업자가
     * "내일 ~할 건데요"라고 말했는데 모델이 작업 일자를 되물었고, 예시로 과거
     * 날짜(2026-05-15)를 들었다 (2026-09-21 QA 실측). 시스템이 아는 것을
     * 되묻는 건 이 제품이 하지 않기로 한 일이다.
     */
    private String systemPrompt() {
        LocalDate today = LocalDate.now();
        return SYSTEM_PROMPT_TEMPLATE.formatted(
                today, today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN));
    }

    /**
     * 대화 한 턴을 처리한다.
     *
     * <p>같은 {@code conversationId}로 다시 부르면 히스토리를 이어 붙인다.
     * 되묻기 답변도 이 경로로 들어온다 — 별도 재개 API가 필요 없다.
     */
    public void chat(String conversationId, String sessionId, Long siteId, String userMessage) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseGet(() -> conversationRepository.save(Conversation.builder()
                        .id(conversationId)
                        .siteId(siteId)
                        .build()));

        List<Message> messages = loadHistory(conversationId);
        messages.add(new UserMessage(userMessage));

        runTurn(conversation, sessionId, siteId, messages);
    }

    private void runTurn(Conversation conversation, String sessionId, Long siteId, List<Message> messages) {
        String conversationId = conversation.getId();

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(AgentContextKeys.CONVERSATION_ID, conversationId);
        toolContext.put(AgentContextKeys.SESSION_ID, sessionId);
        toolContext.put(AgentContextKeys.SITE_ID, siteId);

        try {
            if (demoModeConfig.isDemoMode()) {
                emitToken(sessionId, conversationId,
                        "데모 모드입니다. 모델 응답은 고정 문구로 대체되며 도구는 실제로 실행됩니다.\n");
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

            messages.add(assistant);
            saveHistory(conversationId, messages);

            if (!text.isBlank()) {
                emitToken(sessionId, conversationId, text);
            }
            emit(sessionId, "ai.done", conversationId, null, Map.of("finished", true));

        } catch (Exception e) {
            log.error("[AGENT] 턴 실패 conversationId={}", conversationId, e);
            persistToolCalls(conversationId);
            emitError(sessionId, conversationId, sanitize(e));

        } finally {
            // ⚠️ 반드시 닫는다. 안 닫으면 클라이언트가 emitter 타임아웃(5분)까지 기다린다.
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
    public record TranscriptLine(String role, String text) {}

    /**
     * 저장된 대화를 화면용으로 돌려준다.
     *
     * <p>새로고침하면 브라우저의 대화 ID가 사라져 <b>모델이 이미 아는 것을 다시 묻는다.</b>
     * 실제로 QA에서 장소를 다시 물었다 (2026-09-21). 그대로 두면 이어지는
     * {@code extractWorkPlan}이 새 대화 ID로 초안을 하나 더 만들어 계획서가 쪼개진다.
     *
     * <p>그래서 프론트가 대화 ID를 보관하고 기동 시 여기로 기록을 복원한다.
     * 시스템 프롬프트와 도구 메시지는 화면에 내보내지 않는다.
     */
    @Transactional(readOnly = true)
    public List<TranscriptLine> transcript(String conversationId) {
        List<TranscriptLine> out = new ArrayList<>();
        conversationStateRepository.findById(conversationId).ifPresent(state -> {
            try {
                List<?> rows = objectMapper.readValue(state.getMessagesJson(), List.class);
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
                    if (!text.isBlank()) {
                        out.add(new TranscriptLine(type, text));
                    }
                }
            } catch (Exception e) {
                log.warn("[AGENT] 기록 복원 실패 conversationId={}", conversationId);
            }
        });
        return out;
    }

    private List<Message> loadHistory(String conversationId) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt()));

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

    private void emitToken(String sessionId, String conversationId, String text) {
        emit(sessionId, "ai.token", conversationId, null, text);
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
}
