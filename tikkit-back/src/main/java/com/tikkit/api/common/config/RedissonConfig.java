package com.tikkit.api.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 클라이언트 빈 등록 (Task 020에서 도입).
 *
 * <h2>지금 이 빈을 주입받는 곳이 없다 — 왜 남겨뒀나</h2>
 * Task 020은 Redis 분산 락이 단일 DB 환경에서 필요한지 재보는 실험이었고, 결론은 "불필요"였다
 * ({@code docs/improvements/003-redis-distributed-lock.md}). 그래서 {@code @DistributedLock} AOP와
 * 측정 코드는 지웠지만 <b>Redis 연결 설정은 남긴다</b> — Task 029(Spring Cache 캐싱)와
 * Task 031(ZSET 대기열)에서 다시 쓸 인프라이고, 로드맵에도 그렇게 잡혀 있다.
 * {@code tikkit.redis.enabled}가 기본 false이므로 이 빈은 평소 만들어지지 않는다.
 *
 * <h2>왜 redisson-spring-boot-starter가 아닌가</h2>
 * 스타터의 자동설정은 {@code Redisson.create()}로 기동 시점에 즉시 연결하고, 실패하면 컨텍스트
 * 기동 자체가 깨진다. Redis를 띄우지 않는 환경(CI의 backend 잡, 평소 로컬 개발)에서 테스트와
 * {@code bootRun}이 전부 막힌다. 스타터는 {@code redisson-spring-data-NN}도 함께 끌어오는데
 * 접미사가 Spring Boot 버전이 아니라 Spring Data Redis 라인을 따라가서 버전을 수동으로 맞춰야
 * 한다. Task 029에서 Spring Cache가 필요해지면 그때 {@code spring-boot-starter-data-redis}를
 * 정식으로 추가하면 된다.
 * <p>
 * 조건을 {@code @ConditionalOnBean}이 아니라 {@code @ConditionalOnProperty}로 거는 이유: 전자는
 * 빈 정의 등록 순서에 의존해서 컴포넌트 스캔으로 올라오는 클래스에 붙이면 결과를 신뢰할 수 없다.
 */
@Configuration
@ConditionalOnProperty(prefix = "tikkit.redis", name = "enabled", havingValue = "true")
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${tikkit.redis.address}") String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }
}
