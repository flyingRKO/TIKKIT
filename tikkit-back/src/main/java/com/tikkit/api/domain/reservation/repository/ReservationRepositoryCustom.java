package com.tikkit.api.domain.reservation.repository;

import com.tikkit.api.domain.reservation.dto.ReservationSummaryResponse;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
}
