package com.tikkit.api.domain.performance.dto;

/**
 * 좌석 배치도의 <b>변하지 않는 부분</b>. {@link ScheduleSeatResponse}에서 {@code status}만 뺀 것이다 (Task 029).
 *
 * <p>좌석맵 응답 8필드 중 7개가 회차 생성 후 바뀌지 않는다. 좌표·구역·열·번호는 공연장에 고정이고,
 * {@code id}({@code schedule_seats.id})와 {@code ticketGradeId}는 회차를 만들 때 정해진다. 바뀌는 건
 * {@code status} 하나이고, 선점·확정·취소·만료 네 경로가 건드린다.
 *
 * <p>그래서 이 record만 캐시하고 상태는 매번 조회해 서비스에서 합친다. 응답
 * ({@link ScheduleSeatResponse})은 그대로라 FE 계약이 바뀌지 않는다.
 *
 * <p><b>주의: 정렬 순서가 값의 일부다.</b> 이 목록은 배치도 순서({@code posY asc, posX asc})로
 * 캐시되고, 상태는 {@code Map}으로 받아 이 순서에 맞춰 붙인다. 캐시를 채우는 쿼리의
 * {@code orderBy}를 바꾸면 응답 순서가 조용히 바뀐다.
 *
 * @param id           {@code schedule_seats.id}. 선점 요청의 {@code seatIds}가 이 값이다
 * @param ticketGradeId 이 좌석이 속한 등급
 */
public record ScheduleSeatLayoutRow(
        Long id,
        String section,
        String rowLabel,
        Integer seatNumber,
        Integer posX,
        Integer posY,
        Long ticketGradeId
) {
}
