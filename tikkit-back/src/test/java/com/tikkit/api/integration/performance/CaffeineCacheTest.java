package com.tikkit.api.integration.performance;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.domain.performance.service.PerformanceService;
import com.tikkit.api.domain.performance.service.ScheduleService;
import com.tikkit.api.domain.reservation.scheduler.ReservationExpiryScheduler;
import com.tikkit.api.support.AbstractConcurrencyTest;
import com.tikkit.api.support.QueryCounter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 캐시(Caffeine) 경로 검증 (Task 029 비교 실험군).
 *
 * <p>Redis 없이 돌기 때문에 컨테이너가 필요 없다. 그래서 {@code tikkit.cache.type=caffeine}만
 * 바꿔 {@link AbstractConcurrencyTest}를 그대로 쓴다.
 *
 * <h2>왜 Caffeine을 선택지로 뒀나</h2>
 * Spring Cache 추상화 덕에 {@code @Cacheable} 코드는 저장소를 모른다. 그래서 같은 코드로 로컬과
 * 공유 캐시를 재서 비교할 수 있다. 다만 이 서비스에서 로컬 캐시는 <b>구조적으로 맞지 않는다</b> —
 * 공연 파생값을 바꾸는 유일한 지점인 만료 배치가 {@code @SchedulerLock}으로 한 인스턴스에서만
 * 돌기 때문에, evict도 그 JVM에만 적용되고 다른 BE는 TTL 60초가 끝날 때까지 옛 {@code status}를
 * 내려준다.
 *
 * <p><b>그 불일치는 이 테스트로 잡을 수 없다</b> — JVM이 하나라서 "다른 인스턴스"가 없다.
 * 여기서는 로컬 캐시가 단일 인스턴스 기준으로 올바르게 동작하는지까지만 고정하고,
 * 2대 간 불일치는 compose로 BE 2대를 띄워 측정한다
 * ({@code docs/improvements/008-caching.md}). Task 026이 세션 불일치를 다룬 방식과 같다.
 */
@TestPropertySource(properties = "tikkit.cache.type=caffeine")
class CaffeineCacheTest extends AbstractConcurrencyTest {

    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ReservationExpiryScheduler scheduler;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private QueryCounter queryCounter;
    private Long performanceId;
    private Long scheduleId;

    @BeforeEach
    void setUpFixture() {
        queryCounter = new QueryCounter(entityManagerFactory);
        GradeWithSeats fixture = createOnSaleGradeWithSeats(4);
        scheduleId = fixture.scheduleId();
        performanceId = jdbcTemplate.queryForObject(
                "SELECT performance_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        // 로컬 캐시는 테스트 간 상태가 JVM에 남으므로 직접 비운다. Redis와 달리 clear()가
        // 즉시 반영되므로 폴링이 필요 없다 — 저장소를 바꾸면 사라지는 번거로움이기도 하다.
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("TTL이 캐시마다 달라 CaffeineCacheManager가 아니라 SimpleCacheManager로 조립된다")
    void 캐시_매니저_구성() {
        // CaffeineCacheManager는 매니저 단위로 spec 하나만 받는다. 목록(60초)과 좌석 배치(10분)의
        // TTL이 다르므로 캐시를 개별로 만들어 SimpleCacheManager에 담았다.
        assertThat(cacheManager).isInstanceOf(SimpleCacheManager.class);
        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrderElementsOf(CacheNames.TTLS.keySet());
    }

    @Test
    @DisplayName("Redis와 같은 쿼리 수를 낸다 — @Cacheable 코드는 저장소를 모른다")
    void 저장소를_바꿔도_쿼리수는_같다() {
        long detailMiss = queryCounter.count(() -> performanceService.getDetail(performanceId));
        long detailHit = queryCounter.count(() -> performanceService.getDetail(performanceId));
        long seatsMiss = queryCounter.count(() -> scheduleService.getSeats(scheduleId));
        long seatsHit = queryCounter.count(() -> scheduleService.getSeats(scheduleId));

        // CacheQueryCountTest(Redis)와 같은 값이어야 한다. 다르면 저장소가 동작에 새어든 것이다.
        assertThat(detailMiss).isEqualTo(2);
        assertThat(detailHit).isZero();
        assertThat(seatsMiss).isEqualTo(2);
        assertThat(seatsHit).isEqualTo(1);
    }

    @Test
    @DisplayName("recordStats()가 켜져 있어 적중률 지표가 나온다")
    void 적중률_지표_노출() {
        performanceService.getDetail(performanceId);
        performanceService.getDetail(performanceId);

        FunctionCounter hits = meterRegistry.find("cache.gets")
                .tag("cache", CacheNames.PERFORMANCE_DETAIL)
                .tag("result", "hit")
                .functionCounter();

        assertThat(hits).isNotNull();
        assertThat(hits.count()).isPositive();
    }

    @Test
    @DisplayName("배치 evict가 이 JVM의 캐시를 비운다 — 2대일 때 반쪽만 적용되는 게 문제다")
    void 배치_evict가_로컬_캐시를_비운다() {
        performanceService.getDetail(performanceId);
        assertThat(cacheManager.getCache(CacheNames.PERFORMANCE_DETAIL).get(performanceId)).isNotNull();

        jdbcTemplate.update("DELETE FROM shedlock");
        jdbcTemplate.update("""
                UPDATE schedules
                SET show_at = now() - interval '2 days',
                    booking_open_at = now() - interval '10 days',
                    booking_close_at = now() - interval '3 days'
                WHERE id = ?
                """, scheduleId);
        scheduler.run();

        // 이 인스턴스에서는 제대로 비워진다. 문제는 배치가 ShedLock으로 한 대에서만 돌아서
        // **다른 BE의 로컬 캐시에는 이 clear가 도달하지 않는다**는 것이다.
        assertThat(cacheManager.getCache(CacheNames.PERFORMANCE_DETAIL).get(performanceId)).isNull();
    }
}
