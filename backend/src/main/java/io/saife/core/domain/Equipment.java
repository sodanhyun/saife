package io.saife.core.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설비 — 이 출품작의 모든 이력이 걸리는 축.
 *
 * <p><b>중복 레코드가 생기면 안 된다.</b> 자연어 매칭이 false negative를 내서
 * 같은 설비가 두 ID로 쪼개지면 "하나의 설비 ID"라는 논지 자체가 무너지고,
 * UC4 타임라인 뷰가 눈에 띄게 깨진다.
 *
 * <p>방어는 3단이다: ① 정규화 명칭 완전일치 ② 유사도 히트 시 되묻기
 * ③ <b>DB 유니크 제약 {@code uq_equipment_identity}</b>. 앱 로직만 믿지 않는다.
 */
@Entity
@Table(name = "equipment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Equipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Column(name = "process_id")
    private Long processId;

    @Column(nullable = false, length = 200)
    private String name;

    /** 공백 제거 + 소문자화. 중복 방지 유니크 키의 일부 */
    @Column(name = "normalized_name", nullable = false, length = 200)
    private String normalizedName;

    @Column(name = "location_tag", length = 200)
    private String locationTag;

    /** 공단 사업장기계기구 대상물코드 */
    @Column(name = "object_code", length = 50)
    private String objectCode;

    @Column(name = "introduced_on")
    private LocalDate introducedOn;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    /** 명칭 정규화 — 매칭과 유니크 제약이 같은 규칙을 써야 한다 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\s+", "").toLowerCase();
    }
}
