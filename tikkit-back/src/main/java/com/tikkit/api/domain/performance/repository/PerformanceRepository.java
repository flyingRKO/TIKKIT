package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.Performance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface PerformanceRepository extends JpaRepository<Performance, Long>, PerformanceRepositoryCustom {

    /**
     * schedules로부터 status/start_date/end_date를 재계산해 반영한다 (ReservationExpiryScheduler에서 60초마다 호출).
     * docs/PRD.md 규칙: 판매 기간(booking_open_at~booking_close_at) 안인 회차가 있으면 ON_SALE,
     * 없지만 앞으로 열릴 회차가 있으면 UPCOMING, 그 외 CLOSED. start/end_date는 show_at(KST 날짜)의 최소·최대값.
     * <p>
     * 값이 실제로 바뀐 행만 UPDATE한다(IS DISTINCT FROM) — 이게 없으면 매 주기마다 전체 공연 행을 다시 쓰게 된다.
     * 회차가 하나도 없는 공연은 계산 대상에서 빠진다(MVP에는 공연 생성 API가 없어 해당 케이스가 없다).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE performances p
            SET status = c.new_status, start_date = c.new_start_date, end_date = c.new_end_date, updated_at = :now
            FROM (
                SELECT
                    s.performance_id,
                    CASE
                        WHEN bool_or(s.booking_open_at <= :now AND :now < s.booking_close_at) THEN 'ON_SALE'
                        WHEN bool_or(s.booking_open_at > :now) THEN 'UPCOMING'
                        ELSE 'CLOSED'
                    END AS new_status,
                    min((s.show_at AT TIME ZONE 'Asia/Seoul')::date) AS new_start_date,
                    max((s.show_at AT TIME ZONE 'Asia/Seoul')::date) AS new_end_date
                FROM schedules s
                GROUP BY s.performance_id
            ) c
            WHERE p.id = c.performance_id
              AND (p.status, p.start_date, p.end_date) IS DISTINCT FROM (c.new_status, c.new_start_date, c.new_end_date)
            """, nativeQuery = true)
    int recalculateDerivedFields(@Param("now") Instant now);
}
