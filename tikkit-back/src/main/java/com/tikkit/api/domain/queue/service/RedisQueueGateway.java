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
     *
     * <h2>왜 백오프가 필요한가</h2>
     * 10만 명이 2초 고정으로 폴링하면 상태 API 하나에 50,000 RPS가 몰린다 — 좌석 API를 막아서
     * 번 여유를 상태 API가 전부 먹는 것이다. Redis는 버티지만 Tomcat 스레드와 nginx가 먼저 죽는다.
     *
     * <h2>구간이 하나 부족했다 (Task 033 실측)</h2>
     * 구간이 닫힌 식이라 "대기 N명일 때 필요한 RPS"가 계산된다.
     * <pre>
     *   RPS(N) = min(N,100)/2 + min(max(N-100,0),900)/5
     *          + min(max(N-1000,0),9000)/10 + max(N-10000,0)/60
     * </pre>
     * 3구간(2/5/10초)이던 Task 031 설계는 <b>N=100,000에서 10,130 RPS</b>를 요구했다.
     * 그런데 실측한 상태 API 처리량은 <b>2,986 req/s</b>(p95 3.63ms, 실패 0%)였고, 그 값으로
     * 위 식을 역으로 풀면 3구간이 지탱하는 한계는 <b>약 28,000명</b>이다 — 목표 10만 명의 1/3이다.
     * <p>
     * 그래서 {@code rank >= 10,000}에 60초 구간을 더했다. 같은 식으로 N=100,000의 요구가
     * <b>2,630 RPS</b>로 떨어져 실측값 안에 들어온다. 30초로 두면 4,130 RPS인데 그건 측정으로
     * 증명되지 않은 영역이라, <b>증명된 쪽</b>을 골랐다.
     * <p>
     * 1,000~10,000을 10초로 남긴 이유는 체감이다. 입장 속도가 50~84명/s이므로 rank 10,000의
     * 예상 대기가 119~200초인데, 그 사람에게 60초를 주면 남은 대기 동안 화면이 두세 번만 갱신된다.
     * 60초는 예상 대기가 분 단위로 넘어가는 rank 10,000 이상에만 쓴다.
     *
     * <h2>깊이를 100배 늘려도 요청당 비용이 같았다</h2>
     * 대기 1,000명과 100,000명에서 같은 도착률로 재면 상태 p95 비율이 0.95~1.03이다.
     * {@code ZRANK}가 O(log N)이니 이론상 1.66배여야 하는데 보이지 않는다 — <b>순번 계산이
     * 요청 비용에서 차지하는 몫이 측정 한계 아래</b>라는 뜻이고, 그래서 위 역산이 성립한다.
     *
     * <h2>2,986 req/s는 하한이다 — 서버 한계가 아니다</h2>
     * 그 지점에서 BE CPU는 인스턴스당 210%였다(예매 포화 때는 414%였다). 호스트 16코어 중
     * 합계 6.9코어를 썼고 postgres는 5.5%였다 — 상태 조회가 DB를 안 건드린다는 뜻이다.
     * 더 올리면 <b>부하 생성기가 먼저 막혔다</b>: k6의 VU 상한을 600 → 2,000으로 올리자 처리량이
     * 2,898 → 2,684 req/s로 <b>떨어졌다.</b> 즉 진짜 상한은 더 위에 있고 여기 적은 숫자는
     * "이 호스트에서 증명된 범위"다. 전용 부하 노드가 있으면 60초를 당길 여지가 있다.
     */
    private static final long NEAR_FRONT_RANK = 100;
    private static final long MID_RANK = 1_000;
    private static final long FAR_RANK = 10_000;
    private static final long POLL_NEAR_FRONT_SECONDS = 2;
    private static final long POLL_MID_SECONDS = 5;
    private static final long POLL_FAR_SECONDS = 10;
    private static final long POLL_DEEP_SECONDS = 60;

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
        if (rank < FAR_RANK) {
            return POLL_FAR_SECONDS;
        }
        return POLL_DEEP_SECONDS;
    }

    /** 입장권 유효성. 만료 시각이 {@code now}와 같으면 만료로 본다(수거 기준과 일치). */
    private static boolean isValid(Instant passExpiresAt, Instant now) {
        return passExpiresAt != null && passExpiresAt.isAfter(now);
    }
}
