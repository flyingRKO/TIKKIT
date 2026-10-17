package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;

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
