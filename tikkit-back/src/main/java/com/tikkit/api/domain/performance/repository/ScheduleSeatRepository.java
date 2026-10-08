package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.ScheduleSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 회차별 좌석 재고 접근. 상태 전이는 전부 조건부 UPDATE다.
 * <p>
 * 네이티브 쿼리를 쓰는 이유가 두 가지다. 하나는 QueryDSL·JPQL이 데이터 변경 CTE
 * ({@code WITH ... UPDATE ... RETURNING})를 표현할 수 없다는 것. 다른 하나는 좌석 해제를
 * {@code reservation_seats} 경유로 짜야 한다는 것이다 — {@code schedule_seats.reservation_id}에는
 * HOT 업데이트를 지키려고 인덱스를 두지 않아서, 그 컬럼만으로 찾으면 전체 스캔이 된다
 * (docs/ERD.md 2절).
 * <p>
 * 모든 쿼리가 {@code updated_at = :now}를 직접 쓴다. 벌크·네이티브 UPDATE에는 JPA Auditing이
 * 걸리지 않는다.
 */
public interface ScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long>, ScheduleSeatRepositoryCustom {

    /**
     * 좌석을 선점하고 {@code reservation_seats}에 이력을 남긴다. 반환값은 실제로 선점된 좌석 수다.
     * <p>
     * <b>{@code scheduleSeatIds}는 반드시 정렬해서 넘긴다.</b> 여러 사용자가 겹치는 좌석 집합을
     * 동시에 선점할 때 락 획득 순서를 하나로 고정해 데드락을 피하려는 것이다.
     * <p>
     * 선점 UPDATE와 이력 INSERT를 한 문장으로 묶어서, 둘 중 하나만 성공하는 상태가 생길 수 없다.
     * 반환값이 요청한 좌석 수보다 적으면 그 사이 누군가 먼저 잡은 것이므로 호출 측이 롤백시킨다.
     * <p>
     * {@code held} CTE를 한 번만 참조하므로 {@code AS MATERIALIZED}가 필요 없다 — Postgres는
     * 단일 참조 CTE를 인라인하지만, 참조가 하나면 인라인돼도 결과가 달라지지 않는다.
     * <b>나중에 이 CTE를 두 번 이상 참조하게 되면 그때는 키워드를 명시해야 한다</b>
     * (ROADMAP Task 022, V5 백필이 자동 materialize에 기댔던 전례).
     * <p>
     * {@code ticket_grade_id}를 WHERE에 다시 넣은 건 방어다. 호출 측이 등급 일치를 미리 확인하지만,
     * 그 조회와 이 UPDATE 사이에 무엇이 바뀌어도 등급 밖 좌석을 잡지 않는다. 비용은 없다.
     * <p>
     * {@code status}와 {@code reservation_id}를 한 번에 같이 쓴다 — 따로 쓰면
     * {@code ck_schedule_seats_status_holder}에 걸린다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            WITH held AS (
                UPDATE schedule_seats
                SET status = 'HELD', reservation_id = :reservationId, updated_at = :now
                WHERE id IN (:scheduleSeatIds)
                  AND status = 'AVAILABLE'
                  AND ticket_grade_id = :ticketGradeId
                RETURNING id
            )
            INSERT INTO reservation_seats (reservation_id, schedule_seat_id, price, created_at, updated_at)
            SELECT :reservationId, id, :price, :now, :now FROM held
            """, nativeQuery = true)
    int holdSeats(@Param("scheduleSeatIds") List<Long> scheduleSeatIds,
                  @Param("reservationId") Long reservationId,
                  @Param("ticketGradeId") Long ticketGradeId,
                  @Param("price") BigDecimal price,
                  @Param("now") Instant now);

    /**
     * 결제 확정 시 선점 좌석을 판매 완료로 바꾼다. 반환값은 전이된 좌석 수다.
     * <p>
     * {@code status = 'HELD'}를 WHERE에 넣어 조건부로 전이한다. 반환값이 매수와 다르면
     * 그 사이 만료 배치가 좌석을 회수한 것이다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE schedule_seats ss
            SET status = 'SOLD', updated_at = :now
            WHERE ss.id IN (SELECT rs.schedule_seat_id FROM reservation_seats rs
                            WHERE rs.reservation_id = :reservationId)
              AND ss.reservation_id = :reservationId
              AND ss.status = 'HELD'
            """, nativeQuery = true)
    int markSold(@Param("reservationId") Long reservationId, @Param("now") Instant now);

    /**
     * 취소된 예약의 좌석을 예매 가능 상태로 되돌린다. 반환값은 반환된 좌석 수다.
     * <p>
     * {@code reservation_seats} 이력 행은 지우지 않는다 — append-only라서 "이 예약이 어느 좌석을
     * 받았었다"는 기록이 남는다. 그래서 같은 좌석이 다음 사람에게 팔려도 과거 예약을 조회할 수 있다.
     * <p>
     * {@code ss.reservation_id = :reservationId}가 필요한 이유: 이력에는 과거에 이 예약이 받았던
     * 좌석이 다 남아 있어서, 그 좌석이 이미 다른 사람에게 넘어갔을 수 있다. 현재 점유자가
     * 이 예약인 좌석만 되돌린다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE schedule_seats ss
            SET status = 'AVAILABLE', reservation_id = NULL, updated_at = :now
            WHERE ss.id IN (SELECT rs.schedule_seat_id FROM reservation_seats rs
                            WHERE rs.reservation_id = :reservationId)
              AND ss.reservation_id = :reservationId
            """, nativeQuery = true)
    int releaseSeats(@Param("reservationId") Long reservationId, @Param("now") Instant now);
}
