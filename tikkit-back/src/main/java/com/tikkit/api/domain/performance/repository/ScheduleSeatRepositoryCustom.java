package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.entity.SeatStatus;

import java.util.List;
import java.util.Map;

public interface ScheduleSeatRepositoryCustom {

    /**
     * 회차 좌석의 <b>불변 부분</b>(좌표·구역·등급)을 배치도 순서(앞열 → 왼쪽)로 조회한다.
     * 캐시에 들어가는 값이다 (Task 029).
     * <p>
     * 반환 순서가 그대로 응답 순서가 된다 — {@code orderBy}를 바꾸면 배치도가 뒤틀린다.
     */
    List<ScheduleSeatLayoutRow> findSeatLayoutByScheduleId(Long scheduleId);

    /**
     * 회차 좌석의 현재 상태. 키는 {@code schedule_seats.id}다 (Task 029).
     * <p>
     * 캐시하지 않는다 — 선점·확정·취소·만료가 매 순간 바꾼다. {@code seats} 조인도 정렬도 없이
     * {@code schedule_seats}만 읽는다.
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
