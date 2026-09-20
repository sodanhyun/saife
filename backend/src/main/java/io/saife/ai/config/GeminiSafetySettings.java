package io.saife.ai.config;

import org.springframework.ai.google.genai.common.GoogleGenAiSafetySetting;
import org.springframework.ai.google.genai.common.GoogleGenAiSafetySetting.HarmBlockMethod;
import org.springframework.ai.google.genai.common.GoogleGenAiSafetySetting.HarmBlockThreshold;
import org.springframework.ai.google.genai.common.GoogleGenAiSafetySetting.HarmCategory;

import java.util.List;

/**
 * Gemini 안전 필터 공통 설정 — 모든 카테고리에서 차단 비활성화.
 * ChatAgentService, FollowUpSuggestionGenerator, StreamingRetryOrchestrator 등에서 공유.
 */
public final class GeminiSafetySettings {

    private GeminiSafetySettings() {}

    /** 모든 안전 카테고리에서 차단을 비활성화한 설정 목록 */
    public static final List<GoogleGenAiSafetySetting> SAFETY_SETTINGS_OFF = List.of(
            new GoogleGenAiSafetySetting(HarmCategory.HARM_CATEGORY_HATE_SPEECH,
                    HarmBlockThreshold.BLOCK_NONE, HarmBlockMethod.PROBABILITY),
            new GoogleGenAiSafetySetting(HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT,
                    HarmBlockThreshold.BLOCK_NONE, HarmBlockMethod.PROBABILITY),
            new GoogleGenAiSafetySetting(HarmCategory.HARM_CATEGORY_HARASSMENT,
                    HarmBlockThreshold.BLOCK_NONE, HarmBlockMethod.PROBABILITY),
            new GoogleGenAiSafetySetting(HarmCategory.HARM_CATEGORY_SEXUALLY_EXPLICIT,
                    HarmBlockThreshold.BLOCK_NONE, HarmBlockMethod.PROBABILITY)
    );
}
