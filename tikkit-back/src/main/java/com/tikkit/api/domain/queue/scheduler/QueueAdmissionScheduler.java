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
 * 대기열 입장 배치 (Task 031). 틱마다 선두 N명을 입장시킨다.
 *
 * <h2>왜 ShedLock이 꼭 필요한가</h2>
 * 락이 없으면 BE 2대가 같은 틱에 각자 선두 N명을 집는다. 첫 인스턴스가 {@code ZREM}까지 마치면
 * 두 번째는 <b>그 다음 N명</b>을 집으므로 입장 속도가 인스턴스 수만큼 빨라진다 — 유입을 제어하려고
 * 만든 장치가 설정값의 2배를 흘려보낸다. 로그도 두 줄씩 찍혀서 입장 속도 계측이 2배로 보인다.
 *
 * <h2>{@code lockAtLeastFor}를 {@code admitInterval}보다 약간 작게 둔다</h2>
 * 이 작업은 수 ms에 끝나므로 락을 즉시 놓으면 같은 틱 안에서 다른 인스턴스가 다시 집는다
 * ({@code ReservationExpiryScheduler}가 55초를 잡은 것과 같은 이유).
 * <p>
 * <b>주의: 어노테이션에는 계산식을 쓸 수 없어서 두 값이 수동으로 묶여 있다.</b>
 * {@code tikkit.queue.admit-interval}을 1초에서 크게 바꾸면 아래 {@code lockAtLeastFor}도 같이
 * 봐야 한다. 안 바꾸면 "설정은 5초인데 실제로는 1초마다 입장"이 되고, 증상이 "예상 대기시간이
 * 계속 틀리다"로만 나타난다.
 *
 * <h2>{@code lockAtMostFor}가 짧은 이유</h2>
 * 락을 들고 죽었을 때 풀리는 시한이다. 예매 만료 배치는 5분이지만 대기열은 30초로 둔다 —
 * 그 시간 동안 <b>입장이 완전히 멈추고</b> 줄 선 사람들의 화면에서 순번이 얼어붙는다.
 * 작업이 수 ms라 30초를 "느린 실행"으로 오판할 여지도 없다.
 */
@Slf4j
public class QueueAdmissionScheduler {

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

    @Scheduled(fixedDelayString = "${tikkit.queue.admit-interval:1s}")
    @SchedulerLock(name = "queue-admission", lockAtMostFor = "30s", lockAtLeastFor = "900ms")
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

        long totalWaiting = 0;
        long totalAdmitted = 0;
        for (Long scheduleId : scheduleIds) {
            totalAdmitted += admitBatch(scheduleId, now);
            totalWaiting += repository.waitingSize(scheduleId);
        }

        // Gauge가 스크랩마다 Redis를 왕복하지 않도록 여기서 계산해 넘긴다.
        metrics.updateWaitingSize(totalWaiting);

        if (totalAdmitted > 0) {
            log.info("대기열 입장 배치 — 입장 {}명, 남은 대기 {}명, 대상 회차 {}개",
                    totalAdmitted, totalWaiting, scheduleIds.size());
        }
    }

    /**
     * 한 회차의 입장 처리.
     *
     * <h2>자가 치유가 공짜로 따라온다</h2>
     * 앞선 틱이 "활성에 추가"까지만 하고 끊겼으면 그 사람은 {@code waiting}에도 남아 있어서 이번
     * 후보에 다시 뽑힌다. 그래도 분기가 필요 없다 — {@code ZADD}는 이미 있는 멤버의 score를
     * 갱신할 뿐이고, 뒤따르는 {@code ZREM}이 {@code waiting}의 중복을 치운다. 즉 <b>중복을
     * 걸러내려고 후보마다 {@code ZSCORE}를 돌리지 않는다</b>(50명이면 왕복 50회다).
     * <p>
     * 대가는 그 사람을 입장 인원으로 한 번 더 센다는 것이고, 중복 자체가 프로세스가 죽어야
     * 생기는 경로라 지표 오차로 감당한다.
     *
     * @return 입장시킨 인원
     */
    private int admitBatch(long scheduleId, Instant now) {
        // ① 만료된 입장권 수거. 명령 하나로 끝난다 — 만료 시각이 ZSET score라서 가능하다.
        repository.removeExpiredPasses(scheduleId, now);

        // ② 선두 N명
        List<Long> candidates = repository.peekWaiting(scheduleId, properties.admitBatchSize());
        if (candidates.isEmpty()) {
            return 0;
        }

        // ③ 활성에 추가 → ④ 대기에서 제거. 순서가 중요한 이유는 repository.admit() Javadoc에 있다.
        repository.admit(scheduleId, candidates, now.plus(properties.passTtl()));
        metrics.recordAdmitted(candidates.size());
        return candidates.size();
    }
}
