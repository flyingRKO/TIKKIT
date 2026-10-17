package com.tikkit.api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 조회 캐시 설정 (Task 029).
 *
 * <p><b>왜 토글인가</b><br>
 * 기본값을 {@code NONE}으로 둬서 Redis 없이도 기동·테스트가 돌아야 한다는 기존 결정을 지킨다
 * ({@code docs/improvements/003-redis-distributed-lock.md} 3절). 통합 테스트 129개가 캐시를
 * 모르는 상태로 그대로 돈다. {@code tikkit.auth.session-store}와 같은 모양으로 맞췄다.
 *
 * <p>{@code CAFFEINE}이 있는 이유는 비교 실험이다. 이 서비스에서 로컬 캐시가 왜 맞지 않는지는
 * 구조에서 이미 드러난다 — 공연 상태를 바꾸는 유일한 지점인 만료 배치가 ShedLock으로
 * <b>한 인스턴스에서만</b> 돌기 때문에, evict가 그 JVM에만 적용되고 다른 BE는 최대 60초간
 * 옛 {@code status}를 내려준다. 그 불일치를 말로 적지 않고 실측하려고 선택지로 남겼다.
 *
 * <p>{@code @Value}가 아니라 {@code @ConfigurationProperties}를 쓴 이유는
 * {@code com.tikkit.api.security.AuthProperties}와 같다 — 완화된 바인딩이라 yml에 소문자로 써도
 * enum으로 들어온다.
 *
 * @param type               캐시 저장소. {@code NONE}이면 {@code NoOpCacheManager}가 올라간다
 * @param seatmapResponseTtl 좌석맵 <b>응답 바이트</b> 캐시의 TTL. 기본값 0은 비활성이다.
 *                           0보다 크면 좌석 상태가 그 시간만큼 stale해진다 — 실험용 스위치이고
 *                           운영에서 켜지 않는다. 자세한 근거는 {@code CacheConfig} 참조
 */
@ConfigurationProperties(prefix = "tikkit.cache")
public record CacheProperties(CacheType type, Duration seatmapResponseTtl) {

    public CacheProperties {
        if (type == null) {
            type = CacheType.NONE;
        }
        if (seatmapResponseTtl == null) {
            seatmapResponseTtl = Duration.ZERO;
        }
    }

    /**
     * 좌석맵 응답 바이트 캐시가 켜져 있는지. TTL이 0 이하면 끈 것으로 본다.
     */
    public boolean seatmapResponseCacheEnabled() {
        return !seatmapResponseTtl.isZero() && !seatmapResponseTtl.isNegative();
    }

    public enum CacheType {
        /** 캐시를 쓰지 않는다. 기본값 — Redis 없이 기동·테스트가 되는 상태를 지킨다. */
        NONE,
        /** JVM 로컬 캐시. BE가 2대면 배치 evict가 한쪽에만 적용된다 (비교 실험군). */
        CAFFEINE,
        /** Redis 공유 캐시. 운영 후보. */
        REDIS
    }
}
