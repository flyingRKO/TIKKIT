package com.tikkit.api.integration.performance;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.service.PerformanceService;
import com.tikkit.api.domain.performance.service.ScheduleService;
import com.tikkit.api.domain.reservation.scheduler.ReservationExpiryScheduler;
import com.tikkit.api.support.AbstractRedisCacheContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공연 파생값이 바뀌면 캐시가 비는지 고정한다 (Task 029).
 *
 * <p>이 서비스에는 공연 데이터를 바꾸는 쓰기 API가 없다 — 엔티티에 setter가 없고 관리자 API도
 * 없다. 그래서 캐시 무효화 지점이 하나뿐이다: 만료 배치가 60초마다 돌리는
 * {@code recalculateDerivedFields}가 {@code performances.status}/{@code start_date}/
 * {@code end_date}를 다시 계산하고, 그 세 컬럼이 목록·상세 응답에 들어 있다.
 *
 * <p>evict가 없으면 판매가 끝난 공연이 TTL 60초 동안 {@code ON_SALE}로 보인다. 이 테스트는
 * 그 60초를 기다리지 않고 배치를 직접 호출해 확인한다.
 */
class CacheEvictionTest extends AbstractRedisCacheContainerTest {

    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ReservationExpiryScheduler scheduler;
    // cacheManager는 AbstractRedisCacheContainerTest가 protected로 들고 있다.

    private Long performanceId;
    private Long scheduleId;

    @BeforeEach
    void setUpFixture() {
        GradeWithSeats fixture = createOnSaleGradeWithSeats(2);
        scheduleId = fixture.scheduleId();
        performanceId = jdbcTemplate.queryForObject(
                "SELECT performance_id FROM schedules WHERE id = ?", Long.class, scheduleId);

        // ShedLock 락 기록을 지운다. 이게 없으면 두 번째 테스트부터 배치가 조용히 건너뛰어진다 —
        // @SchedulerLock(lockAtLeastFor = "55s")가 정상 종료 후에도 55초간 락을 들고 있고,
        // AbstractConcurrencyTest.truncateAll()의 테이블 목록에 shedlock이 없어서 남는다.
        // 증상이 "evict가 안 됐다"로 나타나서 캐시 문제로 오진하기 쉽다.
        jdbcTemplate.update("DELETE FROM shedlock");
        // 캐시 키가 테스트마다 겹치지 않도록 AbstractRedisCacheContainerTest가 id 구간을 민다.
    }

    @Test
    @DisplayName("파생값이 바뀐 배치 실행은 공연 목록·상세 캐시를 비운다")
    void 파생값_변경시_공연_캐시_비움() {
        // given: 상세를 조회해 캐시에 올린다
        assertThat(performanceService.getDetail(performanceId).status()).isEqualTo(PerformanceStatus.ON_SALE);
        performanceService.list(null, null, "ON_SALE", 0, listPageSize);
        awaitCached(CacheNames.PERFORMANCE_DETAIL, performanceId);
        awaitCached(CacheNames.PERFORMANCE_LIST, listCacheKey());

        // when: 회차를 과거로 옮겨 판매가 끝난 상태로 만든 뒤 배치를 돌린다.
        // 배치의 recalculateDerivedFields가 status를 CLOSED로 바꾸고 변경 행 수를 1로 돌려준다.
        jdbcTemplate.update("""
                UPDATE schedules
                SET show_at = now() - interval '2 days',
                    booking_open_at = now() - interval '10 days',
                    booking_close_at = now() - interval '3 days'
                WHERE id = ?
                """, scheduleId);
        scheduler.run();

        // then: 두 캐시가 비었다.
        // RedisCache.clear()가 즉시 반영되지 않아 폴링한다 — 그 이유는
        // AbstractRedisCacheContainerTest.awaitCacheMiss() Javadoc에 적어뒀다.
        awaitPerformanceCachesCleared(performanceId, listCacheKey());
        // 그리고 다시 조회하면 바뀐 상태가 보인다 (TTL 60초를 기다리지 않는다)
        assertThat(performanceService.getDetail(performanceId).status()).isNotEqualTo(PerformanceStatus.ON_SALE);
    }

    @Test
    @DisplayName("파생값이 그대로면 캐시를 건드리지 않는다 — 멀쩡한 캐시를 매분 날리지 않는다")
    void 파생값_불변시_캐시_유지() {
        // given: 먼저 배치를 한 번 돌려 파생값을 확정한다.
        //
        // 픽스처가 만드는 공연은 start_date/end_date가 NULL이다(회차에서 계산되는 값이라
        // 엔티티에서 채우지 않는다). 그래서 **첫 배치는 항상 값을 바꾼다** — 이 단계를 빼고
        // 짰다가 "안 바뀌었는데 evict됐다"로 계속 깨졌다. 운영에서는 시드/배치가 이미 돌아서
        // 정착된 상태가 정상이다.
        jdbcTemplate.update("DELETE FROM shedlock");
        scheduler.run();
        awaitPerformanceCachesCleared(performanceId, listCacheKey());

        performanceService.getDetail(performanceId);
        awaitCached(CacheNames.PERFORMANCE_DETAIL, performanceId);

        // when: 아무것도 바뀌지 않은 상태에서 배치를 다시 돌린다.
        // recalculateDerivedFields는 IS DISTINCT FROM으로 실제 변경 행만 UPDATE하므로 0을 돌려준다.
        jdbcTemplate.update("DELETE FROM shedlock");
        scheduler.run();

        // then: 캐시가 남아 있다. 매번 비우면 배치 주기마다 적중률이 0으로 떨어진다.
        awaitCached(CacheNames.PERFORMANCE_DETAIL, performanceId);
    }

    @Test
    @DisplayName("좌석 배치 캐시는 공연 캐시가 비는 배치에서도 살아남는다 — 불변이라 비울 이유가 없다")
    void 불변_캐시는_유지() {
        // given: 좌석 배치와 공연 상세를 모두 캐시에 올린다
        scheduleService.getSeats(scheduleId);
        performanceService.getDetail(performanceId);
        awaitCached(CacheNames.SEAT_LAYOUT, scheduleId);

        // when: 파생값이 실제로 바뀌는 배치를 돌린다 (공연 캐시는 비워지는 조건)
        jdbcTemplate.update("""
                UPDATE schedules
                SET show_at = now() - interval '2 days',
                    booking_open_at = now() - interval '10 days',
                    booking_close_at = now() - interval '3 days'
                WHERE id = ?
                """, scheduleId);
        scheduler.run();

        // then: 공연 캐시는 비었지만 좌석 배치는 남아 있다.
        // 좌석 좌표는 공연 판매 상태와 무관하다 — 같이 날리면 2,000행 조회를 공짜로 다시 하게 된다.
        awaitPerformanceCachesCleared(performanceId, listCacheKey());
        awaitCached(CacheNames.SEAT_LAYOUT, scheduleId);
    }
}
