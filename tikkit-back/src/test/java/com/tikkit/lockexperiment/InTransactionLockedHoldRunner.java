package com.tikkit.lockexperiment;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.service.ReservationService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * "커밋 전 락 해제" 함정을 재현한다 (Task 020 비교 실험 전용).
 * <p>
 * {@code @DistributedLock}을 쓰지 않고 손으로 잡는다. Aspect에는 트랜잭션 활성 여부 가드가 있어서
 * 이 상황을 애초에 거부하기 때문이다 — 가드가 없을 때 무슨 일이 벌어지는지를 재는 arm이다.
 * <p>
 * 순서가 핵심이다. {@code TransactionTemplate}이 트랜잭션을 먼저 열고, 그 안에서 락을 잡고,
 * {@code finally}에서 락을 풀고, <b>그 다음에</b> 람다가 끝나면서 커밋된다.
 * {@code ReservationService.create()}는 {@code REQUIRED}라 이 트랜잭션에 합류하므로 INSERT는
 * 락이 풀리는 시점에 아직 커밋되지 않았다. 다음 스레드가 락을 잡고 들어와도
 * {@code existsBy...}가 그 행을 보지 못한다.
 */
public class InTransactionLockedHoldRunner {

    private static final String KEY_PREFIX = "tikkit:lock:";

    private final TransactionTemplate transactionTemplate;
    private final RedissonClient redissonClient;
    private final ReservationService reservationService;

    public InTransactionLockedHoldRunner(TransactionTemplate transactionTemplate,
                                        RedissonClient redissonClient,
                                        ReservationService reservationService) {
        this.transactionTemplate = transactionTemplate;
        this.redissonClient = redissonClient;
        this.reservationService = reservationService;
    }

    /**
     * @param commitDelayMillis 락 해제와 커밋 사이에 끼울 지연. 0이면 그대로 둔다.
     */
    public ReservationResponse hold(Long memberId, ReservationCreateRequest request,
                                    String lockKey, long commitDelayMillis) {
        return transactionTemplate.execute(status -> {
            RLock lock = redissonClient.getLock(KEY_PREFIX + lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(30, 30, TimeUnit.SECONDS);
                if (!acquired) {
                    throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
                }
                return reservationService.create(memberId, request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("락 획득 대기 중 인터럽트", e);
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                // 락은 풀렸고 커밋은 아직이다. 이 틈이 경쟁 창이다.
                sleepQuietly(commitDelayMillis);
            }
        });
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
