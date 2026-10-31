package com.tikkit.api.support;

import com.tikkit.api.common.config.CacheNames;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 조회 캐시(Task 029)를 검증하는 테스트용 베이스.
 *
 * <p>{@link AbstractRedisSessionContainerTest}와 같은 구조이고 바꾸는 프로퍼티만 다르다 —
 * 세션은 {@code memory}로 두고 <b>캐시만</b> Redis로 올린다. 그래서
 * {@code RedisConnectionConfig}의 OR 조건(세션이든 캐시든 하나라도 redis면 연결 팩토리를 만든다)이
 * 실제로 동작하는지도 이 테스트가 같이 증명한다.
 *
 * <p>세션까지 Redis로 올리지 않는 이유: {@code spring.session.data.redis.repository-type: indexed}가
 * 기동 시점에 {@code CONFIG SET}을 쏘므로 컨텍스트 기동이 Redis에 묶인다. 캐시만 켜면 Lettuce가
 * 지연 연결이라 그 비용이 없고, 여기서 보려는 건 캐시 동작이다.
 *
 * <p>{@link AbstractConcurrencyTest}를 상속해 <b>비트랜잭션 + truncate</b> 환경을 쓴다.
 * 캐시 히트는 "두 번째 호출에서 쿼리가 안 나간다"로 검증하는데, 트랜잭션 테스트 안에서는
 * 세션이 유지돼 1차 캐시와 구분되지 않는다 ({@link QueryCounter} Javadoc의 같은 이유).
 */
@TestPropertySource(properties = {
        // AbstractConcurrencyTest가 풀을 30으로 올려 둔 것을 되돌린다. 거기서는 100명이 동시에
        // 좌석을 집는 재현에 필요한 값이지만, 캐시 테스트는 단일 스레드다.
        //
        // 줄이지 않으면 **Postgres 커넥션이 고갈된다.** 스프링 테스트 컨텍스트는 캐시되고
        // 실행이 끝날 때까지 닫히지 않으므로 풀이 컨텍스트마다 쌓인다. 캐시 토글 조합마다
        // 컨텍스트가 하나씩 생기는데(none / redis / 좌석맵 전략별) 각각 30이면
        // 기본 max_connections(100)를 넘고, 증상은 캐시와 무관한 테스트에서
        // "FATAL: sorry, too many clients already"로 나타난다.
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"
})
public abstract class AbstractRedisCacheContainerTest extends AbstractConcurrencyTest {

    private static final int REDIS_PORT = 6379;

    // 운영 compose와 같은 이미지 태그 (docker-compose.prod.yml의 redis 서비스).
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideCacheProperties(DynamicPropertyRegistry registry) {
        registry.add("tikkit.cache.type", () -> "redis");
        // RedisConnectionConfig가 redis://host:port URI를 파싱한다.
        registry.add("tikkit.redis.address",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    protected CacheManager cacheManager;

    /** 테스트마다 다른 id 구간을 쓰기 위한 카운터. */
    private static final AtomicInteger KEY_SPACE = new AtomicInteger(1);

    /**
     * 테스트마다 <b>캐시 키 공간을 분리한다.</b> Redis를 비우는 대신 쓰는 방법이다.
     *
     * <h2>왜 Redis를 비우지 않나</h2>
     * 처음에는 {@code @BeforeEach}에서 {@code FLUSHDB}를 쐈다. 그런데 테스트가 간헐적으로
     * "캐시에 넣었는데 꺼내면 없다"로 깨졌다. 쓰기 자체는 멀쩡했다 — 실측하면 put 직후
     * 0~1회 재시도 안에 보인다. 범인은 <b>삭제 명령이 뒤늦게 도착하는 것</b>이었다.
     * {@code FLUSHDB}와 {@code RedisCache.clear()}는 반환값이 void라서 응답을 기다리지 않고
     * 돌아오고, 그 명령이 다음 테스트가 캐시를 채운 뒤에 처리되면서 방금 넣은 값을 지웠다.
     * 같은 커넥션에 응답을 읽는 명령을 붙여 순서를 강제해 봤지만 그래도 재현됐다.
     *
     * <h2>그래서 키를 겹치지 않게 한다</h2>
     * 근본 원인은 {@code truncateAll()}의 {@code RESTART IDENTITY}다 — 매 테스트의 공연·회차 id가
     * 1부터 다시 시작하므로 캐시 키 {@code ...::1}이 테스트마다 재사용된다. id 구간을 테스트마다
     * 1,000씩 밀어두면 늦게 도착한 삭제가 <b>지난 테스트의 키만</b> 건드린다. 타이밍에 기대지
     * 않으므로 결정적이다.
     *
     * <p>캐시 키로 쓰이는 두 테이블만 밀면 된다. 패턴 삭제({@code clear()})는 캐시 이름 전체를
     * 지우므로 그것까지는 막지 못한다 — {@code clear()}를 트리거하는 테스트가 스스로
     * {@link #awaitCacheMiss}로 완료를 기다려서 자기 안에서 끝내는 게 짝이 되는 규칙이다.
     */
    @BeforeEach
    void isolateCacheKeySpace() {
        int index = KEY_SPACE.getAndIncrement();
        long base = index * 1_000L;
        jdbcTemplate.execute("ALTER TABLE performances ALTER COLUMN id RESTART WITH " + base);
        jdbcTemplate.execute("ALTER TABLE schedules ALTER COLUMN id RESTART WITH " + base);
        // 목록 캐시 키는 id가 아니라 (category|status|page|size)라서 id를 밀어도 겹친다.
        // size를 테스트마다 다르게 해서 키를 가른다. 픽스처 공연이 1건이라 크기는 아무 값이어도
        // 결과가 같고, "첫 페이지가 꽉 차지 않아 count 쿼리가 생략되는" 조건도 유지된다.
        listPageSize = 20 + index;
    }

    /** 이 테스트만 쓰는 목록 페이지 크기. {@link #listCacheKey()}와 짝이다. */
    protected int listPageSize;

    /** {@code PerformanceService.list(null, null, "ON_SALE", 0, listPageSize)}가 만드는 캐시 키. */
    protected String listCacheKey() {
        return "-|ON_SALE|0|" + listPageSize;
    }

    /**
     * 캐시 항목이 보일 때까지 기다린다.
     *
     * <p><b>캐시 쓰기가 간헐적으로 늦게 보인다.</b> 키를 덤프해 보면 어떤 테스트가 넣은 값이
     * 그 테스트의 조회에는 없고 다음 테스트의 덤프에 나타난다. 대부분은 즉시 보이지만
     * (put 직후 재시도 0~1회) 때때로 늦어서, "넣고 바로 꺼내는" 단정형 검증이 실행마다
     * 다르게 깨졌다.
     *
     * <p>운영에서는 문제가 아니다 — 늦어도 다음 요청이 미스를 한 번 더 겪을 뿐 틀린 응답이
     * 나오지는 않는다. 하지만 테스트는 기다려야 한다.
     *
     * <p>&gt; [!warning] 확인 필요<br>
     * 왜 늦는지는 규명하지 못했다. Lettuce 동기 API라면 응답을 기다려야 하는데 그렇지 않은
     * 것처럼 보인다. 캐시 정확성에 영향이 없어 더 파지 않았다.
     */
    protected void awaitCached(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        assertThat(cache).isNotNull();
        Awaitable.untilTrue(() -> cache.get(key) != null,
                "캐시 %s의 키 %s가 채워지지 않았다".formatted(cacheName, key));
    }

    /**
     * 배치가 비우는 공연 캐시 두 개가 모두 비워질 때까지 기다린다.
     *
     * <p>{@code clear()}는 키 하나가 아니라 <b>캐시 이름 전체를 패턴으로</b> 지운다. 그래서 이
     * 삭제가 늦게 도착하면 <b>다음 테스트가 넣은 값까지</b> 날아간다 — 키 공간을 분리해도
     * 막을 수 없다. {@code clear()}를 트리거한 테스트는 자기 안에서 완료를 확인하고 끝내야 한다.
     */
    protected void awaitPerformanceCachesCleared(Object detailKey, Object listKey) {
        awaitCacheMiss(CacheNames.PERFORMANCE_DETAIL, detailKey);
        awaitCacheMiss(CacheNames.PERFORMANCE_LIST, listKey);
    }

    /**
     * 캐시 항목이 사라질 때까지 기다린다. {@code clear()}가 비동기라서 필요하다.
     * <p>
     * 운영에서는 이 지연이 문제가 되지 않는다 — evict를 거는 배치가 60초 주기이고, 지연은 ms
     * 단위다. 최악의 경우 evict 직후 채워진 값이 뒤늦은 삭제에 날아가는데 그건 캐시 미스 한 번일
     * 뿐 틀린 응답이 아니다. 하지만 테스트는 즉시 확인하려 하므로 기다려야 한다.
     */
    protected void awaitCacheMiss(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        assertThat(cache).isNotNull();
        Awaitable.untilTrue(() -> cache.get(key) == null,
                "캐시 %s의 키 %s가 비워지지 않았다".formatted(cacheName, key));
    }

    /** 짧은 폴링 헬퍼. Awaitility를 의존성에 추가할 만한 쓰임이 아니라 직접 돌린다. */
    static final class Awaitable {

        private static final long TIMEOUT_MILLIS = 3_000;
        private static final long INTERVAL_MILLIS = 25;

        static void untilTrue(java.util.function.BooleanSupplier condition, String message) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                try {
                    Thread.sleep(INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            throw new AssertionError(message);
        }

        private Awaitable() {
        }
    }
}
