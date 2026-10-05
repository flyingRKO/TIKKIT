package com.tikkit.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis가 필요한 동시성 테스트의 베이스 (Task 020 비교 실험 전용 — 실험이 끝나면 제거한다).
 * <p>
 * {@link AbstractContainerTest}에 Redis를 얹지 않고 이 클래스를 따로 둔 이유: 거기에 넣으면
 * <b>기존 테스트 전부가</b> Redis 컨테이너를 요구하게 된다. 이 클래스를 상속한 테스트가 로드될 때만
 * 컨테이너가 뜬다.
 * <p>
 * {@code org.testcontainers:redis} 같은 전용 모듈을 쓰지 않고 core의 {@link GenericContainer}로
 * 띄운다 — 포트 하나 노출하고 리스닝만 기다리면 되는 일에 의존성을 늘릴 이유가 없다.
 * <p>
 * {@code @DynamicPropertySource}는 클래스 계층 전체에서 수집되므로 부모의 데이터소스 주입도 함께 적용된다.
 * {@code tikkit.redis.enabled}를 여기서 켜기 때문에 {@code RedissonConfig}와
 * {@code DistributedLockAspect} 빈이 이 컨텍스트에서만 만들어진다.
 */
public abstract class AbstractRedisConcurrencyTest extends AbstractConcurrencyTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("tikkit.redis.enabled", () -> "true");
        registry.add("tikkit.redis.address",
                () -> "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
    }
}
