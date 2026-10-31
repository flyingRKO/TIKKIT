package com.tikkit.api.domain.performance.repository;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
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
     * Task 029에서 이걸 "좌표·등급"과 "상태" 두 쿼리로 쪼개 앞쪽만 캐시해 봤다. 응답 8필드 중
     * 7개가 불변이라 캐시할 수 있는 모양이긴 했다. 그런데 캐시를 꺼 두면 왕복이 1 → 2로 늘어
     * 좌석맵 p95가 6.63 → 8.62ms가 되고, Redis 캐시를 켜도 7.22ms로 원래보다 느렸다 —
     * 캐시 항목이 337,885바이트라 받아서 2,000개 record로 역직렬화하는 비용이 이 쿼리를 넘는다.
     * <b>적중률 99.97%에서도 그랬다.</b> 그래서 단일 쿼리로 되돌렸다
     * ({@code docs/improvements/008-caching.md} 4절).
     * <p>
     * Task 040이 이 쿼리의 비용 구성을 한 겹 더 벗겼다. 작업 집합을 {@code shared_buffers}
     * 밖으로 밀어(회차 600개, 183MB) 디스크 읽기를 요청당 9.8페이지로 만든 다음
     * {@code shared_buffers}를 512MB·1GB로 올려 <b>0으로 되돌렸는데 포화 처리량이 775 → 768 →
     * 766 req/s로 안 움직였다</b>(9회 산포 736~796 안). 커버링 인덱스
     * {@code (schedule_id) INCLUDE (seat_id, status, ticket_grade_id)}도 같은 결과다 — 요청당
     * 버퍼 접근을 1,409 → 189회(-86.6%)로 줄이는데 처리량 +3.1%가 산포 안이고, 그 대가가
     * 338MB와 HOT 파괴다. <b>이제 그 기각에는 "지금 규모에서"라는 조건이 붙지 않는다.</b>
     * <p>
     * 대신 포화 구간 CPU가 <b>postgres 400%(4.0코어) vs BE 2대 합계 271%</b>로 나왔다. 즉 이
     * 쿼리를 더 빠르게 하려면 줄여야 하는 것이 I/O나 버퍼 접근이 아니라 <b>Postgres CPU</b>이고,
     * 그건 2,000행 튜플 구성과 237KB 전송이다. Task 036이 로컬 캐시를 보더라도 "DB 조회가
     * 빨라져서"가 아니라 "Postgres CPU를 아예 안 쓰게 해서" 이득이 나오는 구조로 재야 한다
     * ({@code docs/improvements/012-shared-buffers-retrial.md} 4.3·4.4·4.6절).
     */
    @Override
    public List<ScheduleSeatResponse> findSeatResponsesByScheduleId(Long scheduleId) {
        return queryFactory
                .select(Projections.constructor(ScheduleSeatResponse.class,
                        scheduleSeat.id, seat.section, seat.rowLabel, seat.seatNumber,
                        seat.posX, seat.posY, scheduleSeat.status, scheduleSeat.ticketGrade.id))
                .from(scheduleSeat)
                .join(scheduleSeat.seat, seat)
                .where(scheduleSeat.schedule.id.eq(scheduleId))
                .orderBy(seat.posY.asc(), seat.posX.asc())
                .fetch();
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
