package com.tikkit.api.domain.reservation.scheduler;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
    private final CacheManager cacheManager;

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

        if (recalculatedPerformances > 0) {
            evictPerformanceCaches();
        }
    }

    /**
     * 공연 파생값 캐시를 비운다 (Task 029).
     *
     * <h2>왜 여기가 유일한 evict 지점인가</h2>
     * 이 서비스에는 공연 데이터를 바꾸는 쓰기 API가 없다 — 엔티티에 setter가 없고 관리자 API도
     * 없다. 목록·상세 응답에 들어가는 값 중 변하는 건 {@code status}/{@code start_date}/{@code end_date}
     * 뿐이고, 그걸 바꾸는 코드가 바로 위의 {@code recalculateDerivedFields} 하나다.
     *
     * <h2>왜 @CacheEvict가 아닌가</h2>
     * 재계산 결과(변경 행 수)를 보고 조건부로 비워야 한다. {@code @CacheEvict}의
     * {@code condition}은 메서드 <b>인자</b>만 볼 수 있고 {@code #result}는 쓸 수 없다
     * ({@code beforeInvocation = false}여도 조건 평가는 인자 기준이다). 매번 전체를 비우면
     * 대부분의 주기에서 멀쩡한 캐시를 날린다 — {@code IS DISTINCT FROM} 덕에 실제로 값이 바뀌는
     * 주기는 드물다.
     *
     * <h2>로컬 캐시에서는 이게 반쪽짜리다</h2>
     * 이 배치는 {@code @SchedulerLock}으로 <b>한 인스턴스에서만</b> 돈다. 그래서 공연 파생값
     * 캐시가 JVM 로컬이면 evict도 그 인스턴스에만 적용되고, 다른 BE는 TTL 60초가 끝날 때까지 옛
     * {@code status}를 내려준다. Redis 공유 캐시면 한 번의 evict가 전체에 반영된다 —
     * 두 저장소의 차이가 성능이 아니라 <b>정합성</b>에서 먼저 드러나는 지점이다. 그래서
     * {@code tikkit.cache.type}에는 로컬 선택지가 없다.
     *
     * <p><b>Task 036이 좌석맵에 로컬 캐시를 넣었지만 이 문단은 그대로 성립한다.</b> 그 캐시는
     * 불변 필드({@code seatLayout})만 담아서 evict 자체가 없고, 이 배치가 비우는 것은 공연
     * 파생값뿐이다 — 두 캐시가 서로 다른 {@code CacheManager}에 있는 이유가 이것이다
     * ({@code CacheConfig.seatmapCacheManager} Javadoc).
     *
     * <p>{@code CacheManager}를 직접 쓰므로 {@code type=none}일 때도 안전하다 —
     * {@code NoOpCacheManager}가 이름마다 빈 캐시를 돌려준다.
     */
    private void evictPerformanceCaches() {
        for (String cacheName : CacheNames.PERFORMANCE_DERIVED) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
        log.info("공연 파생값이 바뀌어 캐시를 비웠다 — {}", CacheNames.PERFORMANCE_DERIVED);
    }
}
