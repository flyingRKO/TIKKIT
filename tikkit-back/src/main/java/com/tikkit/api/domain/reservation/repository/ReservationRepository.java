package com.tikkit.api.domain.reservation.repository;

import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom {

    // 예약번호 뒤 6자리 채번용 시퀀스(V2__add_reservation_no_seq.sql)에서 다음 값을 가져온다.
    @Query(value = "select nextval('reservation_no_seq')", nativeQuery = true)
    long nextReservationNoSeq();

    // 같은 회원이 같은 등급(=같은 회차)에 이미 PENDING 선점을 갖고 있는지 확인한다 (중복 선점 방지).
    boolean existsByMemberIdAndTicketGradeIdAndStatus(Long memberId, Long ticketGradeId, ReservationStatus status);

    /**
     * 상태가 아직 PENDING이고 선점 시간이 남아 있을 때만 CONFIRMED로 전이한다. 0행이면 결제가 늦은 것이다.
     * <p>
     * {@code expires_at > :now}를 넣은 이유: 만료 배치가 60초 주기라 "홀드는 끝났는데 아직 PENDING"인 창이
     * 항상 존재한다. 그 창에서 들어온 결제를 DB가 직접 거절하므로 메모리 가드({@code isExpired})와
     * 판정 규칙이 일치한다.
     * <p>
     * 벌크 UPDATE에는 JPA Auditing이 걸리지 않아 {@code updatedAt}을 직접 넣는다. 서비스 트랜잭션 안에서만
     * 호출하므로 {@code @Transactional}을 붙이지 않는다 (만료 배치는 스케줄러가 직접 부르기 때문에 붙어 있다).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r set r.status = com.tikkit.api.domain.reservation.entity.ReservationStatus.CONFIRMED,
                   r.confirmedAt = :now, r.updatedAt = :now
            where r.id = :id
              and r.status = com.tikkit.api.domain.reservation.entity.ReservationStatus.PENDING
              and r.expiresAt > :now
            """)
    int confirmIfPending(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 읽은 상태가 그대로일 때만 CANCELLED로 전이한다. 0행이면 그 사이 만료 배치나 다른 요청이 선수를 친 것이다.
     * <p>
     * 1행을 바꾼 트랜잭션만 "환불·재고 복원을 수행할 권리"를 얻는다. 만료 배치는 PENDING인 행만 집어가므로
     * 우리가 먼저 CANCELLED로 바꾸면 배치가 이 행을 보지 못하고, 반대로 배치가 먼저 EXPIRED로 바꿨으면
     * 여기서 0행이 되어 복원을 건너뛴다 — <b>재고 이중 복원이 구조적으로 불가능해진다.</b>
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r set r.status = com.tikkit.api.domain.reservation.entity.ReservationStatus.CANCELLED,
                   r.cancelledAt = :now, r.updatedAt = :now
            where r.id = :id and r.status = :expected
            """)
    int cancelIfStatus(@Param("id") Long id, @Param("expected") ReservationStatus expected, @Param("now") Instant now);

    /**
     * 조건부 전이가 0행일 때 실제 DB 상태를 확인해 에러 코드를 결정한다.
     * 스칼라 프로젝션이라 1차 캐시의 낡은 엔티티를 거치지 않고 DB 값을 그대로 읽는다.
     */
    @Query("select r.status from Reservation r where r.id = :id")
    Optional<ReservationStatus> findStatusById(@Param("id") Long id);

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
