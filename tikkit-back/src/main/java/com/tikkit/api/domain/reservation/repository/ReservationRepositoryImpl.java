package com.tikkit.api.domain.reservation.repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikkit.api.domain.performance.entity.QPerformance;
import com.tikkit.api.domain.performance.entity.QSchedule;
import com.tikkit.api.domain.performance.entity.QTicketGrade;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.entity.QReservation;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class ReservationRepositoryImpl implements ReservationRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QReservation reservation = QReservation.reservation;
    private static final QSchedule schedule = QSchedule.schedule;
    private static final QPerformance performance = QPerformance.performance;
    private static final QTicketGrade ticketGrade = QTicketGrade.ticketGrade;

    @Override
    public Page<ReservationSummaryResponse> searchMine(Long memberId, ReservationStatus status, Pageable pageable) {
        BooleanExpression where = reservation.member.id.eq(memberId).and(statusEq(status));

        // idx_reservations_member_created_at(member_id, created_at desc) 인덱스를 의식한 정렬
        List<ReservationSummaryResponse> content = queryFactory
                .select(Projections.constructor(ReservationSummaryResponse.class,
                        reservation.id, reservation.reservationNo, performance.title, schedule.showAt,
                        ticketGrade.grade, reservation.quantity, reservation.totalAmount, reservation.status))
                .from(reservation)
                .join(reservation.schedule, schedule)
                .join(schedule.performance, performance)
                .join(reservation.ticketGrade, ticketGrade)
                .where(where)
                .orderBy(reservation.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // count 쿼리는 join 없이 — memberId/status가 reservation 테이블 컬럼이라 join이 필요 없다
        return PageableExecutionUtils.getPage(content, pageable, () -> queryFactory
                .select(reservation.count())
                .from(reservation)
                .where(where)
                .fetchOne());
    }

    @Override
    public Optional<Reservation> findMineWithDetails(Long id, Long memberId) {
        Reservation result = queryFactory
                .selectFrom(reservation)
                .join(reservation.schedule, schedule).fetchJoin()
                .join(schedule.performance, performance).fetchJoin()
                .join(reservation.ticketGrade, ticketGrade).fetchJoin()
                .where(reservation.id.eq(id), reservation.member.id.eq(memberId))
                .fetchOne();
        return Optional.ofNullable(result);
    }

    private BooleanExpression statusEq(ReservationStatus status) {
        return status != null ? reservation.status.eq(status) : null;
    }
}
