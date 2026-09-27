package com.tikkit.api.domain.reservation.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Getter
@Entity
@Table(name = "reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    /** 선점(홀드) 유지 시간. docs/PRD.md 비즈니스 규칙 참조. */
    public static final Duration HOLD_DURATION = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String reservationNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_grade_id", nullable = false)
    private TicketGrade ticketGrade;

    // DB 컬럼이 smallint라 SqlTypes.SMALLINT로 명시해야 ddl-auto=validate가 int4/int2 불일치로 실패하지 않는다.
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    private Instant expiresAt;

    private Instant confirmedAt;

    private Instant cancelledAt;

    @Builder
    private Reservation(String reservationNo, Member member, Schedule schedule, TicketGrade ticketGrade,
                         Integer quantity, BigDecimal unitPrice, BigDecimal totalAmount, ReservationStatus status,
                         Instant expiresAt, Instant confirmedAt, Instant cancelledAt) {
        this.reservationNo = reservationNo;
        this.member = member;
        this.schedule = schedule;
        this.ticketGrade = ticketGrade;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = totalAmount;
        this.status = status;
        this.expiresAt = expiresAt;
        this.confirmedAt = confirmedAt;
        this.cancelledAt = cancelledAt;
    }

    /** 선점(홀드) 예약을 생성한다. 단가는 생성 시점의 등급 가격을 스냅샷으로 저장한다. */
    public static Reservation createPending(String reservationNo, Member member, TicketGrade ticketGrade,
                                              int quantity, Instant now) {
        BigDecimal unitPrice = ticketGrade.getPrice();
        return Reservation.builder()
                .reservationNo(reservationNo)
                .member(member)
                .schedule(ticketGrade.getSchedule())
                .ticketGrade(ticketGrade)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .totalAmount(unitPrice.multiply(BigDecimal.valueOf(quantity)))
                .status(ReservationStatus.PENDING)
                .expiresAt(now.plus(HOLD_DURATION))
                .build();
    }
}
