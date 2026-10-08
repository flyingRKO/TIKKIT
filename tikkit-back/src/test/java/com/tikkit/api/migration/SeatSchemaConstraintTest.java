package com.tikkit.api.migration;

import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V4가 만든 좌석 테이블의 제약이 실제로 데이터를 막는지 검증한다 (Task 021).
 *
 * <p>{@code .claude/skills/db-design/repository.md}가 요구하는 테스트다 — "복합 FK가 있는 테이블은
 * 두 경로가 어긋나는 데이터를 넣었을 때 실제로 막히는지 테스트한다". 좌석 레포지토리는 Task 022에
 * 생기므로 여기서는 {@link JdbcTemplate}으로 직접 INSERT를 날려 확인한다.
 *
 * <p>제약 위반은 메서드마다 한 건씩만 시도한다. PostgreSQL은 제약 위반 후 트랜잭션을 abort 상태로
 * 만들어 {@code current transaction is aborted}로 모든 후속 쿼리를 거부하므로, 한 메서드에서
 * 두 번 위반시킬 수 없다. 그래서 테스트가 잘게 쪼개져 있다.
 */
@Transactional
class SeatSchemaConstraintTest extends AbstractContainerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long venueId;
    private Long scheduleId;
    private Long otherScheduleId;
    private Long gradeId;
    private Long otherScheduleGradeId;
    private Long seatId;
    private Long otherSeatId;
    private Long scheduleSeatId;
    private Long reservationId;
    private Long cancelledReservationId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        venueId = insert("INSERT INTO venues (name, address) VALUES (?, ?) RETURNING id",
                "제약검증 공연장", "서울");
        Long performanceId = insert("""
                INSERT INTO performances (title, category, venue_id, running_minutes, age_rating, status)
                VALUES (?, 'CONCERT', ?, 120, '전체 관람가', 'ON_SALE') RETURNING id
                """, "제약검증 공연", venueId);

        scheduleId = insertSchedule(performanceId, now.plus(10, ChronoUnit.DAYS));
        otherScheduleId = insertSchedule(performanceId, now.plus(11, ChronoUnit.DAYS));
        gradeId = insertGrade(scheduleId);
        otherScheduleGradeId = insertGrade(otherScheduleId);

        seatId = insertSeat("VIP-중", "1", 1);
        otherSeatId = insertSeat("VIP-중", "1", 2);

        scheduleSeatId = insert("""
                INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status)
                VALUES (?, ?, ?, 'AVAILABLE') RETURNING id
                """, scheduleId, seatId, gradeId);

        Long member = insert("""
                INSERT INTO members (email, password, name, phone, role)
                VALUES (?, 'x', '검증용', '010-0000-0000', 'USER') RETURNING id
                """, "constraint@tikkit.com");
        reservationId = insertReservation("CT-CONF", member, "CONFIRMED");
        cancelledReservationId = insertReservation("CT-CANC", member, "CANCELLED");
    }

    @Test
    @DisplayName("같은 회차에 같은 좌석을 두 번 등록하면 UNIQUE 제약으로 막힌다")
    void 같은_회차에_같은_좌석을_두_번_넣으면_실패() {
        assertThatThrownBy(() -> insert("""
                INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status)
                VALUES (?, ?, ?, 'AVAILABLE') RETURNING id
                """, scheduleId, seatId, gradeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_schedule_seats_schedule_seat");
    }

    @Test
    @DisplayName("다른 회차의 등급을 가리키는 좌석은 복합 FK로 막힌다")
    void 회차와_등급이_어긋난_좌석은_거부된다() {
        // ticket_grades(id, schedule_id) 복합 UNIQUE를 참조하므로 등급과 회차가 함께 맞아야 한다
        assertThatThrownBy(() -> insert("""
                INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status)
                VALUES (?, ?, ?, 'AVAILABLE') RETURNING id
                """, scheduleId, otherSeatId, otherScheduleGradeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_schedule_seats_ticket_grade_schedule");
    }

    @Test
    @DisplayName("정의되지 않은 좌석 상태는 CHECK 제약으로 막힌다")
    void 정의되지_않은_좌석_상태는_거부된다() {
        // 제약 이름을 고정하지 않는다. 'RESERVED'는 status IN 목록(schedule_seats_status_check)과
        // 상태-점유자 CHECK(ck_schedule_seats_status_holder)를 동시에 위반하는데 — 세 값 중 어느
        // 것도 아니므로 holder의 두 분기를 모두 못 만족한다 — 어느 쪽을 보고할지는 Postgres가 정한다.
        assertThatThrownBy(() -> insert("""
                INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status, reservation_id)
                VALUES (?, ?, ?, 'RESERVED', ?) RETURNING id
                """, scheduleId, otherSeatId, gradeId, reservationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint");
    }

    @Test
    @DisplayName("점유 예약이 없는 SOLD 좌석은 상태-점유자 CHECK로 막힌다")
    void 점유자_없는_SOLD_좌석은_거부된다() {
        assertThatThrownBy(() -> insert("""
                INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status)
                VALUES (?, ?, ?, 'SOLD') RETURNING id
                """, scheduleId, otherSeatId, gradeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_schedule_seats_status_holder");
    }

    @Test
    @DisplayName("점유 예약이 있는 AVAILABLE 좌석도 상태-점유자 CHECK로 막힌다")
    void 점유자_있는_AVAILABLE_좌석은_거부된다() {
        // 반대 방향도 막아야 한다 — 선점이 풀렸는데 reservation_id가 남아 있으면 안 된다
        assertThatThrownBy(() -> insert("""
                INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status, reservation_id)
                VALUES (?, ?, ?, 'AVAILABLE', ?) RETURNING id
                """, scheduleId, otherSeatId, gradeId, reservationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_schedule_seats_status_holder");
    }

    @Test
    @DisplayName("같은 공연장에 같은 구역·열·번호의 좌석을 두 번 등록하면 막힌다")
    void 같은_공연장에_같은_좌석번호를_두_번_넣으면_실패() {
        assertThatThrownBy(() -> insertSeat("VIP-중", "1", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_seats_venue_section_row_number");
    }

    @Test
    @DisplayName("같은 예약에 같은 좌석을 두 번 기록하면 UNIQUE 제약으로 막힌다")
    void 같은_예약에_같은_좌석을_두_번_넣으면_실패() {
        // given
        insertReservationSeat(reservationId);

        // when & then
        assertThatThrownBy(() -> insertReservationSeat(reservationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_reservation_seats_reservation_seat");
    }

    @Test
    @DisplayName("다른 예약은 같은 좌석을 이력으로 가질 수 있다 (취소 이력 보존)")
    void 취소된_예약도_같은_좌석을_이력으로_가질_수_있다() {
        // given: schedule_seat_id 단독 UNIQUE를 두지 않은 설계의 의도를 확인한다
        insertReservationSeat(cancelledReservationId);

        // when & then
        assertThatCode(() -> insertReservationSeat(reservationId)).doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservation_seats WHERE schedule_seat_id = ?",
                Long.class, scheduleSeatId)).isEqualTo(2L);
    }

    private Long insertReservationSeat(Long targetReservationId) {
        return insert("""
                INSERT INTO reservation_seats (reservation_id, schedule_seat_id, price)
                VALUES (?, ?, 150000) RETURNING id
                """, targetReservationId, scheduleSeatId);
    }

    private Long insertSeat(String section, String rowLabel, int seatNumber) {
        return insert("""
                INSERT INTO seats (venue_id, section, row_label, seat_number, pos_x, pos_y)
                VALUES (?, ?, ?, ?, ?, 1) RETURNING id
                """, venueId, section, rowLabel, seatNumber, seatNumber);
    }

    private Long insertSchedule(Long performanceId, Instant showAt) {
        return insert("""
                INSERT INTO schedules (performance_id, show_at, booking_open_at, booking_close_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, performanceId, at(showAt), at(Instant.now().minus(1, ChronoUnit.DAYS)),
                at(showAt.minus(2, ChronoUnit.HOURS)));
    }

    private Long insertGrade(Long targetScheduleId) {
        return insert("""
                INSERT INTO ticket_grades (schedule_id, grade, price)
                VALUES (?, 'VIP', 150000) RETURNING id
                """, targetScheduleId);
    }

    private Long insertReservation(String reservationNo, Long memberId, String status) {
        return insert("""
                INSERT INTO reservations (reservation_no, member_id, schedule_id, ticket_grade_id,
                                          quantity, unit_price, total_amount, status)
                VALUES (?, ?, ?, ?, 1, 150000, 150000, ?) RETURNING id
                """, reservationNo, memberId, scheduleId, gradeId, status);
    }

    private Long insert(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
