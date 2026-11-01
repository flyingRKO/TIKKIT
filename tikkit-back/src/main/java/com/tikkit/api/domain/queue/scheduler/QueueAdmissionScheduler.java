package com.tikkit.api.domain.queue.scheduler;

import com.tikkit.api.domain.queue.config.QueueProperties;
import com.tikkit.api.domain.queue.repository.WaitingQueueRedisRepository;
import com.tikkit.api.domain.queue.service.QueueFlagRegistry;
import com.tikkit.api.domain.queue.service.QueueMetrics;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 대기열 입장 배치 (Task 031, Task 037에서 전역 예산으로 전환).
 *
 * <h2>Task 037: 입장 속도가 켠 회차 수에 비례하던 것을 고쳤다</h2>
 * 전에는 회차를 순회하면서 <b>회차마다 독립적으로</b> {@code admit-batch-size}명을 꺼냈다.
 * 그래서 초당 입장 인원이 {@code batch × 켠 회차 수}였고, 기본값 50은 회차 1개를 전제한 값이라
 * <b>회차 2개를 켜는 것만으로 100명/s가 되어 010이 실측한 상한 84를 넘었다.</b> 대기열의 목적이
 * 좌석 API를 지키는 것인데, 운영자가 회차를 하나 더 켜는 것만으로 그 보호가 조용히 뚫린다.
 * <p>
 * 이제 틱마다 <b>전역 예산</b>({@code admit-rate-per-second})을 들고 회차를 순회하면서 쓴 만큼
 * 깎는다. 켠 회차가 몇 개여도 틱당 총 입장 인원은 예산을 넘지 않는다.
 *
 * <h3>루프가 셋인 이유 — 만료 수거를 예산에 묶으면 안 된다</h3>
 * 예산을 다 쓰면 입장은 멈춰야 하지만 <b>만료된 입장권 수거는 멈추면 안 된다.</b> 한 메서드에
 * 묶어 두고 예산 소진에 {@code break}를 걸면 뒤 회차의 {@code active} ZSET에 만료 엔트리가 쌓이고
 * {@code activeSize}가 틀린다. 만료 판정 자체는 score 비교라 안전하지만(만료권으로 통과하지는
 * 않는다) 수거가 밀린 만큼 Redis 메모리가 남고, 켠 회차가 많을수록 뒤쪽 회차가 영구히 밀린다.
 * 대기 인원 집계(Gauge)도 같은 이유로 전 회차를 돈다 — 예산이 소진된 회차의 줄이 대시보드에서
 * 사라지면 "대기열이 비었다"로 오독한다.
 * <p>
 * Redis 왕복은 늘지 않는다. {@code peekWaiting}에 넘기는 count가 고정값에서 남은 예산으로
 * 바뀌는 것뿐이고, 예산이 소진되면 뒤 회차는 {@code ZRANGE}를 아예 돌지 않아 <b>오히려 줄어든다</b>
 * (010 5.2절이 "3,000회차를 켜면 틱마다 왕복 약 9,000회"를 전역화의 두 번째 이유로 적었다).
 *
 * <h3>앞 회차가 예산을 다 먹을 수 있다 — 알려진 편향이다</h3>
 * 예산을 순회하며 이어받으므로 <b>id가 작은 회차가 먼저 먹는다.</b> 그래서
 * {@code QueueFlagRegistry}가 순회 순서를 id 오름차순으로 고정한다 — 편향을 없애지는 못하지만
 * <b>설명할 수 있게</b> 만든다({@code HashSet} 해시 순서면 "왜 저 회차만 들어가나"에 답할 수 없다).
 * <p>
 * 다른 분배 방식을 고르지 않은 이유는 {@code docs/improvements/017-queue-global-limits.md}에 있다:
 * 균등 분배({@code budget / N})는 빈 회차 몫이 버려지고 켠 회차가 예산보다 많으면 몫이 0이 되며,
 * 대기 인원 비례는 틱마다 {@code ZCARD}가 회차 수만큼 늘고, 라운드로빈 커서는 JVM 로컬 상태라
 * BE 2대 + ShedLock 환경에서 인스턴스마다 다른 위치에서 시작한다.
 *
 * <h2>왜 ShedLock이 꼭 필요한가</h2>
 * 락이 없으면 BE 2대가 같은 틱에 각자 전역 예산만큼 집는다 — 전역 상한을 넣어도 인스턴스 수만큼
 * 흘러나가므로 이 Task의 수정이 무의미해진다. 로그도 두 줄씩 찍혀서 입장 속도 계측이 2배로 보인다.
 *
 * <h2>{@code admit-interval}이 설정이 아니라 상수다</h2>
 * 전에는 {@code tikkit.queue.admit-interval}이 설정값이었고 아래 {@code lockAtLeastFor}는
 * 어노테이션 리터럴이었다. 어노테이션 속성은 컴파일 타임 상수여야 해서 "간격의 90%"라는 관계를
 * 쓸 수 없으므로 <b>두 값이 수동으로 묶여 있었고</b>, {@code admit-interval}만 크게 바꾸면
 * "설정은 5초인데 실제로는 1초마다 입장"이 되면서 증상이 "예상 대기시간이 계속 틀리다"로만
 * 나타났다.
 * <p>
 * Task 037이 <b>결합의 한쪽을 없앴다.</b> 설정값이 "초당 인원"이 되면 틱 간격은 "얼마나 잘게
 * 나눠 흘리나"라는 구현 디테일이고 바꿀 근거가 없다(흐름 속도는 rate가 정한다). 그래서 간격을
 * 아래 상수로 내리고 {@code lockAtLeastFor}를 바로 옆에 뒀다. 두 상수의 관계는
 * {@code QueueAdmissionSchedulerContractTest}가 리플렉션으로 고정하므로 <b>드리프트가 조용한
 * 버그가 아니라 빨간 테스트가 된다.</b>
 *
 * <h2>{@code lockAtMostFor}가 짧은 이유</h2>
 * 락을 들고 죽었을 때 풀리는 시한이다. 예매 만료 배치는 5분이지만 대기열은 30초로 둔다 —
 * 그 시간 동안 <b>입장이 완전히 멈추고</b> 줄 선 사람들의 화면에서 순번이 얼어붙는다.
 * 작업이 수 ms라 30초를 "느린 실행"으로 오판할 여지도 없다.
 */
@Slf4j
public class QueueAdmissionScheduler {

    /**
     * 틱 간격. <b>이 값이 1초라서 "틱당 예산 = 초당 입장 인원"이 그대로 성립한다.</b>
     * 바꾸면 {@link #run()}의 예산 계산이 틀리므로 계약 테스트가 1초를 고정한다.
     */
    static final String ADMIT_INTERVAL = "1s";

    /**
     * 작업이 수 ms에 끝나므로 락을 즉시 놓으면 같은 틱 안에서 다른 인스턴스가 다시 집는다
     * ({@code ReservationExpiryScheduler}가 55초를 잡은 것과 같은 이유).
     * <b>{@link #ADMIT_INTERVAL}보다 작아야 한다</b> — 크면 틱을 거를 수 있다.
     */
    static final String LOCK_AT_LEAST_FOR = "900ms";

    /** 락을 들고 죽었을 때 풀리는 시한. 위 javadoc 참조. */
    static final String LOCK_AT_MOST_FOR = "30s";

    static final String LOCK_NAME = "queue-admission";

    private final WaitingQueueRedisRepository repository;
    private final QueueFlagRegistry flags;
    private final QueueMetrics metrics;
    private final QueueProperties properties;

    public QueueAdmissionScheduler(WaitingQueueRedisRepository repository,
                                   QueueFlagRegistry flags,
                                   QueueMetrics metrics,
                                   QueueProperties properties) {
        this.repository = repository;
        this.flags = flags;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = ADMIT_INTERVAL)
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = LOCK_AT_LEAST_FOR)
    public void run() {
        Instant now = Instant.now();
        Set<Long> scheduleIds = flags.enabledScheduleIds();
        if (scheduleIds.isEmpty()) {
            // Gauge를 0으로 내려놓고 나간다. 이 한 줄이 없으면 플래그를 끈 뒤에도
            // tikkit.queue.waiting.size가 **마지막 값에 얼어붙는다** — Gauge는 AtomicLong을
            // 읽을 뿐이고 아무도 그 값을 갱신하지 않기 때문이다. 대시보드에서는 "대기열이
            // 아직 돌고 있다"로 보이고, 측정 군을 바꾼 직후에 그 오독이 일어난다.
            metrics.updateWaitingSize(0);
            return;
        }

        // ① 만료된 입장권 수거 — 예산과 무관하게 전 회차. 명령 하나로 끝난다(만료 시각이 score다).
        for (Long scheduleId : scheduleIds) {
            repository.removeExpiredPasses(scheduleId, now);
        }

        // ② 입장 — 전역 예산을 순회하며 소비한다. 앞 회차가 안 쓴 몫이 뒤 회차로 넘어간다.
        int budget = properties.admitRatePerSecond();
        long totalAdmitted = 0;
        for (Long scheduleId : scheduleIds) {
            if (budget <= 0) {
                break;
            }
            int admitted = admit(scheduleId, now, budget);
            budget -= admitted;
            totalAdmitted += admitted;
        }

        // ③ 대기 인원 집계 — 전 회차. Gauge가 스크랩마다 Redis를 왕복하지 않도록 여기서 넘긴다.
        long totalWaiting = 0;
        for (Long scheduleId : scheduleIds) {
            totalWaiting += repository.waitingSize(scheduleId);
        }
        metrics.updateWaitingSize(totalWaiting);

        if (totalAdmitted > 0) {
            // 예산 소진 여부를 같이 남긴다. 소진이 계속 찍히면 켠 회차 수에 비해 rate가 작아서
            // 뒤쪽 회차가 굶고 있다는 신호이고, 그게 이 설계의 알려진 편향이다.
            log.info("대기열 입장 배치 — 입장 {}명, 남은 대기 {}명, 대상 회차 {}개, 예산 소진 {}",
                    totalAdmitted, totalWaiting, scheduleIds.size(), budget <= 0);
        }
    }

    /**
     * 한 회차의 입장 처리. 남은 전역 예산을 상한으로 받는다.
     *
     * <h2>자가 치유가 공짜로 따라온다</h2>
     * 앞선 틱이 "활성에 추가"까지만 하고 끊겼으면 그 사람은 {@code waiting}에도 남아 있어서 이번
     * 후보에 다시 뽑힌다. 그래도 분기가 필요 없다 — {@code ZADD}는 이미 있는 멤버의 score를
     * 갱신할 뿐이고, 뒤따르는 {@code ZREM}이 {@code waiting}의 중복을 치운다. 즉 <b>중복을
     * 걸러내려고 후보마다 {@code ZSCORE}를 돌리지 않는다</b>(50명이면 왕복 50회다).
     * <p>
     * 대가는 그 사람을 입장 인원으로 한 번 더 센다는 것이고, 중복 자체가 프로세스가 죽어야
     * 생기는 경로라 지표 오차로 감당한다. <b>Task 037에서 대가가 하나 늘었다</b> — 중복이
     * 전역 예산도 한 칸 먹으므로 그 틱의 뒤쪽 회차가 한 명 덜 들어간다. 같은 이유로 무해하다.
     *
     * @param budget 이 회차가 쓸 수 있는 상한(남은 전역 예산)
     * @return 입장시킨 인원
     */
    private int admit(long scheduleId, Instant now, int budget) {
        List<Long> candidates = repository.peekWaiting(scheduleId, budget);
        if (candidates.isEmpty()) {
            return 0;
        }

        // 활성에 추가 → 대기에서 제거. 순서가 중요한 이유는 repository.admit() Javadoc에 있다.
        repository.admit(scheduleId, candidates, now.plus(properties.passTtl()));
        metrics.recordAdmitted(candidates.size());
        return candidates.size();
    }
}
