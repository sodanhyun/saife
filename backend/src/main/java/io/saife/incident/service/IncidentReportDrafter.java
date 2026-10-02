package io.saife.incident.service;

import io.saife.ai.config.GeminiSafetySettings;
import io.saife.common.config.DemoModeConfig;
import io.saife.evidence.Evidence;
import io.saife.evidence.EvidenceKind;
import io.saife.evidence.ledger.CitationSanitizer;
import io.saife.incident.domain.Incident;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 산업재해조사표의 <b>재해발생 원인</b>과 <b>재발방지 계획</b> 초안을 쓴다.
 *
 * <p>여기서만 모델이 문장을 만든다. 등급·기한·이력 소환은 전부 결정론적 코드다.
 * 모델이 하는 일은 <b>이미 소환된 사실을 문장으로 옮기는 것</b>뿐이고,
 * 새로운 사실을 지어내면 안 된다 — 그래서 프롬프트가 근거를 통째로 넘긴다.
 *
 * <p><b>실패해도 사고 등록은 성공한다.</b> 조사표 문안은 사람이 고쳐 쓰는 초안이고,
 * 모델이 죽었다고 법정 기한 타이머까지 못 만들면 그건 설계가 잘못된 것이다.
 * 무대에서 네트워크가 끊겨도 UC2의 뼈대는 그대로 돈다.
 *
 * <p>출력물에는 '작성 보조'임을 명시한다. 법률 자문이 아니고 제출은 사람이 한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentReportDrafter {

    private final ChatClient.Builder chatClientBuilder;
    private final DemoModeConfig demoModeConfig;

    /** @param aiGenerated 모델이 실제로 썼는지. false면 폴백 문안이다 */
    public record Draft(String cause, String prevention, boolean aiGenerated) {}

    /**
     * 조사표 문안 초안을 만든다. 어떤 이유로든 실패하면 폴백 문안을 돌려준다.
     *
     * <p>{@code evidence}는 이번 사고 등록 응답에 실린 근거(유사 사례 3 + 사고 후 조문 2,
     * {@code #n} 번호가 이미 붙어 있다) — 프롬프트에 카드로 나열하고, 모델 출력은
     * {@link CitationSanitizer}로 후처리해 <b>이 목록에 없는 번호</b>는 지운다(환각 인용 차단).
     * 빈 리스트여도 안전하게 동작한다 — 인용할 게 없으면 그냥 인용이 없는 문안이 된다.
     *
     * @param incident 등록된 사고
     * @param recall   소환된 설비 이력 — 문안의 근거가 된다
     * @param evidence 번호가 매겨진 근거 목록(유사 사례 + 사고 후 조문)
     */
    public Draft draft(Incident incident, EquipmentHistoryRecaller.Recall recall, List<Evidence> evidence) {
        Set<Integer> knownNos = evidence.stream().map(Evidence::no).collect(Collectors.toSet());

        if (demoModeConfig.isDemoMode()) {
            log.info("[UC2] 데모 모드 — 조사표 문안은 폴백을 쓴다");
            return sanitize(fallback(incident, recall), knownNos);
        }
        try {
            String text = chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM)
                    .user(buildUserPrompt(incident, recall, evidence))
                    .options(GoogleGenAiChatOptions.builder()
                            .safetySettings(GeminiSafetySettings.SAFETY_SETTINGS_OFF)
                            // 근거가 프롬프트에 다 있는 정리 작업이라 깊은 사고가 필요 없다. 기본 사고 단계에서는
                            // 등록 응답이 25~110초 걸렸다(2026-10-02 실측). 사고 단계를 낮춰 화면이 기다리지 않게 한다
                            .thinkingLevel(org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel.LOW)
                            .build())
                    .call()
                    .content();

            Draft parsed = parse(text);
            if (parsed == null) {
                log.warn("[UC2] 조사표 문안 파싱 실패 — 폴백 사용");
                return sanitize(fallback(incident, recall), knownNos);
            }
            return sanitize(parsed, knownNos);

        } catch (Exception e) {
            // 모델이 죽어도 사고 등록과 법정 기한은 살아야 한다
            log.warn("[UC2] 조사표 문안 생성 실패 — 폴백 사용: {}", e.toString());
            return sanitize(fallback(incident, recall), knownNos);
        }
    }

    /** 모델이 목록에 없는 번호를 인용했으면 지운다. 폴백 문안도 예외 없이 거친다(항상 안전한 경로 하나) */
    private Draft sanitize(Draft d, Set<Integer> knownNos) {
        return new Draft(CitationSanitizer.sanitize(d.cause(), knownNos),
                CitationSanitizer.sanitize(d.prevention(), knownNos), d.aiGenerated());
    }

    private static final String SYSTEM = """
            당신은 산업재해조사표 작성을 돕는 보조자입니다. 법률 자문을 하지 않습니다.

            아래 규칙을 지키십시오.
            - 주어진 근거에 없는 사실을 만들지 마십시오. 특히 날짜·수치·설비명·인명을 지어내지 마십시오.
            - 근거가 부족하면 "확인 필요"라고 쓰십시오. 추측을 단정형으로 쓰지 마십시오.
            - 재발방지 계획에는 이미 등록돼 있던 미이행 조치가 있으면 그것을 첫 항목으로 쓰십시오.
              새 대책을 나열하기 전에 하기로 했던 것부터 다루는 것이 조사표의 신뢰를 만듭니다.
            - 한국어 공문 문체로 쓰십시오. 가운뎃점(·)과 대시(—, –)는 쓰지 말고 쉼표나 괄호를 쓰십시오.
            - 재발방지 대책 문장 끝에 근거 번호를 [#n]으로 붙이세요. 목록에 없는 번호는 쓰지 마세요.

            출력 형식을 정확히 지키십시오. 다른 말을 덧붙이지 마십시오.

            [원인]
            (재해발생 원인. 3~5문장)

            [재발방지]
            1. (대책)
            2. (대책)
            3. (대책)
            """;

    private String buildUserPrompt(Incident incident, EquipmentHistoryRecaller.Recall recall, List<Evidence> evidence) {
        StringBuilder sb = new StringBuilder();

        sb.append("[사고 개요]\n");
        sb.append("- 발생일시: ").append(incident.getOccurredAt()).append('\n');
        sb.append("- 설비: ").append(recall.equipmentName());
        if (recall.locationTag() != null) {
            sb.append(" (").append(recall.locationTag()).append(')');
        }
        sb.append('\n');
        if (incident.getAccidentType() != null) {
            sb.append("- 발생형태: ").append(incident.getAccidentType().getLabel()).append('\n');
        }
        if (incident.getLeaveDays() != null) {
            sb.append("- 휴업일수: ").append(incident.getLeaveDays()).append("일\n");
        }
        sb.append("- 재해 경위: ").append(nvl(incident.getDescription(), "(미입력)")).append('\n');

        sb.append("\n[이 설비에 사고 전부터 기록돼 있던 위험요인]\n");
        if (recall.priorHazards().isEmpty()) {
            sb.append("- 없음\n");
        } else {
            for (EquipmentHistoryRecaller.PriorHazard h : recall.priorHazards()) {
                sb.append("- [").append(h.accidentType() == null ? "?" : h.accidentType().getLabel())
                        .append("] ").append(nvl(h.missingControl(), h.description()));
                if (h.lastRiskLevel() != null) {
                    sb.append(" — 최근 평가 등급 ").append(h.lastRiskLevel());
                }
                if (h.lastAssessedOn() != null) {
                    sb.append(" (").append(h.lastAssessedOn()).append(')');
                }
                sb.append('\n');
            }
        }

        sb.append("\n[미이행 상태였던 감소대책]\n");
        if (recall.unfinishedActions().isEmpty()) {
            sb.append("- 없음\n");
        } else {
            for (EquipmentHistoryRecaller.UnfinishedAction a : recall.unfinishedActions()) {
                sb.append("- ").append(a.content());
                if (a.dueDate() != null) {
                    sb.append(" (기한 ").append(a.dueDate());
                    if (a.overdueDays() != null && a.overdueDays() > 0) {
                        sb.append(", 사고 시점에 ").append(a.overdueDays()).append("일 경과");
                    }
                    sb.append(')');
                }
                sb.append('\n');
            }
        }

        if (recall.warnedAt() != null) {
            sb.append("\n[작업 전 브리핑] 작업자가 ").append(recall.warnedAt())
                    .append("에 위험 브리핑을 확인했습니다.\n");
        }

        appendEvidenceSection(sb, evidence);

        sb.append("\n위 근거만 사용해 [원인]과 [재발방지]를 작성하십시오.");
        return sb.toString();
    }

    /**
     * 근거 목록을 {@code #n} 카드로 나열한다. {@code IncidentEvidenceCollector}가 이미
     * 번호를 매겨 넘긴다 — 유사 사례(사진 우선) 3건 + 사고 후 조문 2건.
     */
    private void appendEvidenceSection(StringBuilder sb, List<Evidence> evidence) {
        if (evidence.isEmpty()) {
            return;
        }
        sb.append("\n[근거 목록 — 인용은 [#n] 형식으로]\n");
        for (Evidence e : evidence) {
            String label = e.kind().isCase() ? "사례" : e.kind() == EvidenceKind.LAW ? "조문" : e.kind().name();
            sb.append('#').append(e.no()).append(" [").append(label).append("] ").append(e.title());
            if (e.snippet() != null && !e.snippet().isBlank()) {
                sb.append(" (").append(e.snippet()).append(')');
            }
            sb.append('\n');
        }
    }

    /** "[원인] ... [재발방지] ..." 형식을 가른다 */
    private Draft parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int causeAt = text.indexOf("[원인]");
        int preventionAt = text.indexOf("[재발방지]");
        if (causeAt < 0 || preventionAt < 0 || preventionAt < causeAt) {
            return null;
        }
        String cause = text.substring(causeAt + "[원인]".length(), preventionAt).trim();
        String prevention = text.substring(preventionAt + "[재발방지]".length()).trim();
        if (cause.isBlank() || prevention.isBlank()) {
            return null;
        }
        return new Draft(cause, prevention, true);
    }

    /**
     * 모델 없이 쓰는 문안.
     *
     * <p>문장이 투박해도 <b>틀린 내용은 없다</b> — 소환된 사실만 나열한다.
     * 화면에는 "AI 생성 아님"이 표시되어 심사위원이 구분할 수 있다.
     */
    private Draft fallback(Incident incident, EquipmentHistoryRecaller.Recall recall) {
        StringBuilder cause = new StringBuilder();
        cause.append(nvl(incident.getDescription(), "재해 경위 확인 필요")).append('\n');
        if (recall.predicted()) {
            cause.append("동일 발생형태의 위험요인이 사고 전부터 등록돼 있었습니다. ");
        }
        if (!recall.unfinishedActions().isEmpty()) {
            cause.append("사고 시점에 미이행 상태였던 감소대책이 ")
                    .append(recall.unfinishedActions().size()).append("건 있었습니다. ");
        }
        cause.append("구체적 원인은 현장 조사로 확인이 필요합니다.");

        StringBuilder prevention = new StringBuilder();
        int n = 1;
        for (EquipmentHistoryRecaller.UnfinishedAction a : recall.unfinishedActions()) {
            prevention.append(n++).append(". 미이행 조치 이행: ").append(a.content());
            if (a.dueDate() != null) {
                prevention.append(" (당초 기한 ").append(a.dueDate()).append(')');
            }
            prevention.append('\n');
        }
        prevention.append(n++).append(". 재해 발생 작업의 수시평가를 완료한 뒤 작업을 재개합니다.\n");
        prevention.append(n).append(". 동일 설비를 사용하는 작업의 작업계획서를 재검토합니다.");

        return new Draft(cause.toString(), prevention.toString(), false);
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
