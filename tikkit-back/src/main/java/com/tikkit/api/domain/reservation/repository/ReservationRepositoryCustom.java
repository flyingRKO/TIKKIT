package com.tikkit.api.domain.reservation.repository;

import com.tikkit.api.domain.reservation.dto.ReservationSeatResponse;
import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ReservationRepositoryCustom {

    /**
     * 특정 회원의 예약 목록을 상태(선택)로 필터링해 최신순으로 조회한다.
     */
    Page<ReservationSummaryResponse> searchMine(Long memberId, ReservationStatus status, Pageable pageable);

    /**
     * 소유자 검증을 포함해 예약 상세를 조회한다. schedule/performance/ticketGrade를 함께 fetch join한다.
     * 다른 회원 소유면 empty를 반환한다 — 서비스에서 404로 변환한다 (소유권 노출 방지, docs/PRD.md 참조).
     */
    Optional<Reservation> findMineWithDetails(Long id, Long memberId);

    /**
     * 예약이 받은 좌석을 배치도 순서(앞열 → 왼쪽)로 조회한다.
     * <p>
     * {@code reservation_seats}가 "예약 → 좌석"을 찾는 유일한 경로다 —
     * {@code schedule_seats.reservation_id}에는 HOT 업데이트를 지키려고 인덱스를 걸지 않았다
     * ({@code ReservationSeat} Javadoc 참조).
     * <p>
     * 상세 조회 1건당 쿼리 1회라 N+1이 아니다. 예약 본문 조회와 한 쿼리로 합치지 않은 이유는
     * 좌석이 1:N이라 조인하면 예약 컬럼이 좌석 수만큼 중복돼서다.
     */
    List<ReservationSeatResponse> findSeats(Long reservationId);
}
