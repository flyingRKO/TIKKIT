package com.tikkit.api.integration.performance;

import com.tikkit.api.domain.performance.service.PerformanceService;
import com.tikkit.api.domain.performance.service.ScheduleService;
import com.tikkit.api.support.AbstractConcurrencyTest;
import com.tikkit.api.support.QueryCounter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탐색·좌석맵 경로가 보내는 JDBC 문장 수를 고정한다 (Task 028).
 * <p>
 * 이 프로젝트에는 {@code @OneToMany}가 한 건도 없고 목록 응답이 전부 DTO 프로젝션이라
 * 구조적으로 N+1이 생기지 않는다. 그래서 이 테스트의 목적은 "N+1을 고쳤다"가 아니라
 * <b>"없는 상태를 고정한다"</b>다 — 나중에 누가 엔티티를 그대로 내보내거나 응답 DTO 안에서
 * 연관을 당겨 쓰면 숫자가 늘어나면서 여기서 깨진다.
 * <p>
 * {@code AbstractConcurrencyTest}를 베이스로 쓰는 이유는 동시성과 무관하다. 쿼리 수는
 * <b>트랜잭션 밖에서</b> 세야 하고(= 호출마다 세션이 새로 열려야 한다), 그 조건을 갖춘
 * 비트랜잭션 + truncate 베이스가 이것뿐이다 ({@link QueryCounter} Javadoc 참조).
 *
 * <p><b>여기 적힌 숫자는 캐시가 꺼진 상태({@code tikkit.cache.type=none}, test 프로필 기본값)의
 * 값이다</b> (Task 029). 캐시를 켜면 두 번째 호출부터 문장 수가 줄어드는데, 그쪽은
 * {@code CacheQueryCountTest}가 Redis 컨테이너를 띄워 미스 경로와 히트 경로를 따로 고정한다.
 * 두 테스트를 나눈 이유는 프로퍼티가 달라 스프링 컨텍스트가 어차피 분리되기 때문이다.
 */
class PerformanceQueryCountTest extends AbstractConcurrencyTest {

    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private QueryCounter queryCounter;
    private Long performanceId;
    private Long scheduleId;

    @BeforeEach
    void setUpFixture() {
        queryCounter = new QueryCounter(entityManagerFactory);
        GradeWithSeats fixture = createOnSaleGradeWithSeats(10);
        scheduleId = fixture.scheduleId();
        performanceId = jdbcTemplate.queryForObject(
                "SELECT performance_id FROM schedules WHERE id = ?", Long.class, scheduleId);
    }

    @Test
    @DisplayName("공연 목록 조회는 첫 페이지가 꽉 차지 않으면 count 쿼리를 생략해 1문장만 보낸다")
    void 공연_목록_count_생략() {
        // PageableExecutionUtils.getPage는 offset 0이고 content가 페이지 크기보다 작으면
        // 전체 건수를 content 크기로 확정할 수 있어 count 쿼리를 쏘지 않는다.
        long queries = queryCounter.count(() -> performanceService.list(null, null, "ON_SALE", 0, 20));

        assertThat(queries).isEqualTo(1);
    }

    @Test
    @DisplayName("공연 목록 조회는 페이지가 꽉 차면 content와 count 2문장을 보낸다")
    void 공연_목록_2문장() {
        // 페이지 크기를 1로 줄여 content가 페이지를 채우게 만든다 — 그래야 count가 실제로 나간다.
        long queries = queryCounter.count(() -> performanceService.list(null, null, "ON_SALE", 0, 1));

        assertThat(queries).isEqualTo(2);
    }

    @Test
    @DisplayName("공연 상세 조회는 공연장 fetch join 1문장과 회차 목록 1문장을 보낸다")
    void 공연_상세_2문장() {
        long queries = queryCounter.count(() -> performanceService.getDetail(performanceId));

        // venue.getName()/getAddress()를 읽지만 findByIdWithVenue가 fetch join해 둬서 추가 쿼리가 없다.
        assertThat(queries).isEqualTo(2);
    }

    @Test
    @DisplayName("등급 조회는 등급 행·잔여 좌석 COUNT 2문장을 보낸다 — 존재 확인이 빠졌다 (Task 029)")
    void 등급_조회_2문장() {
        long queries = queryCounter.count(() -> scheduleService.getTicketGrades(scheduleId));

        // Task 028까지는 3문장이었다(existsById + 등급 + COUNT). Task 029에서 등급을 먼저 조회하고
        // 결과가 비었을 때만 existsById로 404를 가르도록 뒤집어서, 정상 경로의 왕복이 하나 줄었다.
        // "없는 회차"와 "등급이 없는 회차"를 여전히 구분한다 — 확인 시점만 옮겼다.
        assertThat(queries).isEqualTo(2);
    }

    @Test
    @DisplayName("좌석맵 조회는 좌석 수와 무관하게 회차 존재 확인·좌석 2문장을 보낸다")
    void 좌석맵_2문장() {
        long queries = queryCounter.count(() -> scheduleService.getSeats(scheduleId));

        // 좌석이 10석이든 2,000석이든 문장 수는 같아야 한다 — 늘어나면 좌석마다 seat를 당겨 쓴 것이다.
        //
        // Task 029에서 이걸 (배치 + 상태) 두 쿼리로 쪼개 배치만 캐시해 히트 시 1문장으로 줄여
        // 봤지만 되돌렸다. 캐시 항목이 2,000석에 337,885바이트라 Redis 왕복·역직렬화가 쿼리보다
        // 비쌌다 — **문장 수가 줄어도 더 느릴 수 있다**(008 4절). 그래서 이 숫자는 Task 028과 같다.
        assertThat(queries).isEqualTo(2);
        assertThat(scheduleService.getSeats(scheduleId)).hasSize(10);
    }
}
