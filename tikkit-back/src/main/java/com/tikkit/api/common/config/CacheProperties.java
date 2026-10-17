package com.tikkit.api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 조회 캐시 설정 (Task 029).
 *
 * <h2>왜 토글인가</h2>
 * 기본값을 {@code NONE}으로 둬서 Redis 없이도 기동·테스트가 돌아야 한다는 기존 결정을 지킨다
 * ({@code docs/improvements/003-redis-distributed-lock.md} 3절). 통합 테스트가 캐시를 모르는
 * 상태로 그대로 돈다. {@code tikkit.auth.session-store}와 같은 모양으로 맞췄다.
 *
 * <h2>왜 저장소 선택지가 Redis뿐인가</h2>
 * Caffeine(JVM 로컬)도 넣어 비교했고 기각했다. 로컬 캐시는 가장 빨랐지만
 * ({@code docs/improvements/008-caching.md} 4절) 공연 파생값을 바꾸는 유일한 지점인 만료 배치가
 * {@code @SchedulerLock}으로 <b>한 인스턴스에서만</b> 돌기 때문에, evict가 그 JVM에만 적용되고
 * 다른 BE는 TTL 60초가 끝날 때까지 옛 {@code status}를 내려준다. 인스턴스가 늘면 그 범위도 같이
 * 커진다.
 * <p>
 * 로컬 캐시가 이기던 유일한 대상은 좌석 배치(항목 337,885바이트)였는데 그건 캐싱 자체를
 * 기각했다. 남은 캐시는 전부 수백 바이트~수 KB라서 직렬화를 피해 얻는 이득이 없다. 그래서
 * 선택지를 남기지 않았다 — 다시 재야 하면 의존성 한 줄을 되살리면 된다.
 *
 * <p>{@code @Value}가 아니라 {@code @ConfigurationProperties}를 쓴 이유는
 * {@code com.tikkit.api.security.AuthProperties}와 같다 — 완화된 바인딩이라 yml에 소문자로 써도
 * enum으로 들어온다.
 *
 * @param type 캐시 저장소. {@code NONE}이면 {@code NoOpCacheManager}가 올라간다
 */
@ConfigurationProperties(prefix = "tikkit.cache")
public record CacheProperties(CacheType type) {

    public CacheProperties {
        if (type == null) {
            type = CacheType.NONE;
        }
    }

    public enum CacheType {
        /** 캐시를 쓰지 않는다. 기본값 — Redis 없이 기동·테스트가 되는 상태를 지킨다. */
        NONE,
        /** Redis 공유 캐시. evict 한 번이 전 인스턴스에 반영된다. */
        REDIS
    }
}
