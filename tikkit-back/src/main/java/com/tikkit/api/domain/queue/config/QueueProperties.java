package com.tikkit.api.domain.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 대기열 설정 (Task 031).
 *
 * <h2>왜 토글인가</h2>
 * 기본값 {@code enabled=false}로 둬서 Redis 없이도 기동·테스트가 돌아야 한다는 기존 결정을 지킨다
 * ({@code docs/improvements/003-redis-distributed-lock.md} 3절). {@code tikkit.auth.session-store}와
 * {@code tikkit.cache.type}과 같은 모양이고, 셋은 서로 독립이다.
 * <p>
 * 이 토글은 "대기열 기능이 이 애플리케이션에 있는가"이고, {@code schedules.queue_enabled}는
 * "이 회차에 줄을 세우는가"다. 전자가 꺼져 있으면 후자가 true여도 아무 일도 일어나지 않는다 —
 * Redis 연결 자체가 없기 때문이다.
 *
 * <h2>속도·TTL을 회차별로 두지 않은 이유</h2>
 * 회차마다 다른 값을 줘야 할 근거가 없고, 주려면 {@code schedules}에 컬럼 두세 개를 더 넣거나
 * 설정 테이블을 만들어야 한다. 지금 필요하지 않은 복잡도라 전역으로 둔다.
 *
 * @param admitBatchSize 한 틱에 입장시킬 인원. <b>측정 전 임시값이다</b> — "BE 2대가 좌석 조회와
 *                       선점을 몇 RPS까지 p95 안에 처리하는가"를 Task 033이 재야 정해진다
 * @param admitInterval  입장 스케줄러 틱 간격. {@code fixedDelay}라 실제 주기는 이 값 + 실행 시간이고,
 *                       그래서 ETA가 실제보다 낙관적이다(7절 한계로 문서화)
 * @param passTtl        입장권 수명. 선점 홀드(10분)보다 길어야 한다 — 짧으면 자기가 잡은 좌석을
 *                       결제하지 못하는 모순이 생긴다
 * @param keyTtl         대기·활성 ZSET 키 자체의 수명. 쓰기마다 다시 걸어서 "아무도 안 쓰는 큐는
 *                       사라진다"를 만든다. 없으면 끝난 회차의 ZSET이 영구히 남는다
 * @param flagCacheTtl   {@code schedules.queue_enabled} 스냅샷 수명. 플래그를 켜고 이 시간만큼은
 *                       일부 인스턴스가 통과시킬 수 있다. 플래그는 사람이 SQL로 바꾸는 값이라 괜찮다
 */
@ConfigurationProperties(prefix = "tikkit.queue")
public record QueueProperties(
        boolean enabled,
        int admitBatchSize,
        Duration admitInterval,
        Duration passTtl,
        Duration keyTtl,
        Duration flagCacheTtl
) {

    private static final int DEFAULT_ADMIT_BATCH_SIZE = 50;
    private static final Duration DEFAULT_ADMIT_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_PASS_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_KEY_TTL = Duration.ofHours(2);
    private static final Duration DEFAULT_FLAG_CACHE_TTL = Duration.ofSeconds(10);

    public QueueProperties {
        if (admitBatchSize <= 0) {
            admitBatchSize = DEFAULT_ADMIT_BATCH_SIZE;
        }
        if (admitInterval == null || admitInterval.isZero() || admitInterval.isNegative()) {
            admitInterval = DEFAULT_ADMIT_INTERVAL;
        }
        if (passTtl == null || passTtl.isZero() || passTtl.isNegative()) {
            passTtl = DEFAULT_PASS_TTL;
        }
        if (keyTtl == null || keyTtl.isZero() || keyTtl.isNegative()) {
            keyTtl = DEFAULT_KEY_TTL;
        }
        if (flagCacheTtl == null || flagCacheTtl.isNegative()) {
            flagCacheTtl = DEFAULT_FLAG_CACHE_TTL;
        }
    }

    /**
     * 초당 입장 인원. 예상 대기시간의 분모다.
     * <p>
     * <b>공칭값이다.</b> 실제 입장 속도는 {@code fixedDelay} + 실행 시간 + ShedLock
     * {@code lockAtLeastFor}에 밀려 이보다 느리므로, ETA는 실제보다 낙관적으로 나온다
     * (= 표시보다 늦게 들어온다). 관측된 속도로 계산하지 않는 이유는 분모가 흔들리면 ETA의
     * 단조 비증가가 깨지기 때문이다 — 진행바가 되감기면 사용자는 버그로 읽는다.
     */
    public double admitRatePerSecond() {
        return admitBatchSize / (admitInterval.toMillis() / 1000.0);
    }
}
