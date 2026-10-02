package io.saife.form.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 법정 서식 뷰 모델.
 *
 * <p>템플릿이 조건 분기로 계산하지 않게, <b>표시할 문자열을 여기서 다 만들어 넘긴다.</b>
 * 서식은 법정 요건이라 화면 로직이 값을 바꾸면 안 된다.
 */
public final class FormViews {

    private FormViews() {}

    /**
     * 위험성평가표.
     *
     * <p>{@code rows}의 컬럼 순서가 <b>시행규칙 제37조의 3요소</b>다:
     * 유해·위험요인 / 위험성 결정 내용 / 조치 내용. 순서를 바꾸지 않는다.
     */
    public record AssessmentForm(String siteName,
                                 String businessNumber,
                                 String representative,
                                 String kindLabel,
                                 String triggerLabel,
                                 LocalDate assessedOn,
                                 String participants,
                                 String statusLabel,
                                 List<AssessmentRow> rows,
                                 String retentionNotice,
                                 String aiNotice) {}

    /**
     * @param hazard        ① 유해·위험요인
     * @param decision      ② 위험성 결정 내용 (등급 + 빈도·강도 + 판정 근거)
     * @param action        ③ 조치 내용
     * @param aiSuggested   AI가 후보로 올린 항목인가. 서식에 표시한다
     * @param actionGuideRef    감소대책의 근거 KOSHA GUIDE 번호. 없으면 null
     * @param actionCompletedAt 이행 완료 시각(KST, "yyyy-MM-dd HH:mm"). 미이행이면 null
     */
    public record AssessmentRow(int no,
                                String equipmentName,
                                String accidentLabel,
                                String hazard,
                                String decision,
                                String riskLabel,
                                String action,
                                String actionOwner,
                                LocalDate actionDueDate,
                                String actionStatusLabel,
                                boolean aiSuggested,
                                String aiAdoptedLabel,
                                String actionGuideRef,
                                String actionCompletedAt) {}

    /** 산업재해조사표 */
    public record IncidentForm(String siteName,
                               String businessNumber,
                               String representative,
                               String equipmentName,
                               String locationTag,
                               String occurredAt,
                               String victimName,
                               String severityLabel,
                               Integer leaveDays,
                               String accidentLabel,
                               String description,
                               String cause,
                               String prevention,
                               String reportStatusLabel,
                               LocalDate reportDueDate,
                               String reportBasis,
                               Long followUpAssessmentId,
                               List<String> recallLines,
                               String aiNotice) {}

    /**
     * @param references  createWorkPlan 시점에 대화 원장에서 붙은 근거 목록. "참고 자료" 절이 읽는다
     * @param warningNote 사고 연쇄(UC2)가 붙인 경고(R48). 없으면 null — 템플릿이 이 경우 절 자체를 감춘다
     */
    public record WorkPlanForm(String siteName,
                               String equipmentName,
                               String workName,
                               String workPlace,
                               LocalDate workDate,
                               String workHours,
                               String method,
                               String statusLabel,
                               String approvedBy,
                               String approvedAt,
                               String approvalNote,
                               String warningNote,
                               List<WorkerRow> workers,
                               List<SlotRow> slots,
                               String briefing,
                               String briefingAckAt,
                               String tbmNotice,
                               String aiNotice,
                               List<ReferenceRow> references) {}

    public record WorkerRow(int no, String name, String position, String duty) {}

    /** @param conflicted 대장 기록과 오늘 답변이 다르다. 서식에 그대로 남긴다 */
    public record SlotRow(String question, String ledgerValue, String answeredValue,
                          boolean conflicted) {}

    /**
     * "참고 자료" 목록 한 줄.
     *
     * @param no        원장 번호(#n)
     * @param sourceUrl 없을 수 있다(원문 링크가 없는 근거) — 그때는 링크 없이 제목만 표시한다
     * @param fetchedAt KST로 이미 변환된 문자열
     */
    public record ReferenceRow(int no, String title, String sourceUrl, String fetchedAt) {}
}
