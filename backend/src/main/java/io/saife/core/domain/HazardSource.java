package io.saife.core.domain;

/** 위험요인이 어디서 나왔는지. 데이터 코어의 연결성을 드러내는 필드다. */
public enum HazardSource {
    PHOTO,       // UC1 사진 판독
    NEAR_MISS,   // 아차사고 보고
    INCIDENT,    // 산재 발생 후 도출
    WORK_PLAN,   // UC3 작업계획 수립 중 발견
    MANUAL       // 사람이 직접 등록
}
