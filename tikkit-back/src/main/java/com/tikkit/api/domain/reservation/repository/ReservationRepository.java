package com.tikkit.api.domain.reservation.repository;

import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // 예약번호 뒤 6자리 채번용 시퀀스(V2__add_reservation_no_seq.sql)에서 다음 값을 가져온다.
    @Query(value = "select nextval('reservation_no_seq')", nativeQuery = true)
    long nextReservationNoSeq();

    // 같은 회원이 같은 등급(=같은 회차)에 이미 PENDING 선점을 갖고 있는지 확인한다 (중복 선점 방지).
    boolean existsByMemberIdAndTicketGradeIdAndStatus(Long memberId, Long ticketGradeId, ReservationStatus status);
}
