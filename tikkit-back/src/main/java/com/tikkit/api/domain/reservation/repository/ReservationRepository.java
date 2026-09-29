package com.tikkit.api.domain.reservation.repository;

import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom {

    // 예약번호 뒤 6자리 채번용 시퀀스(V2__add_reservation_no_seq.sql)에서 다음 값을 가져온다.
    @Query(value = "select nextval('reservation_no_seq')", nativeQuery = true)
    long nextReservationNoSeq();

    // 같은 회원이 같은 등급(=같은 회차)에 이미 PENDING 선점을 갖고 있는지 확인한다 (중복 선점 방지).
    boolean existsByMemberIdAndTicketGradeIdAndStatus(Long memberId, Long ticketGradeId, ReservationStatus status);

    /**
     * 만료 시각이 지난 PENDING 예약을 일괄 EXPIRED 처리하고, 등급별 잔여 수량을 합산해 복원한다.
     * data-modifying CTE 한 문장으로 두 단계를 묶어 원자적으로 반영한다 (ReservationExpiryScheduler에서 60초마다 호출).
     * <p>
     * 두 번째 UPDATE는 등급 단위 SUM으로 합산한 뒤 반영한다 — 같은 등급에서 여러 건이 동시에 만료되면
     * {@code UPDATE ... FROM}이 대상 행 하나에 여러 소스 행을 매칭시키는데, PostgreSQL은 그중 하나만 반영하기 때문이다.
     * <p>
     * 동시성 미보장 — 이 배치와 결제(ReservationService.pay)가 같은 예약을 동시에 건드리면
     * 재고가 이중으로 복원될 수 있다. Task 018에서 재현 대상으로 남긴다 (docs/ROADMAP.md 참조).
     * <p>
     * native 쿼리라 JPA Auditing이 적용되지 않아 updated_at을 직접 넣는다. 반환값은 최종 UPDATE(ticket_grades) 기준
     * 영향받은 행 수이며, 만료된 예약 건수와는 다르다(등급이 겹치면 더 적을 수 있다).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            WITH expired AS (
                UPDATE reservations SET status = 'EXPIRED', updated_at = :now
                WHERE status = 'PENDING' AND expires_at <= :now
                RETURNING ticket_grade_id, quantity
            ), restored AS (
                SELECT ticket_grade_id, SUM(quantity) AS qty FROM expired GROUP BY ticket_grade_id
            )
            UPDATE ticket_grades tg
            SET remaining_quantity = tg.remaining_quantity + r.qty, updated_at = :now
            FROM restored r WHERE tg.id = r.ticket_grade_id
            """, nativeQuery = true)
    int expirePendingReservations(@Param("now") Instant now);
}
