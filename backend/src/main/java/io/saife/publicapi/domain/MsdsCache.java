package io.saife.publicapi.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * MSDS 캐시.
 *
 * <p>MSDS만 건별 호출이라 쿼터를 먹는 유일한 API다. 필요분만 받아 고정한다.
 * UC3가 쓰는 건 4개 항목뿐이다 — 02(유해성) 05(폭발화재) 07(취급저장) 08(노출방지·보호구).
 * 물질당 4콜. 16콜을 다 받지 않는다.
 *
 * <p>itemDetail은 '|'가 줄 구분자다.
 */
@Entity
@Table(name = "msds_cache")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class MsdsCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chem_id", nullable = false, length = 20)
    private String chemId;

    @Column(name = "chem_name_kor", length = 300)
    private String chemNameKor;

    @Column(name = "cas_no", length = 40)
    private String casNo;

    @Column(name = "un_no", length = 20)
    private String unNo;

    /** 02 / 05 / 07 / 08 */
    @Column(name = "section_code", nullable = false, length = 10)
    private String sectionCode;

    /** msdsItemCode */
    @Column(name = "item_code", length = 20)
    private String itemCode;

    /** msdsItemNameKor */
    @Column(name = "item_name", length = 300)
    private String itemName;

    @Column(name = "item_detail", columnDefinition = "text")
    private String itemDetail;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @PrePersist
    void onCreate() {
        this.fetchedAt = OffsetDateTime.now();
    }
}
