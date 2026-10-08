package com.tikkit.api.domain.venue.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공연장의 물리 좌석. 회차와 무관한 마스터 데이터다.
 * <p>
 * 좌석이 공연장 소유라서 {@code domain/venue}에 둔다 (docs/ERD.md 2절). 회차별 판매 상태는
 * {@code ScheduleSeat}이 따로 들고, 이 엔티티는 "몇 구역 몇 열 몇 번이 어디에 있는가"만 안다.
 * <p>
 * {@code rowLabel}이 문자열인데 실제로는 숫자가 들어간다(1, 2, ... 23). 국내 예매처 관행이
 * "3열 12번"이라 알파벳을 쓰지 않지만, 공연장마다 "A열"이나 "BOX"가 섞일 수 있어 타입은 열어둔다.
 * {@code seatNumber}는 구역마다 1번부터 다시 시작한다.
 */
@Getter
@Entity
@Table(name = "seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false, length = 10)
    private String section;

    @Column(nullable = false, length = 5)
    private String rowLabel;

    @Column(nullable = false)
    private Integer seatNumber;

    /** 배치도 가로 좌표. 구역 사이 통로만큼 값이 비어 있다. */
    // posX를 그냥 두면 Spring 네이밍 전략이 posx로 바꾼다 — 단어 끝 대문자 한 글자 앞에는
    // 언더스코어를 넣지 않기 때문이다(CamelCaseToUnderscoresNamingStrategy). 컬럼명을 명시한다.
    @Column(name = "pos_x", nullable = false)
    private Integer posX;

    /** 배치도 세로 좌표. 작을수록 무대에 가깝다. */
    @Column(name = "pos_y", nullable = false)
    private Integer posY;

    @Builder
    private Seat(Venue venue, String section, String rowLabel, Integer seatNumber,
                 Integer posX, Integer posY) {
        this.venue = venue;
        this.section = section;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.posX = posX;
        this.posY = posY;
    }
}
