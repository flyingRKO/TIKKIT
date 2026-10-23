package com.tikkit.api.support;

import com.tikkit.api.domain.queue.repository.WaitingQueueRedisRepository;
import com.tikkit.api.domain.queue.scheduler.QueueAdmissionScheduler;
import com.tikkit.api.domain.queue.service.QueueFlagRegistry;
import com.tikkit.api.domain.queue.service.QueueGateway;
import com.tikkit.api.security.MemberDetails;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 대기열(Task 031)을 검증하는 테스트용 베이스.
 *
 * <p>{@link AbstractRedisCacheContainerTest}와 같은 구조이고 바꾸는 프로퍼티만 다르다 —
 * 세션은 {@code memory}, 캐시는 {@code none}으로 두고 <b>대기열만</b> Redis로 올린다.
 * 그래서 {@code RedisConnectionConfig}의 OR 조건에 Task 031이 추가한 세 번째 가지
 * ({@code QueueNeedsRedis})가 실제로 동작하는지도 이 베이스가 같이 증명한다.
 *
 * <p>캐시 베이스를 상속하지 않는 이유: 그쪽은 {@code tikkit.cache.type=redis}를 강제하고 키 공간
 * 분리 로직이 전부 Spring Cache 전용이다. 상속하면 대기열 테스트가 캐시 컨텍스트에 끼어들어
 * "캐시 없는 기준선"을 섞는다.
 *
 * <p>{@link AbstractConcurrencyTest}를 상속해 <b>비트랜잭션 + truncate + MockMvc</b> 환경을 쓴다.
 * 대기열 상태는 DB가 아니라 Redis에 있으므로 테스트 트랜잭션으로 롤백할 수 없고, 좌석 픽스처는
 * 실제로 커밋돼야 보호된 API가 200을 줄 수 있다.
 */
@TestPropertySource(properties = {
        // AbstractConcurrencyTest가 풀을 30/30으로 올려 둔 것을 되돌린다. 거기서는 100명이
        // 동시에 좌석을 집는 재현에 필요한 값이지만 대기열 테스트는 단일 스레드다.
        //
        // **줄이지 않으면 Postgres 커넥션이 고갈된다.** 프로퍼티 집합이 다르면 스프링 테스트
        // 컨텍스트가 하나 더 생기고, 그 풀은 실행이 끝날 때까지 닫히지 않는다. 기본
        // max_connections(100)를 넘기면 증상이 **대기열과 무관한 테스트**의
        // "FATAL: sorry, too many clients already"로 나타난다 (캐시 베이스가 같은 함정을 겪었다).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",

        // **Redis 세션 자동설정을 끈다.** 이걸 안 하면 대기열 테스트가 전부 401로 깨진다.
        //
        // 연결 팩토리가 생기면 Spring Session 자동설정의 @ConditionalOnBean(RedisConnectionFactory)이
        // 충족되어 세션이 Redis로 간다 — `tikkit.auth.session-store=memory`여도 그렇다.
        // 그러면 SessionRepositoryFilter가 요청마다 자기 세션을 만들고, MockMvc에
        // `.session(mockHttpSession)`으로 넘긴 세션은 **무시된다**. SecurityContext가 복원되지 않아
        // 인증이 필요한 요청이 401이 되고, 증상이 "대기열 보호가 403이 아니라 401을 준다"로만
        // 나타난다(RedisSessionAuthIntegrationTest Javadoc이 같은 사실을 반대편에서 적어뒀다 —
        // 그 테스트는 세션 저장을 검증하려고 일부러 쿠키를 들고 다닌다).
        //
        // 여기서 보려는 건 대기열이고 세션 저장소는 AbstractRedisSessionContainerTest가 따로
        // 검증하므로, 이 컨텍스트에서는 세션을 JVM 메모리에 둔다.
        //
        // 주의: 이 프로퍼티는 리스트라서 application.yml의 값을 **덮어쓴다.** 거기 있던 두 개를
        // 같이 적어야 한다 — 빼면 DataRedisAutoConfiguration이 되살아나 자동 팩토리가 생기고,
        // 그러면 세션을 끄려는 이 설정 자체가 무의미해진다.
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration,"
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration"
})
public abstract class AbstractRedisQueueContainerTest extends AbstractConcurrencyTest {

    private static final int REDIS_PORT = 6379;

    // 운영 compose와 같은 이미지 태그 (docker-compose.prod.yml의 redis 서비스).
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideQueueProperties(DynamicPropertyRegistry registry) {
        registry.add("tikkit.queue.enabled", () -> "true");
        // 입장 인원을 작게 둔다. 테스트는 "1틱에 정확히 N명"을 세는데 기본값 50이면
        // 픽스처를 51명 만들어야 경계를 볼 수 있다.
        registry.add("tikkit.queue.admit-batch-size", () -> ADMIT_BATCH_SIZE);
        // 플래그 스냅샷을 짧게. 이 값에 기대지 않고 invalidate()로 강제하지만, 테스트가
        // 늦게 켠 플래그도 결국 따라오게 해 둔다.
        registry.add("tikkit.queue.flag-cache-ttl", () -> "1s");
        // RedisConnectionConfig가 redis://host:port URI를 파싱한다.
        registry.add("tikkit.redis.address",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
    }

    /** 한 틱에 입장시킬 인원. {@code QueueAdmissionTest}가 경계를 이 값으로 계산한다. */
    protected static final int ADMIT_BATCH_SIZE = 3;

    @Autowired
    protected WaitingQueueRedisRepository queueRepository;

    @Autowired
    protected QueueFlagRegistry queueFlagRegistry;

    @Autowired
    protected QueueAdmissionScheduler queueAdmissionScheduler;

    @Autowired
    protected QueueGateway queueGateway;

    /**
     * 세션에 심어둔 {@code MemberDetails}에서 회원 id를 꺼낸다.
     * <p>
     * {@code createMembersWithSession()}이 세션만 돌려주는데, 대기열은 {@code memberId}로 줄을
     * 세우므로 두 값을 짝지어야 한다 — MockMvc로는 세션을, 게이트웨이 직접 호출로는 id를 쓴다.
     */
    protected static long memberIdOf(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return ((MemberDetails) context.getAuthentication().getPrincipal()).getMemberId();
    }

    /** 테스트마다 다른 회차 id 구간을 쓰기 위한 카운터. */
    private static final AtomicInteger KEY_SPACE = new AtomicInteger(1);

    /**
     * 테스트마다 <b>Redis 키 공간을 분리한다.</b>
     *
     * <p>{@code truncateAll()}의 {@code RESTART IDENTITY}가 매 테스트의 {@code schedules.id}를
     * 1부터 돌리므로, 키 {@code tikkit:queue:{1}:waiting}이 테스트마다 재사용된다. 대기열 상태는
     * truncate가 지우지 못하는 Redis에 있어서, 앞 테스트가 남긴 멤버가 다음 테스트의 순번에
     * 섞인다 — 증상은 "position이 1이어야 하는데 3이다"로 나타난다.
     *
     * <p>캐시 베이스가 검증한 기법을 그대로 쓴다: id 구간을 테스트마다 1,000씩 밀어 둔다.
     * {@link #deleteQueue}로 명시적으로도 지우지만, 그 삭제가 늦게 도착해도 <b>지난 테스트의 키만</b>
     * 건드리게 하는 것이 이 분리의 역할이다. {@code FLUSHDB}는 쓰지 않는다 — 같은 컨테이너를
     * 공유하는 다른 테스트 컨텍스트(캐시·세션)와 충돌한다.
     */
    @BeforeEach
    void isolateQueueKeySpace() {
        long base = KEY_SPACE.getAndIncrement() * 1_000L;
        jdbcTemplate.execute("ALTER TABLE schedules ALTER COLUMN id RESTART WITH " + base);
    }

    /**
     * 회차의 대기열을 켜고 스냅샷을 즉시 반영한다.
     *
     * <p>{@code invalidate()}를 같이 부르는 게 핵심이다. 안 부르면 {@link QueueFlagRegistry}가
     * TTL 동안 "꺼짐"을 들고 있어서 <b>테스트가 조용히 통과한다</b> — 보호를 검증하는 테스트가
     * 플래그 off 경로를 타고 200을 받아 초록색이 된다. 가장 위험한 실패 모드라 헬퍼로 묶었다.
     */
    protected void enableQueue(long scheduleId) {
        jdbcTemplate.update("UPDATE schedules SET queue_enabled = true WHERE id = ?", scheduleId);
        queueFlagRegistry.invalidate();
    }

    /** 회차의 대기열을 끈다. 플래그 off 경로를 검증할 때 쓴다. */
    protected void disableQueue(long scheduleId) {
        jdbcTemplate.update("UPDATE schedules SET queue_enabled = false WHERE id = ?", scheduleId);
        queueFlagRegistry.invalidate();
    }

    /** 해당 회차의 대기·활성 ZSET을 지운다. */
    protected void deleteQueue(long scheduleId) {
        queueRepository.deleteQueue(scheduleId);
    }
}
