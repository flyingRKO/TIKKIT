package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 회차 조회에서 <b>변하지 않는 부분만</b> 캐시하는 얇은 계층 (Task 029).
 *
 * <h2>왜 ScheduleService 안에 두지 않았나</h2>
 * {@code @Cacheable}은 스프링 프록시를 거쳐야 동작한다. 같은 클래스 안에서 호출하면
 * (self-invocation) 프록시를 타지 않아 <b>캐시가 조용히 동작하지 않는다</b> — 예외도 경고도 없고
 * 적중률만 영구히 0%다. {@code ScheduleService.getSeats()}는 캐시된 배치도와 실시간 상태를
 * 합쳐야 하므로 "캐시되는 조회"와 "그걸 쓰는 조립"이 같은 메서드에 있을 수 없다. 그래서
 * 캐시 대상만 별도 빈으로 뺐다.
 * <p>
 * 공연 목록·상세는 이 문제가 없어서 {@code PerformanceService}에 직접 붙였다 — 컨트롤러가
 * 바깥에서 부르므로 프록시를 탄다.
 *
 * <h2>왜 트랜잭션이 없나</h2>
 * 둘 다 단일 조회이고, 캐시 히트일 때 트랜잭션을 여는 게 목적에 반한다. 호출 측
 * ({@code ScheduleService})이 이미 {@code @Transactional(readOnly = true)}이므로 미스일 때는
 * 그 트랜잭션에 참여한다.
 *
 * <h2>캐시하지 않는 짝</h2>
 * 이 클래스가 캐시하는 것의 반대편은 {@code ScheduleService}가 매번 조회한다 —
 * 등급의 잔여 수량({@code countAvailableByScheduleId})과 좌석 상태
 * ({@code findSeatStatusesByScheduleId}). 둘 다 예매가 일어나는 순간 바뀌는 값이다.
 *
 * <h2>빈 결과를 캐시하지 않는 이유</h2>
 * 키가 경로 변수({@code scheduleId})라서 <b>사용자가 키 공간을 고를 수 있다</b>. 없는 회차로
 * 요청하면 조회가 빈 목록을 돌려주는데 그걸 캐시하면, 무작위 id를 쏘는 것만으로 캐시가 빈
 * 항목으로 채워진다(TTL 10분 동안 남는다). {@code unless}로 빈 결과를 빼서 실재하는 회차만
 * 캐시한다. 공연 목록에서 keyword 검색을 캐시 대상에서 뺀 것과 같은 이유다.
 */
@Component
@RequiredArgsConstructor
public class CatalogCache {

    private final TicketGradeRepository ticketGradeRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;

    /**
     * 회차의 등급·가격. 회차 생성 후 바뀌지 않는다 — 가격을 바꾸는 코드 경로가 없다.
     */
    @Cacheable(cacheNames = CacheNames.GRADE_PRICES, key = "#scheduleId", unless = "#result.isEmpty()")
    public List<TicketGradeRow> gradePrices(Long scheduleId) {
        return ticketGradeRepository.findRowsByScheduleId(scheduleId);
    }

    /**
     * 회차의 좌석 배치(좌표·구역·등급). 공연장 좌석이 고정이고 회차-좌석 매핑도 생성 시 정해진다.
     * <p>
     * 반환 순서가 응답 순서다. 캐시 값에 순서가 포함되어 있다는 뜻이고, 채우는 쿼리의
     * {@code orderBy}를 바꾸면 캐시를 비워야 한다.
     */
    @Cacheable(cacheNames = CacheNames.SEAT_LAYOUT, key = "#scheduleId", unless = "#result.isEmpty()")
    public List<ScheduleSeatLayoutRow> seatLayout(Long scheduleId) {
        return scheduleSeatRepository.findSeatLayoutByScheduleId(scheduleId);
    }
}
