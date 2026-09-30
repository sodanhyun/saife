package io.saife.evidence.chunk;

/** 청킹 상수. 규칙 문서와 코드가 어긋나지 않게 여기 한 곳에만 둔다 */
public final class ChunkPolicy {
    private ChunkPolicy() {}
    public static final int CHILD_TARGET = 1200;      // 지침 child 목표 길이(자)
    public static final int CHILD_MIN = 300;          // 이보다 짧은 조각은 앞 조각에 합친다
    public static final int CHILD_MAX = 3000;         // 초과분은 CHILD_TARGET 단위로 재분할
    public static final int OVERLAP_CHARS = 200;      // 이전 청크 꼬리를 문장 경계에서 잘라 붙인다
    public static final int PARENT_MAX_CHILDREN = 5;  // 섹션 제목이 없을 때 parent 하나에 묶는 child 수
    public static final int COVER_MAX = 1200;         // 표지 청크(제목+1~2페이지) 최대 길이
    public static final String LEVEL_CHILD = "child";
    public static final String LEVEL_PARENT = "parent";
}
