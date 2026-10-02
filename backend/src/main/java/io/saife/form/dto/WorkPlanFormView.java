package io.saife.form.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 「작업 전 안전점검표 (TBM)」 서식 값. 템플릿은 계산하지 않고 이 값을 그대로 찍는다.
 *
 * @param documentTitle 작업 전 안전점검표 (TBM), 제38조 대상이면 작업계획서
 * @param documentBasis 서식 머리의 근거 표기
 * @param approvalNote  조건부 승인의 잠정조치. 없으면 "-"
 * @param warningNote   사고 연쇄가 붙인 작업 보류 사유. 없으면 null(절을 감춘다)
 * @param msdsLine      화학물질 한 줄. 없으면 null
 */
public record WorkPlanFormView(String documentTitle,
                               String documentBasis,
                               String siteName,
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
                               List<Worker> workers,
                               List<Check> checks,
                               List<Decision> decisions,
                               List<String> riskPoints,
                               List<String> keepPoints,
                               String msdsLine,
                               List<String> pendingActions,
                               String stopLine,
                               String briefingAckAt,
                               List<Reference> references) {

    public record Worker(int no, String name, String position) {}

    /** 현장 확인 항목: 항목, 오늘 확인 값(단위 포함), 대장 기록 */
    public record Check(String label, String value, String ledgerValue, boolean conflicted) {}

    /** 위험요인 판정: 발생형태, 등급(상/중/하), 근거, 개선대책 */
    public record Decision(String label, String grade, String gradeClass, String basis, String measure) {}

    /** 참고 자료: 번호, 제목, 원문 링크(없을 수 있음), 링크 문구 */
    public record Reference(int no, String title, String sourceUrl, String linkText) {}
}
