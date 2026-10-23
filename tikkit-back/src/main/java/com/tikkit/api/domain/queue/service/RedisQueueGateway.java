package com.tikkit.api.domain.queue.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.queue.config.QueueProperties;
import com.tikkit.api.domain.queue.dto.QueueStatusResponse;
import com.tikkit.api.domain.queue.repository.WaitingQueueRedisRepository;

import java.time.Instant;

/**
 * Redis ZSET 대기열 구현 (Task 031).
 *
 * <h2>조회 순서가 정해져 있다 — 활성을 먼저 본다</h2>
 * {@code active}를 먼저 확인하고 그 다음 {@code waiting}을 본다. 입장 스케줄러가
 * "활성에 추가 → 대기에서 제거" 순서로 돌기 때문에, 그 둘 사이에 끊기면 사용자가 양쪽에 남는다.
 * 활성을 먼저 보면 그 사용자는 <b>정상 입장</b>으로 읽힌다. 순서를 뒤집으면 같은 사용자가
 * "아직 대기 중"으로 보이고, 이미 받은 입장권이 무시된다.
 *
 * <h2>만료 판정 기준을 한 곳으로 모았다</h2>
 * {@code expiresAt <= now}면 만료다({@link #isValid}). 레포지토리의
 * {@code removeExpiredPasses}도 경계를 포함해 수거하므로 기준이 같다 — 다르면 "조회는 만료인데
 * 수거는 안 되는" 틈이 생기고, 그 틈에 걸린 사용자는 상태가 {@code EXPIRED}인데 재진입도 안 된다.
 */
public class RedisQueueGateway implements QueueGateway {

    /**
     * 폴링 백오프 구간. 순번이 뒤쪽일수록 느리게 묻는다.
     * <p>
     * 10만 명이 2초 고정으로 폴링하면 상태 API 하나에 50,000 RPS가 몰린다 — 좌석 API를 막아서
     * 번 여유를 상태 API가 전부 먹는 것이다. Redis는 버티지만({@code ZRANK} + {@code ZCARD})
     * Tomcat 스레드와 nginx가 먼저 죽는다. 대부분이 뒤쪽이므로 이 구간만으로 약 10,000 RPS까지
     * 떨어진다.
     * <p>
     * 숫자의 근거는 아직 없다. Task 033이 포화 테스트로 정한다 — 계약에 필드가 있으니
     * 프론트를 고치지 않고 이 상수만 바꾸면 된다.
     */
    private static final long NEAR_FRONT_RANK = 100;
    private static final long MID_RANK = 1_000;
    private static final long POLL_NEAR_FRONT_SECONDS = 2;
    private static final long POLL_MID_SECONDS = 5;
    private static final long POLL_FAR_SECONDS = 10;

    private final WaitingQueueRedisRepository repository;
    private final QueueFlagRegistry flags;
    private final QueueMetrics metrics;
    private final QueueProperties properties;

    public RedisQueueGateway(WaitingQueueRedisRepository repository,
                             QueueFlagRegistry flags,
                             QueueMetrics metrics,
                             QueueProperties properties) {
        this.repository = repository;
        this.flags = flags;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public boolean isQueueEnabled(long scheduleId) {
        return flags.enabled(scheduleId);
    }

    @Override
    public QueueStatusResponse enter(long scheduleId, long memberId) {
        if (!flags.enabled(scheduleId)) {
            return QueueStatusResponse.disabled(scheduleId);
        }
        Instant now = Instant.now();

        // 이미 입장한 사람이 "예매하기"를 다시 눌렀을 때 줄 뒤로 보내지 않는다.
        Instant passExpiresAt = repository.passExpiresAt(scheduleId, memberId);
        if (isValid(passExpiresAt, now)) {
            return QueueStatusResponse.admitted(scheduleId, repository.waitingSize(scheduleId), passExpiresAt);
        }

        // ZADD NX라 이미 줄 서 있으면 진입 시각이 보존된다 — 재호출이 순번을 밀지 않는다.
        repository.enqueue(scheduleId, memberId, now);
        return describe(scheduleId, memberId);
    }

    @Override
    public QueueStatusResponse status(long scheduleId, long memberId) {
        if (!flags.enabled(scheduleId)) {
            return QueueStatusResponse.disabled(scheduleId);
        }
        Instant now = Instant.now();

        Instant passExpiresAt = repository.passExpiresAt(scheduleId, memberId);
        if (isValid(passExpiresAt, now)) {
            return QueueStatusResponse.admitted(scheduleId, repository.waitingSize(scheduleId), passExpiresAt);
        }
        return describe(scheduleId, memberId);
    }

    @Override
    public void leave(long scheduleId, long memberId) {
        if (!flags.enabled(scheduleId)) {
            return;
        }
        repository.leave(scheduleId, memberId);
    }

    @Override
    public void requireAdmitted(long scheduleId, long memberId) {
        if (!flags.enabled(scheduleId)) {
            // 플래그가 꺼진 회차는 Redis를 건드리지 않고 통과한다. 이 한 줄이
            // "대기열을 켜지 않은 회차는 예전과 똑같이 동작한다"를 보장한다.
            return;
        }
        Instant passExpiresAt = repository.passExpiresAt(scheduleId, memberId);
        if (passExpiresAt == null) {
            metrics.recordReject(ErrorCode.QUEUE_PASS_REQUIRED);
            throw new BusinessException(ErrorCode.QUEUE_PASS_REQUIRED);
        }
        Instant now = Instant.now();
        if (!isValid(passExpiresAt, now)) {
            metrics.recordReject(ErrorCode.QUEUE_PASS_EXPIRED);
            throw new BusinessException(ErrorCode.QUEUE_PASS_EXPIRED);
        }

        // 활동하면 유지되고 가만히 있으면 만료된다. XX + GT라 수거된 사람이 되살아나지 않고
        // 순서가 뒤바뀐 요청이 만료를 되감지도 않는다.
        repository.extendPass(scheduleId, memberId, now.plus(properties.passTtl()));
        metrics.recordPass();
    }

    /** 대기 중이면 순번을, 큐에 없으면 {@code EXPIRED}를 돌려준다. */
    private QueueStatusResponse describe(long scheduleId, long memberId) {
        long totalWaiting = repository.waitingSize(scheduleId);
        Long rank = repository.waitingRank(scheduleId, memberId);
        if (rank == null) {
            return QueueStatusResponse.expired(scheduleId, totalWaiting);
        }
        return QueueStatusResponse.waiting(scheduleId, rank, totalWaiting,
                estimatedWaitSeconds(rank), pollAfterSeconds(rank));
    }

    /**
     * {@code rank}는 단조 감소하고(앞사람은 입장하거나 이탈할 뿐 늘지 않는다) 입장 속도는
     * 상수이므로 <b>이 값은 단조 비증가다</b> — 진행바가 되감기지 않는다는 계약이다.
     * 재진입하면 깨지므로, 그때는 프론트가 "순번이 초기화됐다"를 명시해야 한다.
     * <p>
     * {@code rank}가 0이면 0초가 나온다. 프론트는 이걸 "곧 입장"으로 표시한다.
     */
    private long estimatedWaitSeconds(long rank) {
        return (long) Math.ceil(rank / properties.admitRatePerSecond());
    }

    private long pollAfterSeconds(long rank) {
        if (rank < NEAR_FRONT_RANK) {
            return POLL_NEAR_FRONT_SECONDS;
        }
        if (rank < MID_RANK) {
            return POLL_MID_SECONDS;
        }
        return POLL_FAR_SECONDS;
    }

    /** 입장권 유효성. 만료 시각이 {@code now}와 같으면 만료로 본다(수거 기준과 일치). */
    private static boolean isValid(Instant passExpiresAt, Instant now) {
        return passExpiresAt != null && passExpiresAt.isAfter(now);
    }
}
