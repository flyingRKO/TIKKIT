package com.tikkit.api.domain.reservation.strategy;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 낙관적 락 충돌로 트랜잭션을 다시 시작한 횟수. Task 019의 "재시도 횟수 비교"용이며 정리 커밋에서 삭제한다.
 * <p>
 * 비교 테스트가 전략별로 읽고 {@link #reset()}한다. 운영 지표가 필요해지면 Micrometer 카운터로
 * 옮겨야 한다 (Task 025 모니터링 구축 참조).
 */
@Component
public class RetryMetrics {

    private final AtomicInteger retryCount = new AtomicInteger();

    public void increment() {
        retryCount.incrementAndGet();
    }

    public int count() {
        return retryCount.get();
    }

    public void reset() {
        retryCount.set(0);
    }
}
