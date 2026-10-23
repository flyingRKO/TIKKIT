package com.tikkit.api.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Lettuce 연결 팩토리. 세션(Task 026)·조회 캐시(Task 029)·대기열(Task 031)이 공유한다.
 *
 * <h2>왜 자동설정을 쓰지 않고 직접 만드나</h2>
 * {@code spring-boot-starter-session-data-redis}는 {@code spring-boot-starter-data-redis}를 끌고 오고,
 * 그 자동설정({@code DataRedisAutoConfiguration})은 프로퍼티가 없어도 localhost:6379를 기본값으로
 * {@code RedisConnectionFactory}를 만든다. 그러면 Spring Session 자동설정의
 * {@code @ConditionalOnBean(RedisConnectionFactory.class)}가 항상 충족되어
 * <b>세션 저장소를 끌 방법이 없어진다</b>. Boot 4에는 {@code spring.session.store-type} 같은
 * 저장소 선택 프로퍼티가 없고(스타터를 넣는 것 자체가 선택이다) 비활성화 스위치도 없다.
 * <p>
 * 그래서 {@code application.yml}에서 {@code DataRedisAutoConfiguration}을 <b>항상 제외</b>하고
 * 이 설정이 조건부로 팩토리를 만든다. Spring Session 쪽 {@code @ConditionalOnBean}이 그걸
 * 따라오므로 프로퍼티 하나로 전체가 켜지고 꺼진다.
 *
 * <h2>왜 조건이 OR인가 (Task 029에서 바뀐 부분)</h2>
 * Task 026에서는 이 빈을 {@code SessionRedisConfig}가 {@code tikkit.auth.session-store=redis}일 때만
 * 만들었다. 캐시가 들어오면서 조건이 하나 더 필요해졌다 — 로컬 개발에서
 * {@code session-store=memory} + {@code cache.type=redis} 조합을 써야 하기 때문이다(세션까지 Redis로
 * 올리면 통합 테스트가 Redis를 기다린다). Task 031에서 대기열 조건이 세 번째로 붙었다.
 * 셋 중 <b>하나라도</b> Redis를 요구하면 팩토리를 만든다.
 * <p>
 * {@code @ConditionalOnProperty}는 여러 개를 붙여도 AND로 묶이므로 {@link AnyNestedCondition}으로
 * OR를 만든다. 중첩 클래스마다 조건을 하나씩 달고, 하나라도 맞으면 통과한다.
 *
 * <h2>Redis가 없어도 기동되는가</h2>
 * Lettuce는 지연 연결이라 팩토리를 만드는 것만으로는 접속하지 않는다. 그래서 Task 020의 Redisson
 * 스타터가 겪던 "Redis 없으면 컨텍스트가 깨진다"({@code docs/improvements/003-redis-distributed-lock.md}
 * 3절)는 재발하지 않고, 대신 첫 접근에서 실패한다.
 * <p>
 * 단 {@code spring.session.data.redis.repository-type: indexed}는 예외다 — 기동 시점에
 * {@code CONFIG SET}으로 keyspace notification을 켜므로 Redis가 없으면 기동이 깨진다.
 * indexed를 고른 이유는 강제 로그아웃(특정 회원의 세션 전부 찾아 삭제)이 indexed 전용 API라서다.
 * <b>이 비용은 세션을 Redis로 둘 때만 발생한다</b> — 캐시만 Redis로 켜면 지연 연결 그대로다.
 *
 * <h2>주소 프로퍼티는 용도별로 나누지 않는다</h2>
 * {@code tikkit.redis.address}는 {@code redis://host:port} URI 형식이라(Task 020의 Redisson
 * 설정에서 온 형식이다) Lettuce가 쓰는 host/port로 쪼개야 한다. 프로퍼티를 용도별로 늘리지
 * 않은 이유는 "Redis가 어디 있나"는 하나의 사실이고, 두 벌로 두면 한쪽만 고치는 사고가 생긴다.
 */
@Configuration
@Conditional(RedisConnectionConfig.RedisRequired.class)
public class RedisConnectionConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(@Value("${tikkit.redis.address}") String address) {
        URI uri = URI.create(address);
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        return new LettuceConnectionFactory(configuration);
    }

    /**
     * 세션이든 캐시든 <b>하나라도</b> Redis를 요구하면 연결 팩토리를 만든다.
     * <p>
     * {@code PARSE_CONFIGURATION} 단계로 평가한다 — 빈 정의가 아니라 설정 클래스를 읽을지
     * 결정하는 조건이라서, 빈 등록 순서에 의존하지 않는다. {@code @ConditionalOnBean}은
     * 등록 순서에 의존하므로 컴포넌트 스캔으로 올라오는 클래스에 붙이면 결과를 신뢰할 수 없다.
     */
    static class RedisRequired extends AnyNestedCondition {

        RedisRequired() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(prefix = "tikkit.auth", name = "session-store", havingValue = "redis")
        static class SessionNeedsRedis {
        }

        @ConditionalOnProperty(prefix = "tikkit.cache", name = "type", havingValue = "redis")
        static class CacheNeedsRedis {
        }

        /**
         * 대기열 (Task 031). 세션·캐시를 끄고 <b>대기열만</b> 켜는 조합이 가능해지는 지점이다 —
         * 대기열 통합 테스트가 캐시 컨텍스트를 건드리지 않고 돌아야 해서 필요하다.
         */
        @ConditionalOnProperty(prefix = "tikkit.queue", name = "enabled", havingValue = "true")
        static class QueueNeedsRedis {
        }
    }
}
