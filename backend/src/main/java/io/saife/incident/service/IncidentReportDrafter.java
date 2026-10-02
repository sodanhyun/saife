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

    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");
    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
            return sanitize(fallback(incident, recall), knownNos, incident);
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
                return sanitize(fallback(incident, recall), knownNos, incident);
            }
            return sanitize(parsed, knownNos, incident);

        } catch (Exception e) {
            // 모델이 죽어도 사고 등록과 법정 기한은 살아야 한다
            log.warn("[UC2] 조사표 문안 생성 실패 — 폴백 사용: {}", e.toString());
            return sanitize(fallback(incident, recall), knownNos, incident);
        }
    }

    /**
     * 모델이 목록에 없는 번호를 인용했으면 지운다. 폴백 문안도 예외 없이 거친다(항상 안전한 경로 하나).
     * 재발방지 줄은 "무엇을 (담당 누구, 기한 YYYY-MM-DD)" 형식으로 맞춘다({@link PreventionFormat}).
     */
    private Draft sanitize(Draft d, Set<Integer> knownNos, Incident incident) {
        String prevention = CitationSanitizer.sanitize(d.prevention(), knownNos);
        prevention = PreventionFormat.normalize(prevention,
                incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate(), SAFETY_MANAGER);
        return new Draft(CitationSanitizer.sanitize(d.cause(), knownNos), prevention, d.aiGenerated());
    }

    /** 담당이 비었을 때 쓰는 사람. 사업장 안전관리자(시드의 샘플 이름) */
    static final String SAFETY_MANAGER = "안전관리자 홍길동";

    /**
     * 산업재해조사표(별지 제30호서식) "재해 발생 원인"과 "재발방지 계획"의 초안.
     *
     * <p>조사표는 관할 관서에 <b>제출하는 문서</b>다. 원인은 불안전한 상태와 불안전한 행동으로 나눠 확인된 사실만
     * 쓰고, 사업주의 법 위반이나 과실을 스스로 단정하는 문장을 넣지 않는다. 사전 지적 사항의 미이행 경위는
     * 사내 「재발방지 검토서」가 다룬다. 재발방지는 "무엇을 (담당 누구, 기한 YYYY-MM-DD)" 한 줄 형식이고,
     * "작업 재개 전" 같은 날짜 아닌 기한을 쓰지 않는다.
     */
    private static final String SYSTEM = """
            당신은 산업재해조사표(산업안전보건법 시행규칙 별지 제30호서식) 작성을 돕는 보조자입니다. 법률 자문을 하지 않습니다.

            규칙
            - 주어진 자료에 없는 사실을 만들지 마십시오. 날짜, 수치, 설비명, 인명을 지어내지 마십시오.
            - [원인]은 두 줄입니다. 첫 줄은 "불안전한 상태: "로 시작해 설비, 작업 환경, 방호 상태 중 자료에서 확인되는 것을,
              둘째 줄은 "불안전한 행동: "으로 시작해 재해자의 동작이나 작업 방법 중 자료에서 확인되는 것을 사실형 평서문으로 씁니다.
              재해발생 당시 상황 문장을 그대로 반복하지 마십시오. 확인되지 않으면 "(확인 필요)"라고 씁니다.
              "위반", "과실", "소홀", "방치", "미이행으로 인해" 같은 책임을 단정하는 말은 쓰지 마십시오.
            - [재발방지]는 3개 이내, 한 줄에 하나, 반드시 이 형식입니다: 번호. 무엇을 (담당 직책 이름, 기한 YYYY-MM-DD)
              담당은 자료에 있는 담당자를 그대로 쓰고, 없으면 "안전관리자 홍길동"으로 씁니다.
              기한은 반드시 자료의 [기한 기준]에 있는 날짜 형식(YYYY-MM-DD)입니다. "작업 재개 전", "즉시", "상시" 같은 말을 쓰지 마십시오.
              감소대책 우선순위(제거, 공학적 대책, 관리적 대책, 보호구)를 따르고, 자료의 미이행 감소대책이 있으면 첫 줄에 둡니다.
              조사표 제출, 수시평가 실시 같은 행정 절차는 재발방지 대책이 아니므로 쓰지 마십시오.
            - 재발방지 줄 끝에만 근거 번호를 [#n]으로 붙일 수 있습니다. 목록에 없는 번호는 쓰지 마십시오. [원인]에는 붙이지 마십시오.
            - 가운뎃점, 대시, 화살표 기호를 쓰지 마십시오. 쉼표와 괄호를 쓰십시오.

            출력 형식(다른 말을 덧붙이지 마십시오)

            [원인]
            불안전한 상태: (사실)
            불안전한 행동: (사실)

            [재발방지]
            1. (무엇을) (담당 직책 이름, 기한 YYYY-MM-DD)
            2. ...
            """;

    private String buildUserPrompt(Incident incident, EquipmentHistoryRecaller.Recall recall, List<Evidence> evidence) {
        StringBuilder sb = new StringBuilder();

        sb.append("[재해 발생 개요]\n");
        sb.append("- 발생일시: ").append(incident.getOccurredAt().atZoneSameInstant(KST).format(TS)).append('\n');
        sb.append("- 설비: ").append(recall.equipmentName());
        if (recall.locationTag() != null) {
            sb.append(" (").append(recall.locationTag()).append(')');
        }
        sb.append('\n');
        if (incident.getIncidentType() != null) {
            sb.append("- 발생형태: ").append(incident.getIncidentType().getLabel()).append('\n');
        }
        if (incident.getSeverity() != null) {
            sb.append("- 재해 정도: ").append(incident.getSeverity().getLabel()).append('\n');
        }
        if (incident.getLeaveDays() != null) {
            sb.append("- 휴업예상일수: ").append(incident.getLeaveDays()).append("일\n");
        }
        sb.append("- 재해발생 당시 상황: ").append(nvl(incident.getDescription(), "(미입력)")).append('\n');
        if (incident.getInjuryType() != null || incident.getInjuryPart() != null) {
            sb.append("- 상해: ").append(nvl(incident.getInjuryType(), "(미입력)"))
                    .append(", 부위 ").append(nvl(incident.getInjuryPart(), "(미입력)")).append('\n');
        }
        java.time.LocalDate occurredOn = incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate();
        sb.append("\n[기한 기준]\n");
        sb.append("- 관리적 대책(점검표, 교육, 작업 방법): ").append(PreventionFormat.adminDue(occurredOn)).append('\n');
        sb.append("- 공학적 대책(설비, 방호장치, 작업발판): ").append(PreventionFormat.engineeringDue(occurredOn)).append('\n');

        sb.append("\n[이 설비의 사고 전 위험성평가]\n");
        if (recall.priorHazards().isEmpty()) {
            sb.append("- 없음\n");
        } else {
            for (EquipmentHistoryRecaller.PriorHazard h : recall.priorHazards()) {
                sb.append("- ").append(h.accidentType() == null ? "발생형태 미상" : h.accidentType().getLabel())
                        .append(": ").append(nvl(h.missingControl(), h.description()));
                if (h.lastRiskLevel() != null) {
                    sb.append(", 등급 ").append(h.lastRiskLevel().getLabel());
                }
                if (h.lastAssessedOn() != null) {
                    sb.append(" (").append(h.lastAssessedOn()).append(')');
                }
                sb.append('\n');
            }
        }

        sb.append("\n[사고 시점 미이행 감소대책]\n");
        if (recall.unfinishedActions().isEmpty()) {
            sb.append("- 없음\n");
        } else {
            for (EquipmentHistoryRecaller.UnfinishedAction a : recall.unfinishedActions()) {
                sb.append("- ").append(a.content());
                if (a.owner() != null && !a.owner().isBlank()) {
                    sb.append(" (담당 ").append(a.owner()).append(')');
                }
                if (a.dueDate() != null) {
                    sb.append(", 당초 기한 ").append(a.dueDate());
                }
                sb.append('\n');
            }
        }

        appendEvidenceSection(sb, evidence);

        sb.append("\n위 자료만 사용해 [원인]과 [재발방지]를 작성하십시오.");
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
        sb.append("\n[근거 목록, 인용은 [#n] 형식]\n");
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
     * 모델 없이 쓰는 문안. 소환된 사실만 옮긴다. 책임을 단정하는 문장을 쓰지 않는다.
     * 원인은 불안전한 상태와 불안전한 행동 두 줄, 재발방지는 날짜 기한이다.
     */
    private Draft fallback(Incident incident, EquipmentHistoryRecaller.Recall recall) {
        java.time.LocalDate occurredOn = incident.getOccurredAt().atZoneSameInstant(KST).toLocalDate();
        String state = recall.priorHazards().stream()
                .filter(EquipmentHistoryRecaller.PriorHazard::sameAxisAsIncident)
                .map(EquipmentHistoryRecaller.PriorHazard::missingControl)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .map(m -> m + " 상태에서 작업 (현장 확인 필요).")
                .orElse("(확인 필요)");
        String cause = "불안전한 상태: " + state + "\n"
                + "불안전한 행동: " + nvl(incident.getDescription(), "(확인 필요)")
                + (incident.getDescription() != null && !incident.getDescription().strip().endsWith(".") ? "." : "");

        StringBuilder prevention = new StringBuilder();
        int n = 1;
        for (EquipmentHistoryRecaller.UnfinishedAction a : recall.unfinishedActions()) {
            if (n > 2) {
                break;
            }
            prevention.append(n++).append(". ").append(a.content())
                    .append(" (담당 ").append(nvl(a.owner(), SAFETY_MANAGER))
                    .append(", 기한 ").append(PreventionFormat.engineeringDue(occurredOn)).append(")\n");
        }
        prevention.append(n).append(". 같은 설비 사용 작업의 작업 전 안전점검표 재검토, 작업자 교육 (담당 ")
                .append(SAFETY_MANAGER).append(", 기한 ").append(PreventionFormat.adminDue(occurredOn)).append(')');

        return new Draft(cause, prevention.toString(), false);
    }

    private String nvl(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
