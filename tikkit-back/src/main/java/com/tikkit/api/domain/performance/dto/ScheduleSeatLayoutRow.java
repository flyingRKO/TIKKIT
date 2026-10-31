package com.tikkit.api.domain.performance.dto;

/**
 * 좌석 배치의 <b>변하지 않는 부분</b> (Task 036).
 *
 * <p>{@link ScheduleSeatResponse} 8필드 중 {@code status}만 뺀 7필드다. 이 7개를 런타임에 바꾸는
 * 코드 경로가 없어서 캐시해도 무효화가 필요 없다 — 좌석 상태를 바꾸는 UPDATE 3개
 * ({@code holdSeats}/{@code markSold}/{@code releaseSeats})와 만료 배치가 전부
 * {@code status}·{@code reservation_id}·{@code updated_at}만 SET하고, {@code Seat}과
 * {@code ScheduleSeat} 엔티티에 좌표·등급 변경 메서드가 없다.
 *
 * <p>Task 029가 같은 모양의 record를 만들어 썼다가 측정 후 지웠다. 그때 기각 근거는 이 값을
 * <b>Redis에</b> 두는 비용이었고(2,000석 337,885바이트의 왕복·역직렬화), 같은 측정에서 로컬
 * 캐시는 가장 빨랐다. 036이 그 로컬 쪽을 포화 구간에서 다시 본다
 * ({@code docs/improvements/008-caching.md} 4.1절).
 *
 * <p>정렬은 DB가 한다({@code ORDER BY pos_y, pos_x}). 캐시에 들어가는 리스트가 이미 응답 순서라
 * 조립할 때 다시 정렬하지 않는다 — 2,000건 정렬은 실측 0.05ms로 작지만, 캐시 히트 경로에서
 * 굳이 되풀이할 이유가 없다.
 *
 * @param scheduleSeatId {@code schedule_seats.id}. 응답의 {@code id}이고 선점 요청이 쓰는 값이다
 *                       ({@code seats.id}가 아니다)
 */
public record ScheduleSeatLayoutRow(
        Long scheduleSeatId,
        String section,
        String rowLabel,
        Integer seatNumber,
        Integer posX,
        Integer posY,
        Long ticketGradeId
) {
}
