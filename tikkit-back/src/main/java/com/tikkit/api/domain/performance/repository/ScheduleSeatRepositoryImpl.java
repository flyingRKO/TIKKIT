package com.tikkit.api.domain.performance.repository;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.entity.QScheduleSeat;
import com.tikkit.api.domain.performance.entity.SeatStatus;
import com.tikkit.api.domain.venue.entity.QSeat;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
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

    /**
     * 위 단일 쿼리에서 {@code status}를 뺀 것 (Task 036). 캐시 대상이다.
     *
     * <p>조인과 정렬은 그대로 둔다 — 빠지는 건 {@code schedule_seats.status} 컬럼 하나뿐이다.
     * 전송 바이트는 좌석당 몇 바이트 줄지만 <b>행 수는 같다.</b> 040이 특정한 벽이 "2,000행 튜플
     * 구성과 237KB 전송"이었으므로, 이 쿼리만으로는 그 비용이 거의 안 줄어든다. 줄어드는 것은
     * <b>캐시 히트로 이 쿼리를 아예 안 돌릴 때</b>다. 그게 이 Task가 보려는 전부다.
     *
     * <p>짝인 {@link #findSeatStatusesByScheduleId}도 2,000행을 꺼내므로 <b>히트 경로에도 2,000행
     * 조회가 하나 남는다.</b> 상태를 전부 가져오는 대신 AVAILABLE이 아닌 좌석만 꺼내면 행 수가
     * 수십~수백으로 줄지만, 그러면 029가 쟀던 구조와 달라져 008 4.1절 수치와 직접 비교가 안 된다.
     * <b>036은 먼저 같은 구조로 재서 warning을 닫고, 델타 조회는 그 결과를 보고 판단한다.</b>
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
     * 좌석 상태만 읽는다 (Task 036). 캐시하지 않는다.
     *
     * <p>{@code seats} 조인이 없고 정렬도 하지 않는다 — 순서는 캐시된 배치가 들고 있고, 이쪽은
     * id로 찾아 쓰는 맵이라 순서가 의미 없다. 그래서 커버링 인덱스 없이도
     * {@code uk_schedule_seats_schedule_seat}의 선두 컬럼으로 회차 범위가 좁혀진다.
     */
    @Override
    public Map<Long, SeatStatus> findSeatStatusesByScheduleId(Long scheduleId) {
        List<Tuple> rows = queryFactory
                .select(scheduleSeat.id, scheduleSeat.status)
                .from(scheduleSeat)
                .where(scheduleSeat.schedule.id.eq(scheduleId))
                .fetch();

        // Tuple을 리포지토리 밖으로 내보내지 않는다 (CLAUDE.md 프로젝션 규칙).
        // HashMap 초기 용량을 주는 이유: 2,000건이면 기본 용량 16에서 8번 리해시한다.
        Map<Long, SeatStatus> statuses = new HashMap<>(Math.max(16, rows.size() * 2));
        for (Tuple row : rows) {
            statuses.put(row.get(scheduleSeat.id), row.get(scheduleSeat.status));
        }
        return statuses;
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
