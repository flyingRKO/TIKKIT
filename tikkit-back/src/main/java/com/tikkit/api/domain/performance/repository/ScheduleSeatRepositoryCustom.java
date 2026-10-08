package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;

import java.util.List;
import java.util.Map;

public interface ScheduleSeatRepositoryCustom {

    /**
     * 회차의 모든 좌석을 배치도 순서(앞열 → 왼쪽)로 조회한다.
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
