package com.tikkit.api.domain.performance.repository;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.entity.QScheduleSeat;
import com.tikkit.api.domain.performance.entity.SeatStatus;
import com.tikkit.api.domain.venue.entity.QSeat;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class ScheduleSeatRepositoryImpl implements ScheduleSeatRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    private static final QScheduleSeat scheduleSeat = QScheduleSeat.scheduleSeat;
    private static final QSeat seat = QSeat.seat;

    /**
     * {@code schedule_seats}에는 {@code idx(schedule_id, status)}가 없지만
     * {@code uk_schedule_seats_schedule_seat UNIQUE (schedule_id, seat_id)}의 선두 컬럼이
     * {@code schedule_id}라서 회차 범위는 그 인덱스로 좁혀진다. 인덱스를 일부러 안 둔 이유는
     * 선점·해제 UPDATE의 HOT 업데이트를 지키려는 것이다 (V4 주석, docs/ERD.md 2절).
     * <p>
     * Task 029에서 기존 {@code findSeatResponsesByScheduleId}를 이 메서드와
     * {@link #findSeatStatusesByScheduleId}로 쪼갰다. 응답 8필드 중 7개가 불변이라
     * 그쪽만 캐시하려는 것이다. 빠지는 비용은 {@code seats} 조인(Task 028이 Seq Scan 0.58ms로 실측)과
     * 2,000행 정렬(0.05ms)이고, 대가는 왕복이 1 → 2로 늘어나는 것이다.
     * <b>캐시가 꺼져 있으면 이 분리는 순손실이다</b> — 7절 수치로 판정한다.
     */
    @Override
    public List<ScheduleSeatLayoutRow> findSeatLayoutByScheduleId(Long scheduleId) {
        return queryFactory
                .select(Projections.constructor(ScheduleSeatLayoutRow.class,
                        scheduleSeat.id, seat.section, seat.rowLabel, seat.seatNumber,
                        seat.posX, seat.posY, scheduleSeat.ticketGrade.id))
                .from(scheduleSeat)
                .join(scheduleSeat.seat, seat)
                .where(scheduleSeat.schedule.id.eq(scheduleId))
                .orderBy(seat.posY.asc(), seat.posX.asc())
                .fetch();
    }

    /**
     * 상태만 읽는다. {@code seats} 조인도 정렬도 없다 — 호출 측이 캐시된 배치도 순서에 맞춰
     * 이 Map을 붙이므로 여기서 정렬할 이유가 없다.
     * <p>
     * {@code Tuple}을 밖으로 내보내지 않으려고 Impl 안에서 Map으로 접는다
     * (CLAUDE.md 프로젝션 규칙).
     */
    @Override
    public Map<Long, SeatStatus> findSeatStatusesByScheduleId(Long scheduleId) {
        List<Tuple> rows = queryFactory
                .select(scheduleSeat.id, scheduleSeat.status)
                .from(scheduleSeat)
                .where(scheduleSeat.schedule.id.eq(scheduleId))
                .fetch();

        return rows.stream().collect(Collectors.toMap(
                row -> row.get(scheduleSeat.id),
                row -> row.get(scheduleSeat.status)));
    }

    @Override
    public Map<Long, Integer> countAvailableByScheduleId(Long scheduleId) {
        // count()는 Long을 돌려주므로 Impl 안에서 Integer로 좁혀 내보낸다.
        // Tuple을 리포지토리 밖으로 노출하지 않는다 (CLAUDE.md 프로젝션 규칙).
        NumberExpression<Long> availableCount = scheduleSeat.count();
        List<Tuple> rows = queryFactory
                .select(scheduleSeat.ticketGrade.id, availableCount)
                .from(scheduleSeat)
                .where(scheduleSeat.schedule.id.eq(scheduleId),
                        scheduleSeat.status.eq(SeatStatus.AVAILABLE))
                .groupBy(scheduleSeat.ticketGrade.id)
                .fetch();

        return rows.stream().collect(Collectors.toMap(
                row -> row.get(scheduleSeat.ticketGrade.id),
                row -> row.get(availableCount).intValue()));
    }

    /**
     * 선점 UPDATE가 같은 조건을 WHERE에 다시 넣으므로 이 조회는 최종 방어선이 아니다.
     * 여기서 미리 보는 건 "없는 좌석"과 "이미 팔린 좌석"을 404와 409로 구분해 주기 위한 것이다.
     */
    @Override
    public int countMatching(List<Long> scheduleSeatIds, Long scheduleId, Long ticketGradeId) {
        Long matched = queryFactory
                .select(scheduleSeat.count())
                .from(scheduleSeat)
                .where(scheduleSeat.id.in(scheduleSeatIds),
                        scheduleSeat.schedule.id.eq(scheduleId),
                        scheduleSeat.ticketGrade.id.eq(ticketGradeId))
                .fetchOne();
        return matched == null ? 0 : matched.intValue();
    }
}
