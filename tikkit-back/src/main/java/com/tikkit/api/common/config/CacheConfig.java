package com.tikkit.api.common.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.util.HashMap;
import java.util.Map;

/**
 * 조회 캐시 설정 (Task 029).
 *
 * <h2>왜 order를 HIGHEST_PRECEDENCE로 두나</h2>
 * 캐시 어드바이저와 트랜잭션 어드바이저의 기본 order는 <b>둘 다</b>
 * {@code Ordered.LOWEST_PRECEDENCE}라서 어느 쪽이 바깥인지 보장되지 않는다. 트랜잭션이 바깥이면
 * 캐시 히트에도 커넥션을 잡고 트랜잭션을 열었다 닫는다 — 캐시로 DB를 건드리지 않겠다는 목적이
 * 반쯤 깨진다. 캐시를 가장 바깥에 둬서 히트 경로가 Hikari를 아예 안 거치게 한다.
 * <p>
 * 대가: 캐시 메서드 안에서 트랜잭션이 필요한 작업을 하면 그 트랜잭션은 캐시 미스일 때만 열린다.
 * 지금 캐시 대상은 전부 읽기 전용 조회라서 문제가 없다.
 *
 * <h2>왜 CacheManager를 토글로 갈아끼우나</h2>
 * {@code NONE}은 {@link NoOpCacheManager}라 {@code @Cacheable}이 전부 통과만 한다. 조건부 빈으로
 * 어노테이션을 지우지 않아도 캐시가 완전히 꺼지고, 그래서 "캐시 없는 기준선"을 언제든 다시 잴 수
 * 있다. 저장소 선택이 Redis 하나뿐인 이유는 {@link CacheProperties} Javadoc에 있다.
 *
 * <h2>통계를 왜 명시적으로 켜나</h2>
 * Micrometer가 {@code cache.gets{result=hit|miss}}를 읽으려면 캐시 구현이 통계를 기록해야 한다.
 * {@code enableStatistics()}가 그 스위치다. 끄면 적중률 패널이 영구히 비어서 006이 요구한
 * "적중률을 따로 재야 p95 개선이 캐시 덕인지 확인된다"를 충족할 수 없다.
 * <p>
 * 그리고 캐시를 {@link CacheNames#TTLS}에 선언한 이름으로 <b>기동 시점에 미리 만든다</b>.
 * lazy로 두면 첫 요청 전까지 지표 시리즈가 없어서 Grafana가 "No data"를 띄우고, 적중률 0과
 * 계측 없음을 구분할 수 없다 (Task 025의 카운터 사전 등록과 같은 이유).
 */
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    /**
     * {@code tikkit.cache.type}에 따라 저장소를 고른다. 전략을 고르는 자리를 한 곳으로 모은 건
     * {@code AuthStrategyConfig}와 같은 방식이다 — 서비스 코드는 저장소를 모른다.
     */
    @Bean
    public CacheManager cacheManager(CacheProperties properties,
                                     ObjectProvider<RedisConnectionFactory> connectionFactory) {
        return switch (properties.type()) {
            case NONE -> new NoOpCacheManager();
            case REDIS -> redisCacheManager(connectionFactory.getObject());
        };
    }

    /**
     * Redis 캐시. 캐시마다 TTL이 다르므로 {@code withInitialCacheConfigurations}로 이름별 설정을 준다.
     */
    private CacheManager redisCacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration base = defaultCacheConfiguration();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        CacheNames.TTLS.forEach((name, ttl) -> perCache.put(name, base.entryTtl(ttl)));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(base)
                // withInitialCacheConfigurations만으로 이름별 설정이 적용되고, 그 이름들이
                // 기동 시점에 미리 만들어진다(= 지표 시리즈가 첫 요청 전에 등록된다).
                //
                // **initialCacheNames(perCache.keySet())를 같이 부르면 안 된다.** 그 메서드는
                // 주어진 이름을 "현재 cacheDefaults로" 미리 만들기 때문에 위에서 준 캐시별 TTL을
                // 덮어쓴다. 실제로 그렇게 짰다가 Redis에서 PTTL을 찍어보니 전부 -1(만료 없음)이었다 —
                // 테스트로 TTL을 확인하지 않았으면 "TTL 60초"라고 문서에 적어두고 영구 캐시를
                // 운영했을 것이다. 그래서 CacheTtlTest가 PTTL을 직접 검증한다.
                .withInitialCacheConfigurations(perCache)
                // 선언한 이름만 쓴다. 오타로 새 캐시가 조용히 생기면 적중률 0%로만 나타난다.
                .disableCreateOnMissingCache()
                .enableStatistics()
                .build();
    }

    /**
     * 값 직렬화는 Jackson 3용 {@link GenericJacksonJsonRedisSerializer}를 쓴다.
     *
     * <p>이름이 비슷한 {@code GenericJackson2JsonRedisSerializer}가 같은 jar에 있는데 그쪽은
     * Jackson 2({@code com.fasterxml.jackson}) 전용이다. 이 프로젝트 스택은 Jackson 3
     * ({@code tools.jackson})이라 2가 붙은 쪽을 고르면 주입받을 ObjectMapper 타입부터 어긋난다.
     *
     * <p><b>왜 default typing이 필요한가</b><br>
     * 캐시 값에 {@code List<TicketGradeRow>}처럼 제네릭 컬렉션이 있다. JSON만 저장하면 꺼낼 때
     * 원소 타입을 알 수 없어 {@code LinkedHashMap}으로 복원된다. 타입 정보를 같이 적어야 한다.
     *
     * <p><b>왜 enableUnsafeDefaultTyping()을 쓰지 않나</b><br>
     * 그 메서드는 JSON에 적힌 어떤 클래스든 역직렬화하므로, Redis에 쓰기 권한을 가진 쪽이
     * 임의 클래스를 올려 코드 실행으로 이어지는 고전적인 gadget 공격이 가능해진다. 캐시는
     * 세션과 같은 Redis를 쓰고 그 Redis는 compose 네트워크 안이지만, "지금 닫혀 있다"를 전제로
     * 깔지 않는다. 허용 패키지를 한정한다.
     */
    private RedisCacheConfiguration defaultCacheConfiguration() {
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.tikkit.api.")
                // 우리 DTO만 허용하면 부족하다. 타입 id는 **final이 아닌 모든 값**에 붙으므로
                // JDK 타입에도 붙고, 허용 목록에 없으면 읽을 때
                // "Could not resolve type id 'java.math.BigDecimal' as a subtype of
                // java.math.BigDecimal: PolymorphicTypeValidator denied resolution"으로 터진다.
                // (처음에 com.tikkit.api.와 java.util.만 넣었다가 등급 가격에서 그대로 겪었다.)
                .allowIfSubType("java.util.")   // List/Map 구현체
                .allowIfSubType("java.math.")   // BigDecimal (가격)
                .allowIfSubType("java.time.")   // Instant, LocalDate (회차 시각, 공연 기간)
                .build();

        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(validator)
                // @Cacheable(unless=...) 없이도 null을 캐시할 수 있게 한다. 지금은 null을 캐시하는
                // 경로가 없지만, 이걸 끄면 null 저장 시 예외가 나서 원인을 찾기 어렵다.
                .enableSpringCacheNullValueSupport()
                .build();

        return RedisCacheConfiguration.defaultCacheConfig()
                // 키는 사람이 redis-cli로 읽을 수 있어야 한다. 기본값은 JDK 직렬화라 바이너리다.
                .computePrefixWith(cacheName -> "tikkit:cache:" + cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
