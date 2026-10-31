package com.tikkit.api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 조회 캐시 설정 (Task 029).
 *
 * <h2>왜 토글인가</h2>
 * 기본값을 {@code NONE}으로 둬서 Redis 없이도 기동·테스트가 돌아야 한다는 기존 결정을 지킨다
 * ({@code docs/improvements/003-redis-distributed-lock.md} 3절). 통합 테스트가 캐시를 모르는
 * 상태로 그대로 돈다. {@code tikkit.auth.session-store}와 같은 모양으로 맞췄다.
 *
 * <h2>왜 카탈로그 캐시의 저장소 선택지가 Redis뿐인가</h2>
 * Caffeine(JVM 로컬)도 넣어 비교했고 <b>카탈로그 캐시에 대해서는</b> 기각했다. 로컬 캐시는 가장
 * 빨랐지만 ({@code docs/improvements/008-caching.md} 4절) 공연 파생값을 바꾸는 유일한 지점인
 * 만료 배치가 {@code @SchedulerLock}으로 <b>한 인스턴스에서만</b> 돌기 때문에, evict가 그 JVM에만
 * 적용되고 다른 BE는 TTL 60초가 끝날 때까지 옛 {@code status}를 내려준다. 인스턴스가 늘면 그
 * 범위도 같이 커진다. 남은 카탈로그 캐시는 전부 수백 바이트~수 KB라서 직렬화를 피해 얻는 이득도
 * 없다.
 *
 * <h2>좌석맵은 왜 별도 전략인가 (Task 036)</h2>
 * 위 기각 근거가 <b>좌석맵에는 적용되지 않는다.</b> evict가 필요한 것은 "변하는 값을 캐시했기
 * 때문"인데, 좌석맵에서 캐시하는 것은 좌표·구역·행·번호·등급 id뿐이고 <b>그 필드를 런타임에
 * 바꾸는 코드 경로가 0건</b>이다(좌석 상태 UPDATE 3개와 만료 배치는 전부 {@code status}·
 * {@code reservation_id}·{@code updated_at}만 SET하고, {@code Seat}·{@code ScheduleSeat}에 해당
 * 필드 변경 메서드가 없다). 상태는 캐시하지 않고 매 요청 읽어서 합친다.
 * <p>
 * 그래서 좌석맵만 로컬 캐시로 가고 카탈로그는 Redis에 남는다. {@code CacheManager}가 둘인 이유가
 * 이것이고, 섞지 않는 이유는 측정이다 — 군 사이에 다른 캐시의 조건이 고정돼야 이득을 좌석맵에
 * 귀속할 수 있다.
 * <p>
 * 008이 든 나머지 기각 근거 ①(힙 용량. 2,000개 객체 그래프 × 회차 수)은 <b>유효하다.</b>
 * 그래서 {@code seatmapMaxEntries}로 보관 회차 수에 상한을 두고, 그 상한에서 힙이 어떻게 움직이는지를
 * Task 036이 측정한다.
 *
 * <p>{@code @Value}가 아니라 {@code @ConfigurationProperties}를 쓴 이유는
 * {@code com.tikkit.api.security.AuthProperties}와 같다 — 완화된 바인딩이라 yml에 소문자로 써도
 * enum으로 들어온다.
 *
 * @param type              카탈로그 캐시 저장소. {@code NONE}이면 {@code NoOpCacheManager}가 올라간다
 * @param seatmap           좌석맵 조회 전략 (Task 036). 측정 군 A/B/C에 1:1로 대응한다
 * @param seatmapMaxEntries 좌석맵 로컬 캐시가 보관할 회차 수 상한. 008의 기각 근거 ①이 힙 용량이라
 *                          측정 축으로 뺐다. 2,000석 회차 하나가 Redis 직렬화 기준 337,885바이트였다
 * @param seatmapTtl        좌석맵 로컬 캐시 TTL. 캐시 대상이 불변이라 무효화용이 아니라 안전망이다 —
 *                          좌석 구성이 마이그레이션으로 바뀌어도 이 시간 안에 수렴한다
 */
@ConfigurationProperties(prefix = "tikkit.cache")
public record CacheProperties(CacheType type,
                              SeatmapStrategy seatmap,
                              int seatmapMaxEntries,
                              Duration seatmapTtl) {

    public CacheProperties {
        if (type == null) {
            type = CacheType.NONE;
        }
        if (seatmap == null) {
            seatmap = SeatmapStrategy.SINGLE;
        }
        if (seatmapMaxEntries <= 0) {
            seatmapMaxEntries = 200;
        }
        if (seatmapTtl == null) {
            seatmapTtl = Duration.ofMinutes(10);
        }
    }

    public enum CacheType {
        /** 캐시를 쓰지 않는다. 기본값 — Redis 없이 기동·테스트가 되는 상태를 지킨다. */
        NONE,
        /** Redis 공유 캐시. evict 한 번이 전 인스턴스에 반영된다. */
        REDIS
    }

    /**
     * 좌석맵 조회 전략 (Task 036). 세 값이 측정 군 A·B·C다.
     *
     * <p>토글이 둘(분리 여부 / 캐시 여부)이 아니라 enum 하나인 이유는 040이 밟은 함정이다 —
     * 군이 여러 개일 때 환경변수를 손으로 조합하면 하나가 조용히 어긋나고, 그러면 "같은 조건으로
     * 쟀다"는 전제가 깨진다. {@code run-group.sh}가 군 시작 시 설정을 출력해 사후 구분할 수 있게
     * 한 것과 같은 방향이다.
     *
     * <p><b>판정이 끝나면 이 enum은 사라진다.</b> 채택이면 고른 전략만 남기고, 기각이면 전부
     * 되돌린다 — 기각한 구현을 토글로 남기지 않는 것이 이 레포의 규칙이다
     * ({@code docs/improvements/README.md} 교훈 5).
     */
    public enum SeatmapStrategy {
        /**
         * 단일 쿼리. 현행이고 기본값이다 — 측정의 before가 사라지지 않게 한다.
         * 좌석맵 p95 6.63ms / 포화 775 req/s (008 4.1절, 012 7.1절).
         */
        SINGLE,
        /**
         * 배치·상태 두 쿼리로 분리하고 캐시는 쓰지 않는다. <b>이 전략은 순손실이다</b> —
         * 왕복이 1 → 2로 늘고 {@code schedule_seats}를 두 번 읽어 6.63 → 8.62ms(+30%)가 된다.
         * 측정 군으로만 존재한다. 분리 손해와 캐시 이득을 갈라서 귀속하려면 이 점이 필요하다
         * (Task 035가 군을 둘로 나눠 귀속을 가른 것과 같은 이유).
         */
        SPLIT,
        /** 분리 + 배치를 Caffeine 로컬 캐시에 둔다. 저부하 4.98ms / 적중률 97.0% (008 4.1절). */
        LOCAL
    }
}
