package io.saife.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 툴 이름 환각(예: camelCase 등록인데 snake_case 호출)을 흡수하는 ToolCallingManager 위임 래퍼.
 * Gemini가 생성한 ToolCall 이름을 등록된 정식 이름으로 정규화한 뒤 delegate에 위임한다.
 */
@Slf4j
public class FuzzyToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;

    public FuzzyToolCallingManager(ToolCallingManager delegate) {
        Assert.notNull(delegate, "delegate ToolCallingManager must not be null");
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ChatResponse rewritten = rewriteToolCallNames(prompt, chatResponse);
        return delegate.executeToolCalls(prompt, rewritten);
    }

    private ChatResponse rewriteToolCallNames(Prompt prompt, ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return response;
        }

        Set<String> registeredNames = collectRegisteredToolNames(prompt);
        if (registeredNames.isEmpty()) {
            return response;
        }

        Map<String, String> normalizedToCanonical = new HashMap<>();
        for (String name : registeredNames) {
            normalizedToCanonical.putIfAbsent(normalize(name), name);
        }

        boolean anyMutation = false;
        List<Generation> rewrittenGenerations = new ArrayList<>(response.getResults().size());

        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message == null || !message.hasToolCalls()) {
                rewrittenGenerations.add(generation);
                continue;
            }

            List<AssistantMessage.ToolCall> originalCalls = message.getToolCalls();
            List<AssistantMessage.ToolCall> rewrittenCalls = new ArrayList<>(originalCalls.size());
            boolean changed = false;

            for (AssistantMessage.ToolCall call : originalCalls) {
                String requested = call.name();
                if (requested != null && registeredNames.contains(requested)) {
                    rewrittenCalls.add(call);
                    continue;
                }
                String canonical = requested == null ? null : normalizedToCanonical.get(normalize(requested));
                if (canonical != null) {
                    log.warn("[FuzzyTool] 툴 이름 정규화: {} → {}", requested, canonical);
                    rewrittenCalls.add(new AssistantMessage.ToolCall(call.id(), call.type(), canonical, call.arguments()));
                    changed = true;
                } else {
                    rewrittenCalls.add(call);
                }
            }

            if (!changed) {
                rewrittenGenerations.add(generation);
                continue;
            }

            anyMutation = true;
            AssistantMessage rebuilt = AssistantMessage.builder()
                    .content(message.getText())
                    .properties(message.getMetadata())
                    .toolCalls(rewrittenCalls)
                    .media(message.getMedia())
                    .build();
            rewrittenGenerations.add(new Generation(rebuilt, generation.getMetadata()));
        }

        if (!anyMutation) {
            return response;
        }
        return new ChatResponse(rewrittenGenerations, response.getMetadata());
    }

    private Set<String> collectRegisteredToolNames(Prompt prompt) {
        Set<String> names = new HashSet<>();
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return names;
        }
        List<ToolCallback> callbacks = options.getToolCallbacks();
        if (callbacks != null) {
            for (ToolCallback cb : callbacks) {
                ToolDefinition def = cb.getToolDefinition();
                if (def != null && def.name() != null) {
                    names.add(def.name());
                }
            }
        }
        Set<String> globalNames = options.getToolNames();
        if (globalNames != null) {
            names.addAll(globalNames);
        }
        return names;
    }

    private String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
}
