package com.tikkit.api.domain.reservation.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import com.tikkit.api.domain.performance.entity.ScheduleSeat;
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

import java.math.BigDecimal;

/**
 * 예약이 어떤 좌석을 어떤 가격에 받았는지 남기는 이력.
 * <p>
 * 예약 소유라서 {@code domain/reservation}에 둔다 (docs/ERD.md 2절). append-only다 —
 * 예약이 취소돼도 행을 지우지 않고 {@code schedule_seats}의 상태만 되돌린다. 그래서
 * 같은 좌석이 여러 예약 행에 등장하는 게 정상이고, {@code schedule_seat_id} 단독 UNIQUE가 없다.
 * <p>
 * 이 테이블이 "예약 → 좌석"을 찾는 유일한 경로이기도 하다. {@code schedule_seats.reservation_id}에는
 * HOT 업데이트를 지키려고 인덱스를 걸지 않았으므로, 좌석 확정·해제는
 * {@code uk_reservation_seats_reservation_seat}의 선두 컬럼을 타고 여기를 경유한다.
 * <p>
 * {@code price}는 결제 시점의 등급 단가 스냅샷이라 한 예약 안에서 전부 같다. 좌석별 차등 가격은
 * 범위 밖이다.
 */
@Getter
@Entity
@Table(name = "reservation_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSeat extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_seat_id", nullable = false)
    private ScheduleSeat scheduleSeat;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal price;

    @Builder
    private ReservationSeat(Reservation reservation, ScheduleSeat scheduleSeat, BigDecimal price) {
        this.reservation = reservation;
        this.scheduleSeat = scheduleSeat;
        this.price = price;
    }
}
