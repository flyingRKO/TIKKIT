package com.tikkit.api.migration;

import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 백필이 등급별 수량 재고를 좌석으로 손실 없이 옮기는지 검증한다 (Task 021).
 *
 * <p>테스트 프로필은 {@code db/migration}만 로드하므로(application-test.yml) Flyway가 V5를 실행하는
 * 시점의 DB는 비어 있다. 즉 <b>기동 시의 V5 실행에는 검증할 데이터가 없다</b>. 그래서 이 테스트는
 * 마이그레이션 전 모양의 픽스처를 직접 넣고 V5 스크립트를 클래스패스에서 읽어 그대로 재실행한다.
 *
 * <p>엔티티 대신 {@link JdbcTemplate}을 쓰는 이유: Task 021에는 좌석 엔티티가 없고(Task 022에서 만든다),
 * 검증 대상이 원시 SQL과 스키마 자체라 Hibernate의 변환이 끼면 DDL·백필 SQL을 직접 확인할 수 없다.
 *
 * <p>싱글턴 컨테이너를 모든 테스트가 공유하므로 다른 테스트의 잔류 데이터가 있을 수 있다.
 * 모든 단정은 픽스처가 만든 회차로 범위를 좁힌다.
 */
@Transactional
class SeatBackfillMigrationTest extends AbstractContainerTest {

    /**
     * UTF-8을 명시해야 한다 — V5의 블록 이름('좌','중','우')이 한글 문자열 리터럴이라
     * 플랫폼 기본 인코딩으로 읽으면 section 값이 깨진다.
     */
    private static final EncodedResource BACKFILL_SCRIPT = new EncodedResource(
            new ClassPathResource("db/migration/V5__backfill_seats.sql"), StandardCharsets.UTF_8);

    private static final int VIP_TOTAL = 30;
    private static final int VIP_REMAINING = 24;   // 판매됨 6 = CONFIRMED 4 + PENDING 2
    private static final int R_TOTAL = 80;
    private static final int R_REMAINING = 77;     // 판매됨 3 = CONFIRMED 3

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    private Long venueId;
    private Long scheduleId;
    private Long vipGradeId;
    private Long rGradeId;
    private Long vipConfirmedId;
    private Long vipPendingId;
    private Long rConfirmedId;
    private Long vipCancelledId;
    private Long vipExpiredId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        venueId = insert("INSERT INTO venues (name, address) VALUES (?, ?) RETURNING id",
                "백필검증 공연장", "서울");
        Long performanceId = insert("""
                INSERT INTO performances (title, category, venue_id, running_minutes, age_rating, status)
                VALUES (?, 'CONCERT', ?, 120, '전체 관람가', 'ON_SALE') RETURNING id
                """, "백필검증 공연", venueId);
        scheduleId = insert("""
                INSERT INTO schedules (performance_id, show_at, booking_open_at, booking_close_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, performanceId, at(now.plus(10, ChronoUnit.DAYS)), at(now.minus(1, ChronoUnit.DAYS)),
                at(now.plus(10, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS)));

        vipGradeId = insertGrade("VIP", 150_000, VIP_TOTAL, VIP_REMAINING);
        rGradeId = insertGrade("R", 99_000, R_TOTAL, R_REMAINING);

        // V3_2의 부분 유니크 인덱스가 (member_id, ticket_grade_id) WHERE status='PENDING'이라
        // 같은 등급의 PENDING은 회원을 다르게 넣어야 한다.
        Long member1 = insertMember("backfill1@tikkit.com");
        Long member2 = insertMember("backfill2@tikkit.com");
        Long member3 = insertMember("backfill3@tikkit.com");

        vipConfirmedId = insertReservation("BF-VIP-CONF", member1, vipGradeId, 4, 150_000,
                "CONFIRMED", null, now);
        // 만료 시각이 이미 지난 PENDING. 건너뛰면 "SOLD+HELD == total - remaining"이 깨지므로
        // 백필은 이것도 HELD로 만들어야 한다.
        vipPendingId = insertReservation("BF-VIP-PEND", member2, vipGradeId, 2, 150_000,
                "PENDING", now.minus(5, ChronoUnit.MINUTES), now.plus(1, ChronoUnit.SECONDS));
        rConfirmedId = insertReservation("BF-R-CONF", member1, rGradeId, 3, 99_000,
                "CONFIRMED", null, now.plus(2, ChronoUnit.SECONDS));
        // 재고를 이미 반납한 예약들 — 좌석을 받으면 안 된다.
        vipCancelledId = insertReservation("BF-VIP-CANC", member3, vipGradeId, 1, 150_000,
                "CANCELLED", null, now.plus(3, ChronoUnit.SECONDS));
        vipExpiredId = insertReservation("BF-VIP-EXP", member3, vipGradeId, 1, 150_000,
                "EXPIRED", now.minus(1, ChronoUnit.HOURS), now.plus(4, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("등급별 점유 좌석 수가 백필 전 판매 수량(total - remaining)과 일치한다")
    void 등급별_판매좌석_건수가_기존_재고와_일치() {
        // when
        runBackfill();

        // then
        assertThat(occupiedSeats(vipGradeId)).isEqualTo(VIP_TOTAL - VIP_REMAINING);
        assertThat(occupiedSeats(rGradeId)).isEqualTo(R_TOTAL - R_REMAINING);
        // 픽스처 수치에 의존하지 않는 형태로도 확인한다.
        assertThat(mismatchedGrades()).isEmpty();
    }

    @Test
    @DisplayName("등급별 좌석 행 수가 총 판매 수량과 일치한다 (그리드 올림 여분이 새어들지 않는다)")
    void 등급별_좌석_총건수가_총수량과_일치() {
        // when
        runBackfill();

        // then
        assertThat(seatRows(vipGradeId)).isEqualTo(VIP_TOTAL);
        assertThat(seatRows(rGradeId)).isEqualTo(R_TOTAL);
    }

    @Test
    @DisplayName("확정 예약의 좌석은 SOLD, 선점 예약의 좌석은 HELD가 된다")
    void 확정예약은_SOLD_선점예약은_HELD() {
        // when
        runBackfill();

        // then: 점유 건수만 보면 두 상태가 뒤바뀌어도 통과하므로 따로 센다
        assertThat(seatsInStatus(vipGradeId, "SOLD")).isEqualTo(4);
        assertThat(seatsInStatus(vipGradeId, "HELD")).isEqualTo(2);
        assertThat(seatsInStatus(rGradeId, "SOLD")).isEqualTo(3);
        assertThat(seatsInStatus(rGradeId, "HELD")).isZero();
    }

    @Test
    @DisplayName("예약마다 배정된 좌석 수가 예매 매수와 같다")
    void 예약별_좌석_건수가_매수와_일치() {
        // when
        runBackfill();

        // then
        assertThat(seatsOf(vipConfirmedId)).isEqualTo(4);
        assertThat(seatsOf(vipPendingId)).isEqualTo(2);
        assertThat(seatsOf(rConfirmedId)).isEqualTo(3);
    }

    @Test
    @DisplayName("예약의 좌석 가격 합계가 결제 금액과 일치한다")
    void 좌석_가격합이_결제금액과_일치() {
        // when
        runBackfill();

        // then
        assertThat(count("""
                SELECT count(*) FROM reservations r
                WHERE r.schedule_id = ?
                  AND EXISTS (SELECT 1 FROM reservation_seats rs WHERE rs.reservation_id = r.id)
                  AND r.total_amount <> (SELECT sum(rs.price) FROM reservation_seats rs
                                         WHERE rs.reservation_id = r.id)
                """, scheduleId)).isZero();
    }

    @Test
    @DisplayName("취소·만료된 예약은 좌석을 배정받지 않는다")
    void 취소_만료_예약은_좌석을_받지_않는다() {
        // when
        runBackfill();

        // then
        assertThat(seatsOf(vipCancelledId)).isZero();
        assertThat(seatsOf(vipExpiredId)).isZero();
        assertThat(count("""
                SELECT count(*) FROM schedule_seats ss
                WHERE ss.schedule_id = ? AND ss.reservation_id IN (?, ?)
                """, scheduleId, vipCancelledId, vipExpiredId)).isZero();
    }

    @Test
    @DisplayName("한 좌석을 활성 예약 두 건이 동시에 점유하지 않는다")
    void 한_좌석은_활성예약_하나만_점유() {
        // when
        runBackfill();

        // then
        assertThat(jdbcTemplate.queryForList("""
                SELECT rs.schedule_seat_id
                FROM reservation_seats rs
                         JOIN reservations r ON r.id = rs.reservation_id
                WHERE r.schedule_id = ? AND r.status IN ('CONFIRMED', 'PENDING')
                GROUP BY rs.schedule_seat_id
                HAVING count(*) > 1
                """, scheduleId)).isEmpty();
    }

    @Test
    @DisplayName("좌석의 공연장이 회차 공연의 공연장과 일치한다")
    void 좌석의_공연장이_회차의_공연장과_일치() {
        // when
        runBackfill();

        // then: schedule_seats → venue는 3홉이라 복합 FK로 표현할 수 없어 DB가 보장하지 못한다
        assertThat(count("""
                SELECT count(*) FROM schedule_seats ss
                         JOIN seats st ON st.id = ss.seat_id
                         JOIN schedules sc ON sc.id = ss.schedule_id
                         JOIN performances p ON p.id = sc.performance_id
                WHERE ss.schedule_id = ? AND st.venue_id <> p.venue_id
                """, scheduleId)).isZero();
    }

    @Test
    @DisplayName("한 예약에 배정된 좌석은 모두 같은 등급이다")
    void 한_예약의_좌석은_모두_같은_등급() {
        // when
        runBackfill();

        // then: "한 예약 = 한 등급" 정책
        assertThat(jdbcTemplate.queryForList("""
                SELECT rs.reservation_id
                FROM reservation_seats rs
                         JOIN reservations r ON r.id = rs.reservation_id
                         JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id
                WHERE r.schedule_id = ?
                GROUP BY rs.reservation_id
                HAVING count(DISTINCT ss.ticket_grade_id) > 1
                """, scheduleId)).isEmpty();
    }

    @Test
    @DisplayName("백필을 두 번 실행해도 결과가 달라지지 않는다")
    void 백필을_두_번_실행해도_결과가_같다() {
        // given
        runBackfill();
        Map<String, Object> first = snapshot();

        // when: 운영 재실행 안전성이기도 하지만, 위 테스트들이 이 스크립트를 재실행하는 전제이기도 하다
        runBackfill();

        // then
        assertThat(snapshot()).isEqualTo(first);
    }

    /**
     * 실제로 배포되는 V5 스크립트를 그대로 다시 돌린다.
     * 테스트 안에 백필 SQL을 복사해두면 운영 스크립트와 조용히 달라져서 검증이 무의미해진다.
     *
     * <p>{@link DataSourceUtils#getConnection}을 쓰는 것이 핵심이다 — {@code dataSource.getConnection()}을
     * 직접 부르면 테스트 트랜잭션 밖의 별도 커넥션을 받아서 (1) 아직 커밋되지 않은 픽스처가 보이지 않고
     * (2) 스크립트가 쓴 데이터가 롤백되지 않고 다음 테스트로 새어나간다.
     */
    private void runBackfill() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, BACKFILL_SCRIPT);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private Map<String, Object> snapshot() {
        return jdbcTemplate.queryForMap("""
                SELECT (SELECT count(*) FROM seats WHERE venue_id = ?)                        AS seats,
                       (SELECT count(*) FROM schedule_seats WHERE schedule_id = ?)            AS schedule_seats,
                       (SELECT count(*) FROM schedule_seats
                         WHERE schedule_id = ? AND status = 'SOLD')                           AS sold,
                       (SELECT count(*) FROM schedule_seats
                         WHERE schedule_id = ? AND status = 'HELD')                           AS held,
                       (SELECT count(*) FROM reservation_seats rs JOIN reservations r
                          ON r.id = rs.reservation_id WHERE r.schedule_id = ?)                AS reservation_seats
                """, venueId, scheduleId, scheduleId, scheduleId, scheduleId);
    }

    /** 픽스처 수치와 무관하게 불변식이 깨진 등급을 찾는다. */
    private List<Map<String, Object>> mismatchedGrades() {
        return jdbcTemplate.queryForList("""
                SELECT tg.grade
                FROM ticket_grades tg
                         LEFT JOIN schedule_seats ss ON ss.ticket_grade_id = tg.id
                WHERE tg.schedule_id = ?
                GROUP BY tg.id, tg.grade, tg.total_quantity, tg.remaining_quantity
                HAVING tg.total_quantity - tg.remaining_quantity
                           <> count(ss.id) FILTER (WHERE ss.status IN ('SOLD', 'HELD'))
                """, scheduleId);
    }

    private long occupiedSeats(Long gradeId) {
        return count("SELECT count(*) FROM schedule_seats WHERE ticket_grade_id = ? "
                + "AND status IN ('SOLD', 'HELD')", gradeId);
    }

    private long seatsInStatus(Long gradeId, String status) {
        return count("SELECT count(*) FROM schedule_seats WHERE ticket_grade_id = ? AND status = ?",
                gradeId, status);
    }

    private long seatRows(Long gradeId) {
        return count("SELECT count(*) FROM schedule_seats WHERE ticket_grade_id = ?", gradeId);
    }

    private long seatsOf(Long reservationId) {
        return count("SELECT count(*) FROM reservation_seats WHERE reservation_id = ?", reservationId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private Long insertGrade(String grade, int price, int total, int remaining) {
        return insert("""
                INSERT INTO ticket_grades (schedule_id, grade, price, total_quantity, remaining_quantity)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, scheduleId, grade, price, total, remaining);
    }

    private Long insertMember(String email) {
        return insert("""
                INSERT INTO members (email, password, name, phone, role)
                VALUES (?, 'x', '검증용', '010-0000-0000', 'USER') RETURNING id
                """, email);
    }

    private Long insertReservation(String reservationNo, Long memberId, Long gradeId, int quantity,
                                   int unitPrice, String status, Instant expiresAt, Instant createdAt) {
        return insert("""
                INSERT INTO reservations (reservation_no, member_id, schedule_id, ticket_grade_id,
                                          quantity, unit_price, total_amount, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, reservationNo, memberId, scheduleId, gradeId, quantity, unitPrice,
                (long) unitPrice * quantity, status, at(expiresAt), at(createdAt));
    }

    private Long insert(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
