package io.saife.workplan.domain;

/**
 * 작업 전에 남기는 문서의 종류.
 *
 * <p>사다리 천장 도장처럼 안전보건규칙 제38조 작업계획서 대상(13개 작업)이 아닌 작업은
 * 「작업 전 안전점검표(TBM)」로 남긴다. 근거는 제42조(추락의 방지), 법 제36조제4항(위험성평가 결과의
 * 근로자 공유), 고시 제13조다. 제38조 대상 작업(차량계 하역운반기계, 중량물 취급 등)이면 같은 흐름에서
 * 문서 이름만 「작업계획서」로 갈린다.
 *
 * @param type       TBM_CHECKLIST 또는 WORK_PLAN
 * @param title      서식과 화면 제목
 * @param shortTitle 진행 표시처럼 좁은 자리에 쓰는 이름
 * @param basis      서식 머리의 근거 표기
 */
public record WorkDocument(String type, String title, String shortTitle, String basis) {

    public static final String TBM_CHECKLIST = "TBM_CHECKLIST";
    public static final String WORK_PLAN = "WORK_PLAN";

    private static final WorkDocument CHECKLIST = new WorkDocument(TBM_CHECKLIST,
            "작업 전 안전점검표 (TBM)", "점검표",
            "산업안전보건기준에 관한 규칙 제42조, 산업안전보건법 제36조제4항, 사업장 위험성평가에 관한 지침 제13조");

    private static final WorkDocument PLAN = new WorkDocument(WORK_PLAN,
            "작업계획서", "작업계획서",
            "산업안전보건기준에 관한 규칙 제38조 (사전조사 및 작업계획서의 작성 등)");

    /** 제38조 제1항 각 호의 작업을 가리키는 말. 사다리, 도장은 여기에 없다 */
    private static final String ARTICLE_38_SIGNAL =
            ".*(지게차|차량계|하역운반|중량물|크레인|타워크레인|굴착|터널|해체|교량|채석|구축물|전기 ?작업|궤도|화학설비).*";

    public static WorkDocument of(String workName, String equipmentName) {
        String text = (workName == null ? "" : workName) + " " + (equipmentName == null ? "" : equipmentName);
        return text.matches(ARTICLE_38_SIGNAL) ? PLAN : CHECKLIST;
    }
}
