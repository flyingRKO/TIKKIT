package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.entity.SeatStatus;

import java.util.List;
import java.util.Map;

public interface ScheduleSeatRepositoryCustom {

    /**
     * 회차의 모든 좌석을 배치도 순서(앞열 → 왼쪽)로 조회한다.
     * <p>
     * Task 029에서 이걸 "불변 부분(좌표·등급)"과 "상태"로 쪼개 앞쪽만 캐시해 봤고,
     * <b>측정 후 되돌렸다.</b> 캐시 항목이 2,000석에 337,885바이트라서 Redis에서 받아
     * 역직렬화하는 비용이 이 쿼리보다 비쌌고, 그 비용은 좌석 수에 비례해 같이 커진다
     * ({@code docs/improvements/008-caching.md} 4절).
     */
    List<ScheduleSeatResponse> findSeatResponsesByScheduleId(Long scheduleId);

    /**
     * 회차의 좌석 배치 중 <b>불변 부분만</b> 배치도 순서로 조회한다 (Task 036).
     * <p>
     * 위 단일 쿼리에서 {@code status}를 뺀 것이다. 캐시 대상이고, 짝은
     * {@link #findSeatStatusesByScheduleId}다. 둘을 합치는 것은 {@code ScheduleService}가 한다 —
     * 캐시되는 조회와 그걸 쓰는 조립이 같은 메서드에 있으면 프록시를 못 탄다(008 3.1절).
     * <p>
     * <b>이 메서드를 쓰면 왕복이 1 → 2로 는다.</b> 캐시를 끄고 쓰면 순손실이다(6.63 → 8.62ms,
     * {@code schedule_seats}를 두 번 읽는다). 그래서 전략 기본값이 단일 쿼리이고, 이 경로는
     * {@code tikkit.cache.seatmap=local}일 때만 쓰인다. 그 손해를 캐시 히트가 되찾는다는 것이
     * 014의 측정이다 - 분리만 하면 -8.2%이고, 캐시를 켜면 +29.5%다.
     */
    List<ScheduleSeatLayoutRow> findSeatLayoutByScheduleId(Long scheduleId);

    /**
     * 회차의 좌석 상태. 키는 {@code schedule_seats.id} (Task 036).
     * <p>
     * 캐시하지 않는다 — 선점·결제·취소·만료가 매 순간 바꾸는 값이고, TTL을 두면 팔린 좌석이
     * 그 시간만큼 "선택 가능"으로 보인다. {@code gradePrices}가 가격만 캐시하고 잔여 수량을 매번
     * 세는 것과 같은 선이다.
     */
    Map<Long, SeatStatus> findSeatStatusesByScheduleId(Long scheduleId);

    /**
     * 회차의 등급별 예매 가능 좌석 수. 좌석이 하나도 없는 등급은 키가 없다.
     */
    Map<Long, Integer> countAvailableByScheduleId(Long scheduleId);

    /**
     * 주어진 좌석들 중 실제로 그 회차·그 등급에 속한 것의 개수.
     * <p>
     * 요청한 좌석 수와 다르면 존재하지 않거나 다른 등급의 좌석이 섞인 것이다. 이 조건은
     * {@code schedule_seats}와 {@code ticket_grades}에 걸쳐 있어 DB CHECK로 표현할 수 없다.
     */
    int countMatching(List<Long> scheduleSeatIds, Long scheduleId, Long ticketGradeId);
}
