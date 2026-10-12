package com.tikkit.api.domain.reservation.scheduler;

import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    // BE가 2대 이상일 때 한 인스턴스만 실행하게 한다 (Task 026).
    //
    // lockAtLeastFor가 중요하다. 이 작업은 수십 ms에 끝나는데 락을 즉시 놓으면 같은 주기 안에서
    // 다른 인스턴스가 다시 집어서 **중복 실행이 "해결된 척" 하다가 그대로 남는다**.
    // 55초를 잡아 다음 주기(60초)까지 아무도 못 들어오게 한다.
    //
    // lockAtMostFor는 이 인스턴스가 락을 들고 죽었을 때 풀리는 시한이다. 5분으로 둔 건
    // 작업 시간(수십 ms)보다 한참 길게 잡아 "느린 실행"을 "죽음"으로 오판하지 않기 위함이다.
    // 대가는 실제로 죽었을 때 최대 5분간 배치가 멈춘다는 것이고, 만료 처리는 그 정도 지연을
    // 감당할 수 있다(선점 만료 시각 자체가 분 단위다).
    @SchedulerLock(name = "reservation-expiry", lockAtMostFor = "5m", lockAtLeastFor = "55s")
    public void run() {
        Instant now = Instant.now();

        int releasedSeats = reservationRepository.expirePendingReservations(now);
        log.info("선점 만료 배치 실행 — 반환된 좌석 수: {}", releasedSeats);

        int recalculatedPerformances = performanceRepository.recalculateDerivedFields(now);
        log.info("공연 판매 상태 재계산 배치 실행 — 값이 바뀐 공연 수: {}", recalculatedPerformances);
    }
}
