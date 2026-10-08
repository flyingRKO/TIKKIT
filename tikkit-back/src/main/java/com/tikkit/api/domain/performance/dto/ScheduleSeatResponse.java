package com.tikkit.api.domain.performance.dto;

import com.tikkit.api.domain.performance.entity.SeatStatus;

/**
 * 좌석 배치도 한 칸. 회차의 모든 좌석을 평면 배열로 내려준다.
 * <p>
 * 구역별로 중첩하지 않은 이유: 배치도 화면(Task 023)이 아직 없어서 어떤 묶음이 편한지 모른다.
 * 평면 배열이면 FE가 어떻게든 가공할 수 있고, 페이로드가 문제로 드러나면 그때 줄인다.
 * <p>
 * {@code id}는 {@code seats.id}가 아니라 {@code schedule_seats.id}다 — 선점 요청의
 * {@code seatIds}가 이 값이다. 좌석은 공연장 단위로 공유되지만 선점은 회차 단위라서,
 * 물리 좌석 ID를 보내면 어느 회차의 그 좌석인지 알 수 없다.
 */
public record ScheduleSeatResponse(
        Long id,
        String section,
        String rowLabel,
        Integer seatNumber,
        Integer posX,
        Integer posY,
        SeatStatus status,
        Long ticketGradeId
) {
}
