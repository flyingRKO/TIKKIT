package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 좌석 배치의 불변 부분만 캐시하는 얇은 계층 (Task 036).
 *
 * <h2>왜 ScheduleService 안에 두지 않았나</h2>
 * {@link CatalogCache}와 같은 이유다. {@code @Cacheable}은 스프링 프록시를 거쳐야 동작하고,
 * 같은 클래스 안에서 호출하면 프록시를 타지 않아 <b>예외도 경고도 없이 적중률만 영구히 0%</b>가
 * 된다. {@code ScheduleService.getSeats()}는 캐시된 배치와 실시간 상태를 합쳐야 하므로
 * "캐시되는 조회"와 "그걸 쓰는 조립"이 같은 메서드에 있을 수 없다.
 *
 * <h2>왜 cacheManager를 지정하나</h2>
 * 이 캐시만 로컬(Caffeine)이고 카탈로그 캐시는 Redis다. 이유는 {@code CacheConfig}의
 * {@code seatmapCacheManager} Javadoc에 있다 — 좌석맵은 항목이 커서 Redis 왕복이 쿼리보다
 * 비쌌고(029 기각) 로컬에서는 그 비용이 0이다. {@code CacheManager} 빈이 둘이라
 * 기본({@code @Primary})이 아닌 쪽은 이름으로 지정해야 한다.
 *
 * <h2>왜 evict가 없나</h2>
 * 담는 7필드를 런타임에 바꾸는 코드 경로가 없다. {@link CacheNames#SEAT_LAYOUT} Javadoc에
 * 근거가 있고, 그래서 이 클래스에는 {@code @CacheEvict}가 없다. 상태는
 * {@code ScheduleService}가 매 요청 읽는다.
 *
 * <h2>빈 결과를 캐시하지 않는 이유</h2>
 * 키가 경로 변수({@code scheduleId})라서 <b>사용자가 키 공간을 고를 수 있다.</b> 없는 회차로
 * 요청하면 빈 목록이 돌아오는데 그걸 캐시하면 무작위 id를 쏘는 것만으로 캐시가 채워진다.
 * {@code CatalogCache.gradePrices()}와 같은 방어이고, 여기서는 항목이 커서 더 중요하다 —
 * 상한({@code seatmapMaxEntries})이 빈 항목으로 차면 실제 회차가 밀려난다.
 */
@Component
@RequiredArgsConstructor
public class SeatLayoutCache {

    private final ScheduleSeatRepository scheduleSeatRepository;

    /**
     * 회차의 좌석 배치(좌표·구역·행·번호·등급 id). 응답 순서로 정렬돼 있다.
     */
    @Cacheable(cacheNames = CacheNames.SEAT_LAYOUT, cacheManager = "seatmapCacheManager",
            key = "#scheduleId", unless = "#result.isEmpty()")
    public List<ScheduleSeatLayoutRow> layout(Long scheduleId) {
        return scheduleSeatRepository.findSeatLayoutByScheduleId(scheduleId);
    }

}
