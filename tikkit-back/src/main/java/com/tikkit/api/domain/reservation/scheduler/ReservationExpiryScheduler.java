package com.tikkit.api.domain.reservation.scheduler;

import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 선점(PENDING) 만료와 공연 판매 상태 파생값 재계산을 처리하는 배치.
 * 두 작업은 각각 별도 트랜잭션(ReservationRepository/PerformanceRepository의 @Modifying @Transactional 메서드)으로 돈다 —
 * 한쪽이 실패해도 다른 쪽까지 롤백되지 않게 하기 위함이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiryScheduler {

    private final ReservationRepository reservationRepository;
    private final PerformanceRepository performanceRepository;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        Instant now = Instant.now();

        int releasedSeats = reservationRepository.expirePendingReservations(now);
        log.info("선점 만료 배치 실행 — 반환된 좌석 수: {}", releasedSeats);

        int recalculatedPerformances = performanceRepository.recalculateDerivedFields(now);
        log.info("공연 판매 상태 재계산 배치 실행 — 값이 바뀐 공연 수: {}", recalculatedPerformances);
    }
}
