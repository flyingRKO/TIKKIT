package com.tikkit.api.domain.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 대기열 설정 (Task 031, Task 037에서 입장 속도를 전역화).
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
 * <h2>Task 037: {@code admit-batch-size}를 {@code admit-rate-per-second}로 바꿨다</h2>
 * 전에는 "한 틱에 입장시킬 인원"이었고 {@code QueueAdmissionScheduler}가 켠 회차를 전부 순회하며
 * 회차마다 그만큼 꺼냈다. 그래서 <b>초당 입장 인원 = batch × 켠 회차 수</b>였고, 기본값 50은 회차
 * 1개를 전제한 값이라 <b>회차 2개를 켜는 것만으로 100명/s가 되어 상한 84를 넘었다.</b> 운영자가
 * 회차를 하나 더 켜는 것만으로 좌석 API 보호가 조용히 뚫린다.
 * <p>
 * 이제 이 값은 <b>전역 초당 입장 인원</b>이고 켠 회차 수와 무관하다. 스케줄러가 틱마다 이 예산을
 * 들고 회차를 순회하면서 쓴 만큼 깎는다({@code QueueAdmissionScheduler} 참조).
 * <p>
 * <b>단위를 "초당"으로 고른 이유</b>는 010이 도출한 유효 구간이 명/s이기 때문이다 —
 * {@code [6.7, 84]명/s}. 전에는 설정 키가 "틱당"이라 구간 안인지 확인하려면 매번 간격으로
 * 나눠 봐야 했고, 그 단위 불일치가 "회차당인가 전역인가" 혼동의 뿌리 중 하나였다. 010 15절이
 * 남긴 두 후보("{@code batch / 켠 회차 수}로 나누거나 '초당 입장 인원'을 설정값으로")에서
 * 후자다.
 * <p>
 * <b>{@code admit-interval}을 설정에서 없앴다.</b> 설정값이 "초당 인원"이 되면 틱 간격은 "얼마나
 * 잘게 나눠 흘리나"라는 구현 디테일이고, 그 값을 바꿀 근거가 없다(흐름 속도는 이 rate가 정한다.
 * 010도 간격은 1s 고정으로만 쟀다). 동시에 그게 {@code @SchedulerLock}의 {@code lockAtLeastFor}와
 * 수동 결합돼 있던 문제의 해법이다 — 결합의 양쪽 중 하나를 없애면 결합이 사라진다. 간격은
 * {@code QueueAdmissionScheduler.ADMIT_INTERVAL} 상수다.
 *
 * <h2>속도·TTL을 회차별로 두지 않은 이유</h2>
 * 회차마다 다른 값을 줘야 할 근거가 없고, 주려면 {@code schedules}에 컬럼 두세 개를 더 넣거나
 * 설정 테이블을 만들어야 한다. 지금 필요하지 않은 복잡도라 전역으로 둔다. Task 037은 전역값을
 * <b>전역답게</b> 만드는 작업이고 회차별로 쪼개는 것이 아니다.
 *
 * @param admitRatePerSecond <b>전역</b> 초당 입장 인원. 켠 회차 수와 무관하다.
 *                           010이 실측으로 도출한 유효 구간은 {@code [6.7, 84]명/s}인데
 *                           <b>10,000석 1회차 기준</b>이다 — 상한 84 = {@code R_sat}(도착률 120/s)
 *                           × 안전계수 0.7이고 그 {@code R_sat}을 10,000석 좌석맵으로 쟀다.
 *                           좌석 수가 작으면 상한이 더 높고(010 한계 6), Task 036·039가 2,000석
 *                           포화를 768.6 → 1004.8 req/s로 올려서 <b>84 자체가 재도출 대상</b>이다.
 *                           그래서 상한을 코드로 강제하지 않는다 — 환경에 따라 틀린 값을 강제하는
 *                           쪽이 더 비싸다
 * @param passTtl            입장권 수명. 선점 홀드(10분)보다 길어야 한다 — 짧으면 자기가 잡은 좌석을
 *                           결제하지 못하는 모순이 생긴다
 * @param keyTtl             대기·활성 ZSET 키 자체의 수명. 쓰기마다 다시 걸어서 "아무도 안 쓰는 큐는
 *                           사라진다"를 만든다. 없으면 끝난 회차의 ZSET이 영구히 남는다
 * @param flagCacheTtl       {@code schedules.queue_enabled} 스냅샷 수명. 플래그를 켜고 이 시간만큼은
 *                           일부 인스턴스가 통과시킬 수 있다. 플래그는 사람이 SQL로 바꾸는 값이라 괜찮다
 */
@ConfigurationProperties(prefix = "tikkit.queue")
public record QueueProperties(
        boolean enabled,
        int admitRatePerSecond,
        Duration passTtl,
        Duration keyTtl,
        Duration flagCacheTtl
) {

    private static final int DEFAULT_ADMIT_RATE_PER_SECOND = 50;
    private static final Duration DEFAULT_PASS_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_KEY_TTL = Duration.ofHours(2);
    private static final Duration DEFAULT_FLAG_CACHE_TTL = Duration.ofSeconds(10);

    public QueueProperties {
        if (admitRatePerSecond <= 0) {
            admitRatePerSecond = DEFAULT_ADMIT_RATE_PER_SECOND;
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
}
