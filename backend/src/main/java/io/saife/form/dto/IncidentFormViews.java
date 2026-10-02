package io.saife.form.dto;

import java.util.List;

/**
 * 사고 서식 뷰 모델 두 종.
 *
 * <ul>
 *   <li>{@link ReportForm}: 제출용 「산업재해조사표」. 시행규칙 별지 제30호서식 항목 순서를 그대로 따른다.
 *       사람 정보(성명, 주민등록번호 등)와 사업자등록번호, 산재관리번호는 빈 작성란으로 둔다.</li>
 *   <li>{@link ReviewForm}: 사내 「재발방지 검토서」(제출하지 않음). 사고 전 지적 사항의 이행 현황,
 *       재발방지 대책(누가, 언제까지, 무엇을), 수시평가 연결을 담는다.</li>
 * </ul>
 *
 * <p>템플릿이 계산하지 않도록 표시 문자열을 여기서 다 만든다. 화면과 출력물이 다른 값을 말하면 안 된다.
 */
public final class IncidentFormViews {

    private IncidentFormViews() {}

    /**
     * 산업재해조사표(별지 제30호서식).
     *
     * @param workerCount   근로자 수. 대장 값이 없으면 빈 문자열(작성란)
     * @param industry      업종
     * @param leaveDays     휴업예상일수. 미입력이면 빈 문자열
     * @param fatal         사망 여부
     * @param injuryType    상해 종류(질병명). 미입력이면 빈 문자열(작성란)
     * @param injuryPart    상해 부위(질병 부위). 미입력이면 빈 문자열(작성란)
     * @param occurredDate  "2026-10-02"
     * @param occurredTime  "10:20"
     * @param place         발생장소 (위치, 설비)
     * @param workType      재해관련 작업유형. 연결된 작업 전 점검이 없으면 빈 문자열
     * @param situation     재해발생 당시 상황
     * @param cause         재해발생 원인 (근거 번호 제거)
     * @param plans         재발방지 계획 줄
     * @param dueLabel      화면 상단(인쇄 안 됨) 제출 기한 안내. 제출 대상이 아니면 판단 근거
     */
    public record ReportForm(String siteName,
                             String workerCount,
                             String industry,
                             String siteAddress,
                             String leaveDays,
                             boolean fatal,
                             String injuryType,
                             String injuryPart,
                             String occurredDate,
                             String occurredTime,
                             String place,
                             String workType,
                             String situation,
                             String cause,
                             List<PlanRow> plans,
                             String dueLabel) {}

    /**
     * 재발방지 대책 한 줄 — "무엇을 (담당 누가, 기한 언제까지)" 형식을 가른 것.
     * 형식을 못 읽으면 {@code what}에 줄 전체가 들어가고 {@code who}·{@code when}은 빈 문자열이다.
     *
     * @param refs 근거 각주 번호. 조사표에서는 비운다
     */
    public record PlanRow(int no, String what, String who, String when, List<Integer> refs) {}

    /**
     * 사내 재발방지 검토서 (비제출).
     *
     * @param title 서식 제목. 아차사고면 "아차사고 기록"
     */
    public record ReviewForm(String title,
                             String siteName,
                             String equipmentName,
                             String place,
                             String occurredAt,
                             String accidentLabel,
                             String severityLabel,
                             String leaveDaysLabel,
                             String situation,
                             String reportLabel,
                             List<PriorRow> priorRows,
                             List<PlanRow> plans,
                             String followUpLabel,
                             List<RegradeRow> regradeRows,
                             List<HoldRow> holdRows,
                             List<FootnoteRow> footnotes) {}

    /**
     * 사고 전 지적 사항 한 줄. 위험요인과 그 감소대책, 사고 시점의 이행 상태.
     *
     * @param elapsedLabel 사고 시점 기준 "30일 경과" / "기한 전" / "이행 완료" / ""
     * @param overdue      사고 시점에 기한이 지난 미이행 대책
     */
    public record PriorRow(String assessedOn,
                           String accidentLabel,
                           String hazard,
                           String riskLabel,
                           String riskClass,
                           String action,
                           String owner,
                           String dueDate,
                           String statusLabel,
                           String elapsedLabel,
                           boolean overdue,
                           boolean sameAxis) {}

    /** 수시평가 재판정 한 줄 */
    public record RegradeRow(String accidentLabel, String hazard, String beforeLabel,
                             String afterLabel, String riskClass, String trace) {}

    /** 작업 보류된 작업 전 점검 한 줄 */
    public record HoldRow(String workDate, String workName, String statusLabel) {}

    /** 각주. {@code url}이 없으면 제목만 */
    public record FootnoteRow(int no, String title, String url) {}
}
