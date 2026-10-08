package com.tikkit.api.domain.performance.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import com.tikkit.api.domain.venue.entity.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 회차별 좌석 재고. 지정석 전환 후 재고의 원천이다.
 * <p>
 * {@code TicketGrade}와 생명주기가 같은 회차 단위 자원이라 {@code domain/performance}에 둔다
 * (docs/ERD.md 2절). 전환 전에는 {@code ticket_grades.remaining_quantity} 숫자 한 칸이
 * 재고였고, 이제는 이 테이블의 {@code status} 분포가 재고다.
 * <p>
 * <b>상태를 바꾸는 메서드를 일부러 두지 않는다.</b> {@code TicketGrade}와 같은 이유다 (Task 019) —
 * 더티체킹 UPDATE가 조건부 UPDATE를 덮어쓰면 동시성 제어가 무력화되므로 그 경로를 아예 만들지 않는다.
 * 선점/해제/확정은 전부 {@code ScheduleSeatRepository}의 조건부 UPDATE만 담당한다.
 * <p>
 * {@code reservationId}를 {@code Reservation} 연관이 아니라 원시 {@code Long}으로 둔 이유:
 * 연관으로 두면 {@code domain/performance}가 {@code domain/reservation}을 import해서
 * 지금 한 방향인 의존이 순환이 된다. 상태 전이가 전부 네이티브 UPDATE라 연관 탐색이 필요 없고,
 * 좌석에서 예약을 거슬러 올라가는 조회는 {@code reservation_seats}를 경유한다.
 */
@Getter
@Entity
@Table(name = "schedule_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleSeat extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_grade_id", nullable = false)
    private TicketGrade ticketGrade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatStatus status;

    /** 현재 이 좌석을 점유한 예약. AVAILABLE이면 반드시 null이다 (ck_schedule_seats_status_holder). */
    @Column(name = "reservation_id")
    private Long reservationId;

    @Builder
    private ScheduleSeat(Schedule schedule, Seat seat, TicketGrade ticketGrade,
                         SeatStatus status, Long reservationId) {
        this.schedule = schedule;
        this.seat = seat;
        this.ticketGrade = ticketGrade;
        this.status = status;
        this.reservationId = reservationId;
    }
}
