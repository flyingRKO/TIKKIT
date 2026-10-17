package com.tikkit.api.integration.performance;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.domain.performance.service.CatalogCache;
import com.tikkit.api.domain.performance.service.PerformanceService;
import com.tikkit.api.support.AbstractRedisCacheContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시별 TTL이 실제로 Redis 키에 걸리는지 확인한다 (Task 029).
 *
 * <h2>왜 이 테스트가 따로 있나</h2>
 * TTL은 <b>조용히 안 걸린다.</b> 설정 코드만 읽으면 멀쩡해 보이는데 Redis에서 {@code PTTL}을
 * 찍어보면 {@code -1}(만료 없음)인 상태를 실제로 만들었다. 원인은
 * {@code RedisCacheManager.builder()}의 두 메서드를 같이 쓴 것이었다 —
 * {@code withInitialCacheConfigurations(perCache)}로 이름별 TTL을 준 뒤
 * {@code initialCacheNames(names)}를 부르면, 후자가 그 이름들을 <b>현재 {@code cacheDefaults}로</b>
 * 다시 만들면서 TTL을 날린다.
 * <p>
 * 이게 안 잡히면 "목록·상세는 TTL 60초"라고 문서에 적어두고 영구 캐시를 운영하게 된다.
 * 공연 파생값이 바뀌어도 evict가 실패하면 영원히 stale인 상태다 —
 * TTL은 그 evict의 안전망인데 안전망이 없는 걸 모르는 게 더 나쁘다.
 *
 * <p>캐시 매니저 설정을 보는 게 아니라 <b>Redis 키의 남은 수명</b>을 직접 읽는다. 설정을
 * assert하면 같은 함정을 다시 지나친다.
 */
class CacheTtlTest extends AbstractRedisCacheContainerTest {

    @Autowired
    private CatalogCache catalogCache;
    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    private StringRedisTemplate redis;
    private Long scheduleId;
    private Long performanceId;

    @BeforeEach
    void setUpFixture() {
        redis = new StringRedisTemplate(redisConnectionFactory);
        GradeWithSeats fixture = createOnSaleGradeWithSeats(2);
        scheduleId = fixture.scheduleId();
        performanceId = jdbcTemplate.queryForObject(
                "SELECT performance_id FROM schedules WHERE id = ?", Long.class, scheduleId);
    }

    @Test
    @DisplayName("공연 목록·상세는 배치 주기와 같은 60초 TTL이 걸린다")
    void 공연_캐시_TTL_60초() {
        performanceService.getDetail(performanceId);
        performanceService.list(null, null, "ON_SALE", 0, listPageSize);
        awaitCached(CacheNames.PERFORMANCE_DETAIL, performanceId);
        awaitCached(CacheNames.PERFORMANCE_LIST, listCacheKey());

        assertThat(ttlOf(CacheNames.PERFORMANCE_DETAIL, performanceId))
                .isPositive()
                .isLessThanOrEqualTo(60L);
        assertThat(ttlOf(CacheNames.PERFORMANCE_LIST, listCacheKey()))
                .isPositive()
                .isLessThanOrEqualTo(60L);
    }

    @Test
    @DisplayName("등급 가격·좌석 배치는 불변이라 10분 TTL이 걸린다")
    void 불변_캐시_TTL_10분() {
        catalogCache.gradePrices(scheduleId);
        catalogCache.seatLayout(scheduleId);
        awaitCached(CacheNames.GRADE_PRICES, scheduleId);
        awaitCached(CacheNames.SEAT_LAYOUT, scheduleId);

        long tenMinutes = Duration.ofMinutes(10).toSeconds();
        // 60초보다 크다는 것까지 확인한다 — 전부 cacheDefaults로 뭉개지면 네 캐시의 TTL이
        // 같아지는데, 값만 보면 "걸려 있다"로 통과할 수 있다.
        assertThat(ttlOf(CacheNames.GRADE_PRICES, scheduleId))
                .isGreaterThan(60L)
                .isLessThanOrEqualTo(tenMinutes);
        assertThat(ttlOf(CacheNames.SEAT_LAYOUT, scheduleId))
                .isGreaterThan(60L)
                .isLessThanOrEqualTo(tenMinutes);
    }

    /** {@code CacheConfig}의 키 접두사 규칙을 그대로 따라 Redis 키를 만들고 남은 수명을 읽는다. */
    private long ttlOf(String cacheName, Object key) {
        Long ttl = redis.getExpire("tikkit:cache:" + cacheName + "::" + key);
        assertThat(ttl)
                .as("키가 없으면 -2, 만료가 없으면 -1이 나온다")
                .isNotNull();
        return ttl;
    }
}
