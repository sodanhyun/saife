package io.saife.ai.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiConstants;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring AI 1.1.5 GoogleGenAiChatModel NSEE 버그 방어용 서브클래스.
 *
 * 라이브러리 버그 2건:
 * (A) responseCandidateToGeneration — candidate.content()/parts() empty 시 Optional.get() NSEE
 * (B) internalStream/internalCall — response.modelVersion().get()에서 modelVersion 없는 청크에 NSEE
 *
 * 본 서브클래스의 변경점:
 * 1. content/parts empty 케이스를 안전 처리 — 빈 AssistantMessage + 메타데이터(finishReason) 보존
 * 2. 2.0.0-M5의 anyMatch 시맨틱 백포팅 — 혼합 part(functionCall + text)에서 functionCall 누락 방지
 * 3. part.functionCall().get() 등 내부 Optional도 모두 .orElse() 사용
 * 4. internalStream 전체 오버라이드 — response.modelVersion().orElse("unknown")으로 NSEE 근본 제거
 *    Tool Calling 다중 라운드(보고서 생성 등 10+ 도구 호출) 시 청크 수 증가로 발생 빈도가 높았음
 *
 * finishReason 메타데이터는 절대 손실시키지 않아 ChatAgentService의 차단 사유 분류 로직이 작동 가능.
 */
@Slf4j
public class SafeCandidateGoogleGenAiChatModel extends GoogleGenAiChatModel {

    private final ObjectMapper objectMapper;

    // ── internalStream 오버라이드용 필드 복사본 ──
    // super의 필드는 모두 private → 서브클래스에서 접근 불가, 생성자 파라미터로부터 별도 보관
    private final Client safeClient;
    private final GoogleGenAiChatOptions safeDefaultOptions;
    private final ObservationRegistry safeObservationRegistry;
    private final ToolCallingManager safeToolCallingManager;
    private final ToolExecutionEligibilityPredicate safeEligibilityPredicate;

    // createGeminiRequest는 package-private → 리플렉션으로 호출
    private final Method createGeminiRequestMethod;

    // super.setObservationConvention() 호출 추적
    private static final ChatModelObservationConvention DEFAULT_CONVENTION =
            new DefaultChatModelObservationConvention();
    private ChatModelObservationConvention safeConvention;

    public SafeCandidateGoogleGenAiChatModel(
            Client genAiClient,
            GoogleGenAiChatOptions defaultOptions,
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObservationRegistry observationRegistry,
            ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate,
            ObjectMapper objectMapper) {
        super(genAiClient, defaultOptions, toolCallingManager, retryTemplate, observationRegistry,
                toolExecutionEligibilityPredicate);
        this.objectMapper = objectMapper;

        // 필드 복사본 보관
        this.safeClient = genAiClient;
        this.safeDefaultOptions = defaultOptions;
        this.safeObservationRegistry = observationRegistry;
        this.safeToolCallingManager = toolCallingManager;
        this.safeEligibilityPredicate = toolExecutionEligibilityPredicate;

        // createGeminiRequest 리플렉션 캐시
        try {
            this.createGeminiRequestMethod = GoogleGenAiChatModel.class
                    .getDeclaredMethod("createGeminiRequest", Prompt.class);
            this.createGeminiRequestMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Spring AI 버전 불일치: GoogleGenAiChatModel.createGeminiRequest 메서드 없음 — " +
                    "SafeCandidateGoogleGenAiChatModel은 spring-ai-google-genai 1.1.5 전용", e);
        }
    }

    @Override
    public void setObservationConvention(ChatModelObservationConvention convention) {
        super.setObservationConvention(convention);
        this.safeConvention = convention;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // internalStream 전체 오버라이드 — response.modelVersion().get() NSEE 근본 수정
    // 원본: GoogleGenAiChatModel.java:490-571 (spring-ai-google-genai 1.1.5)
    // 변경점: response.modelVersion().get() → response.modelVersion().orElse("unknown") (1줄)
    // ────────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    @Override
    public Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse) {
        return Flux.deferContextual(contextView -> {
            var observationContext = ChatModelObservationContext.builder()
                    .prompt(prompt)
                    .provider(GoogleGenAiConstants.PROVIDER_NAME)
                    .build();

            var convention = safeConvention != null ? safeConvention : DEFAULT_CONVENTION;
            Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
                    .observation(convention, DEFAULT_CONVENTION, () -> observationContext,
                            safeObservationRegistry);
            observation.parentObservation(
                    contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

            try {
                // createGeminiRequest — package-private이므로 리플렉션 호출
                Object req = createGeminiRequestMethod.invoke(this, prompt);
                String modelName = (String) req.getClass().getMethod("modelName").invoke(req);
                List<Content> contents = (List<Content>) req.getClass().getMethod("contents").invoke(req);
                GenerateContentConfig config = (GenerateContentConfig) req.getClass()
                        .getMethod("config").invoke(req);

                ResponseStream<GenerateContentResponse> responseStream =
                        safeClient.models.generateContentStream(modelName, contents, config);

                Flux<ChatResponse> chatResponseFlux = Flux.fromIterable(responseStream)
                        .switchMap(response -> {
                            // MALFORMED_FUNCTION_CALL 진단: response 레벨 raw 덤프
                            response.candidates().ifPresent(candidates -> {
                                for (Candidate c : candidates) {
                                    c.finishReason().ifPresent(fr -> {
                                        if (fr.toString().contains("MALFORMED_FUNCTION_CALL")) {
                                            log.warn("[SafeCandidate] MALFORMED response 진단 — " +
                                                    "candidateCount={}, candidate: {}",
                                                    candidates.size(), candidateToDebugString(c));
                                        }
                                    });
                                }
                            });

                            List<Generation> generations = response.candidates()
                                    .orElse(List.of())
                                    .stream()
                                    .map(this::responseCandidateToGeneration)
                                    .flatMap(List::stream)
                                    .toList();

                            var usageOpt = response.usageMetadata();
                            var options = (GoogleGenAiChatOptions) prompt.getOptions();
                            Usage currentUsage = usageOpt.isPresent()
                                    ? safeGetUsage(usageOpt.get(), options)
                                    : new DefaultUsage(0, 0, 0);
                            Usage cumulativeUsage = UsageCalculator.getCumulativeUsage(
                                    currentUsage, previousChatResponse);

                            // 핵심 수정: .get() → .orElse("unknown")
                            String modelVersion = response.modelVersion().orElse("unknown");

                            var chatResponse = new ChatResponse(generations,
                                    ChatResponseMetadata.builder()
                                            .usage(cumulativeUsage)
                                            .model(modelVersion)
                                            .build());
                            return Flux.just(chatResponse);
                        });

                Flux<ChatResponse> flux = chatResponseFlux.flatMap(response -> {
                    if (safeEligibilityPredicate.isToolExecutionRequired(
                            prompt.getOptions(), response)) {
                        return Flux.deferContextual(ctx -> {
                            ToolExecutionResult toolResult;
                            try {
                                ToolCallReactiveContextHolder.setContext(ctx);
                                toolResult = safeToolCallingManager
                                        .executeToolCalls(prompt, response);
                            } finally {
                                ToolCallReactiveContextHolder.clearContext();
                            }
                            if (toolResult.returnDirect()) {
                                return Flux.just(ChatResponse.builder().from(response)
                                        .generations(ToolExecutionResult
                                                .buildGenerations(toolResult))
                                        .build());
                            }
                            // 재귀 호출 — this.internalStream (오버라이드 버전)
                            return this.internalStream(
                                    new Prompt(toolResult.conversationHistory(),
                                            prompt.getOptions()),
                                    response);
                        }).subscribeOn(Schedulers.boundedElastic());
                    }
                    return Flux.just(response);
                })
                .doOnError(observation::error)
                .doFinally(s -> observation.stop())
                .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

                return new MessageAggregator().aggregate(flux, observationContext::setResponse);

            } catch (Exception e) {
                observation.error(e);
                observation.stop();
                return Flux.error(new RuntimeException("Failed to generate content", e));
            }
        });
    }

    /**
     * super.getDefaultUsage() 대체 — private 메서드이므로 재구현.
     * GoogleGenAiUsage.from()은 Extended Usage(thinking tokens 등)를 포함하는 기본 동작.
     */
    private Usage safeGetUsage(Object usageMetadata, GoogleGenAiChatOptions options) {
        boolean includeExtended = true;
        if (options != null && options.getIncludeExtendedUsageMetadata() != null) {
            includeExtended = options.getIncludeExtendedUsageMetadata();
        } else if (safeDefaultOptions.getIncludeExtendedUsageMetadata() != null) {
            includeExtended = safeDefaultOptions.getIncludeExtendedUsageMetadata();
        }
        if (includeExtended) {
            return GoogleGenAiUsage.from(
                    (com.google.genai.types.GenerateContentResponseUsageMetadata) usageMetadata);
        }
        var meta = (com.google.genai.types.GenerateContentResponseUsageMetadata) usageMetadata;
        return new DefaultUsage(
                meta.promptTokenCount().orElse(0),
                meta.candidatesTokenCount().orElse(0),
                meta.totalTokenCount().orElse(0));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // responseCandidateToGeneration — candidate 레벨 NSEE 방어 (기존 유지)
    // ────────────────────────────────────────────────────────────────────────────
    @Override
    protected List<Generation> responseCandidateToGeneration(Candidate candidate) {
        int candidateIndex = candidate.index().orElse(0);
        FinishReason finishReason = candidate.finishReason()
                .orElse(new FinishReason(FinishReason.Known.STOP));

        Map<String, Object> messageMetadata = new HashMap<>();
        messageMetadata.put("candidateIndex", candidateIndex);
        messageMetadata.put("finishReason", finishReason);

        ChatGenerationMetadata chatGenerationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason.toString())
                .build();

        Optional<Content> contentOpt = candidate.content();
        Optional<List<Part>> partsOpt = contentOpt.flatMap(Content::parts);
        List<Part> parts = partsOpt.orElse(List.of());

        // 빈 candidate 방어 — content empty / parts empty / parts list 비어있음 모두 동일 처리
        if (parts.isEmpty()) {
            // MALFORMED_FUNCTION_CALL 진단: candidate raw 데이터 덤프
            String frStr = finishReason.toString();
            if (frStr.contains("MALFORMED_FUNCTION_CALL")) {
                log.warn("[SafeCandidate] MALFORMED_FUNCTION_CALL 진단 — candidate raw: {}",
                        candidateToDebugString(candidate));
            }
            log.warn("[SafeCandidate] 빈 candidate 수신 (NSEE 회피) — finishReason={}, " +
                            "contentPresent={}, partsPresent={}, partsEmpty={}",
                    finishReason,
                    contentOpt.isPresent(),
                    partsOpt.isPresent(),
                    partsOpt.map(List::isEmpty).orElse(false));
            AssistantMessage emptyMessage = AssistantMessage.builder()
                    .content("")
                    .properties(messageMetadata)
                    .build();
            return List.of(new Generation(emptyMessage, chatGenerationMetadata));
        }

        // thoughtSignatures 추출 (1.1.5 super 동작과 동일)
        List<byte[]> thoughtSignatures = parts.stream()
                .filter(p -> p.thoughtSignature().isPresent())
                .map(p -> p.thoughtSignature().get())
                .toList();
        if (!thoughtSignatures.isEmpty()) {
            messageMetadata.put("thoughtSignatures", thoughtSignatures);
        }

        // 2.0.0-M5 시맨틱 백포팅: 어느 part 하나라도 functionCall이면 tool-call 분기
        boolean hasFunctionCall = parts.stream().anyMatch(p -> p.functionCall().isPresent());

        if (hasFunctionCall) {
            List<AssistantMessage.ToolCall> toolCalls = parts.stream()
                    .filter(p -> p.functionCall().isPresent())
                    .map(p -> {
                        FunctionCall fc = p.functionCall().get();
                        String name = fc.name().orElse("");
                        String args = mapToJsonSafe(fc.args().orElse(Map.of()));
                        return new AssistantMessage.ToolCall("", "function", name, args);
                    })
                    .toList();

            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .properties(messageMetadata)
                    .toolCalls(toolCalls)
                    .build();

            return List.of(new Generation(assistantMessage, chatGenerationMetadata));
        }

        // 텍스트 분기 — part마다 Generation 생성 (1.1.5 동작 유지)
        return parts.stream()
                .map(p -> AssistantMessage.builder()
                        .content(p.text().orElse(""))
                        .properties(messageMetadata)
                        .build())
                .map(m -> new Generation(m, chatGenerationMetadata))
                .toList();
    }

    /**
     * MALFORMED_FUNCTION_CALL 진단용 — Candidate의 모든 필드를 안전하게 문자열로 덤프.
     * content, parts, finishReason, finishMessage, groundingMetadata, safetyRatings 등 확인.
     */
    private String candidateToDebugString(Candidate candidate) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("index=").append(candidate.index().orElse(-1));
            sb.append(", finishReason=").append(candidate.finishReason().map(Object::toString).orElse("null"));
            sb.append(", finishMessage=").append(candidate.finishMessage().orElse("null"));
            sb.append(", content.present=").append(candidate.content().isPresent());
            candidate.content().ifPresent(content -> {
                sb.append(", content.role=").append(content.role().orElse("null"));
                content.parts().ifPresent(parts -> {
                    sb.append(", parts.size=").append(parts.size());
                    for (int i = 0; i < Math.min(parts.size(), 3); i++) {
                        Part p = parts.get(i);
                        sb.append(", part[").append(i).append("]={");
                        sb.append("text=").append(p.text().map(t -> t.substring(0, Math.min(t.length(), 200))).orElse("null"));
                        sb.append(", functionCall=").append(p.functionCall().map(fc ->
                                "name=" + fc.name().orElse("null") + ", args=" +
                                        fc.args().map(a -> a.toString().substring(0, Math.min(a.toString().length(), 300))).orElse("null")
                        ).orElse("null"));
                        sb.append("}");
                    }
                });
            });
            candidate.safetyRatings().ifPresent(ratings ->
                    sb.append(", safetyRatings=").append(ratings.size()).append("건"));
        } catch (Exception e) {
            sb.append(" [덤프 중 오류: ").append(e.getMessage()).append("]");
        }
        return sb.toString();
    }

    private String mapToJsonSafe(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("[SafeCandidate] functionCall args JSON 직렬화 실패 — fallback to '{{}}': {}",
                    e.getMessage());
            return "{}";
        }
    }
}
