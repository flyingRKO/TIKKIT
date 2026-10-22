package com.tikkit.api.domain.queue.config;

import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.queue.repository.WaitingQueueRedisRepository;
import com.tikkit.api.domain.queue.scheduler.QueueAdmissionScheduler;
import com.tikkit.api.domain.queue.service.DisabledQueueGateway;
import com.tikkit.api.domain.queue.service.QueueFlagRegistry;
import com.tikkit.api.domain.queue.service.QueueGateway;
import com.tikkit.api.domain.queue.service.QueueMetrics;
import com.tikkit.api.domain.queue.service.RedisQueueGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 대기열 구현을 고르는 <b>유일한</b> 자리 (Task 031).
 *
 * <h2>꺼진 상태를 "빈이 없음"이 아니라 구현으로 표현한다</h2>
 * {@code tikkit.queue.enabled}가 기본 false이고 그때는 Redis 연결 팩토리도 없다. 그런데
 * {@code ReservationService}와 인터셉터는 <b>항상</b> 가드를 호출해야 한다 — 대기열을 끌 때마다
 * 호출부를 넣고 빼야 한다면 그게 더 위험하다.
 * <p>
 * 그래서 꺼진 상태는 {@link DisabledQueueGateway}(전부 통과)로 표현한다.
 * {@code tikkit.cache.type=none}이 {@code NoOpCacheManager}를 올려서 {@code @Cacheable}을 지우지
 * 않고도 캐시를 완전히 끄는 것과 같은 구조다.
 *
 * <h2>조건을 중첩 설정 한 곳에만 붙인다</h2>
 * {@code AuthStrategyConfig}가 적어둔 이유 — 빈마다 조건을 흩으면 <b>조합이 어긋난 상태로도 기동이
 * 된다</b>. 대기열은 "ZSET 접근 + 플래그 스냅샷 + 지표 + 입장 배치"가 한 세트라서 하나만 빠지면
 * "줄은 서는데 아무도 입장하지 않는" 상태가 되고, 예외도 로그도 없다.
 * <p>
 * 그래서 Redis가 필요한 빈 전체를 {@link RedisQueueConfig} 하나에 넣고 조건을 거기에만 건다.
 * 묶어두면 <b>지표 인스턴스도 하나로 공유된다</b> — 게이트웨이와 스케줄러가 각자
 * {@code new QueueMetrics(...)}를 하면 Gauge가 읽는 {@code AtomicLong}과 스케줄러가 갱신하는
 * {@code AtomicLong}이 서로 달라져서 <b>대기 인원 지표가 영원히 0</b>이 된다.
 */
@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueStrategyConfig {

    /**
     * Redis 구현이 있으면 그걸 쓰고, 없으면 전부 통과시키는 구현을 쓴다.
     *
     * <p>{@code ObjectProvider}로 받는 이유: {@code @ConditionalOnMissingBean}은 빈 등록 순서에
     * 의존해서 자동설정 밖에서는 결과를 신뢰할 수 없다({@code RedisConnectionConfig}가
     * {@code @ConditionalOnBean}을 피한 것과 같은 이유). {@code getIfAvailable()}은 지연 해석이라
     * 순서와 무관하다.
     *
     * <p>{@code CacheProperties}처럼 enum을 쓰지 않고 boolean인 이유: 캐시 저장소는 Caffeine을
     * 넣어 비교했던 이력이 있어 선택지가 늘어날 자리였지만, 대기열은 ZSET이 전제라 Redis 외의
     * 구현이 있을 수 없다.
     */
    @Bean
    public QueueGateway queueGateway(ObjectProvider<RedisQueueGateway> redisQueueGateway) {
        RedisQueueGateway redis = redisQueueGateway.getIfAvailable();
        return redis != null ? redis : new DisabledQueueGateway();
    }

    /**
     * 대기열이 켜졌을 때만 올라가는 구성요소 전체. <b>조건이 이 클래스 하나에만 붙는다.</b>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "tikkit.queue", name = "enabled", havingValue = "true")
    static class RedisQueueConfig {

        /**
         * {@code StringRedisTemplate}을 직접 만드는 이유: {@code application.yml}이
         * {@code DataRedisAutoConfiguration}을 항상 제외해서 자동설정이 주던 템플릿 빈이 없다
         * (제외하지 않으면 세션 저장소를 끌 방법이 없어진다 — {@code RedisConnectionConfig} Javadoc).
         *
         * <p>JSON이 아니라 문자열 템플릿인 이유: 넣는 값이 {@code memberId} 문자열과 epoch millis
         * 숫자뿐이고, 운영 중에 {@code redis-cli}로 사람이 바로 읽을 수 있어야 한다.
         * "지금 몇 명이 줄 서 있나"를 {@code ZRANGE ... WITHSCORES}로 확인하는 게 디버깅의
         * 1차 수단이다.
         *
         * <p>{@code RedisConnectionFactory}를 직접 주입받아도 되는 이유: 이 설정 클래스 자체가
         * 조건부이고, {@code RedisConnectionConfig}가 {@code tikkit.queue.enabled=true}도 Redis가
         * 필요한 경우로 세기 때문에 여기까지 왔다면 팩토리가 반드시 있다.
         */
        @Bean
        StringRedisTemplate queueRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
            return new StringRedisTemplate(redisConnectionFactory);
        }

        @Bean
        WaitingQueueRedisRepository waitingQueueRedisRepository(StringRedisTemplate queueRedisTemplate,
                                                                QueueProperties properties) {
            return new WaitingQueueRedisRepository(queueRedisTemplate, properties);
        }

        @Bean
        QueueFlagRegistry queueFlagRegistry(ScheduleRepository scheduleRepository,
                                            QueueProperties properties) {
            return new QueueFlagRegistry(scheduleRepository, properties.flagCacheTtl());
        }

        @Bean
        QueueMetrics queueMetrics(MeterRegistry meterRegistry) {
            return new QueueMetrics(meterRegistry);
        }

        @Bean
        RedisQueueGateway redisQueueGateway(WaitingQueueRedisRepository repository,
                                            QueueFlagRegistry flags,
                                            QueueMetrics metrics,
                                            QueueProperties properties) {
            return new RedisQueueGateway(repository, flags, metrics, properties);
        }

        /**
         * 입장 배치. {@code @Bean}으로 등록해도 {@code @Scheduled}는 정상 처리된다.
         * <p>
         * 다만 {@code SchedulingConfig}가 {@code @Profile("!test")}라 테스트 프로필에서는 틱이
         * 돌지 않는다 — 테스트는 {@code run()}을 직접 부른다({@code CacheEvictionTest} 선례).
         * 틱 타이밍에 의존하지 않아 결정적이라는 점에서 오히려 낫다.
         */
        @Bean
        QueueAdmissionScheduler queueAdmissionScheduler(WaitingQueueRedisRepository repository,
                                                        QueueFlagRegistry flags,
                                                        QueueMetrics metrics,
                                                        QueueProperties properties) {
            return new QueueAdmissionScheduler(repository, flags, metrics, properties);
        }
    }
}
