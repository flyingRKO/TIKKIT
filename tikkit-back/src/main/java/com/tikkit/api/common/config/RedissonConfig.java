package com.tikkit.api.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 클라이언트 빈 등록 (Task 020).
 * <p>
 * {@code tikkit.redis.enabled}가 true일 때만 빈을 만든다. 기본값이 false이므로 Redis가 떠 있지 않아도
 * 기동과 테스트가 그대로 돌아간다 — {@code redisson-spring-boot-starter}를 쓰지 않은 이유가 이것이다.
 * 스타터의 자동설정은 기동 시점에 즉시 연결을 시도하고 실패하면 컨텍스트 기동 자체를 깨뜨린다.
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
