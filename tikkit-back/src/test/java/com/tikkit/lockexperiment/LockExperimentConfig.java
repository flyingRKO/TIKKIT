package com.tikkit.lockexperiment;

import com.tikkit.api.domain.reservation.service.ReservationService;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 분산 락 비교 실험에 필요한 빈을 등록한다 (Task 020 비교 실험 전용).
 * <p>
 * {@code @TestConfiguration}이라 컴포넌트 스캔에 걸리지 않고 {@code @Import}한 테스트에만 적용된다. 실험 클래스들이 {@code com.tikkit.api} 밖에 있는 이유는 {@link HoldExperimentController} javadoc 참조.
 * {@code RedissonClient}는 {@code tikkit.redis.enabled=true}일 때만 있으므로, 이 설정을 import하는
 * 테스트는 {@link com.tikkit.api.support.AbstractRedisConcurrencyTest}를 상속해야 한다.
 */
@TestConfiguration
public class LockExperimentConfig {

    @Bean
    public DistributedLockedHoldFacade distributedLockedHoldFacade(ReservationService reservationService) {
        return new DistributedLockedHoldFacade(reservationService);
    }

    @Bean
    public InTransactionLockedHoldRunner inTransactionLockedHoldRunner(
            TransactionTemplate transactionTemplate,
            RedissonClient redissonClient,
            ReservationService reservationService) {
        return new InTransactionLockedHoldRunner(transactionTemplate, redissonClient, reservationService);
    }

    @Bean
    public HoldExperimentController holdExperimentController(
            ReservationService reservationService,
            DistributedLockedHoldFacade lockedFacade,
            InTransactionLockedHoldRunner inTransactionRunner) {
        return new HoldExperimentController(reservationService, lockedFacade, inTransactionRunner);
    }
}
