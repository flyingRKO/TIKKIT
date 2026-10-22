package com.tikkit.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 세션 전략(Task 026)을 검증하는 테스트용 베이스.
 *
 * <p><b>왜 {@link AbstractContainerTest}에 Redis를 넣지 않았나</b><br>
 * 그러면 통합 테스트 129개 전부가 Redis 기동을 기다리게 되고, "Redis 없이도 기동·테스트가
 * 돌아야 한다"는 기존 결정({@code docs/improvements/003-redis-distributed-lock.md} 3절)이 깨진다.
 * 기본 전략은 memory이므로 평소 테스트는 Redis를 아예 모른다. 이 클래스를 상속한 테스트만
 * 컨테이너를 띄우고 {@code tikkit.auth.session-store=redis}로 전환한다.
 *
 * <p>프로퍼티가 다르므로 스프링 테스트 컨텍스트는 별도로 하나 더 만들어진다 — 의도한 비용이다.
 * 같은 컨텍스트를 쓰면 전략을 바꿀 수 없다.
 */
public abstract class AbstractRedisSessionContainerTest extends AbstractContainerTest {

    private static final int REDIS_PORT = 6379;

    // 운영 compose와 같은 이미지 태그를 쓴다 (docker-compose.prod.yml의 redis 서비스).
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4-alpine").withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideSessionProperties(DynamicPropertyRegistry registry) {
        registry.add("tikkit.auth.session-store", () -> "redis");
        // RedisConnectionConfig가 redis://host:port URI를 파싱한다.
        registry.add("tikkit.redis.address",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
    }
}
