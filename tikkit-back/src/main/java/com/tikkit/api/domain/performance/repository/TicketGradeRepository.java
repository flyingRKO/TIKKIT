package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.TicketGrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface TicketGradeRepository extends JpaRepository<TicketGrade, Long>, TicketGradeRepositoryCustom {

    /**
     * 잔여 수량을 조건 없이 절대값으로 덮어쓴다 — <b>동시성 미보장 기준선 재현 전용</b> (Task 019).
     * <p>
     * Task 018까지의 운영 동작(읽은 값에서 빼고 더티체킹으로 반영)과 발행되는 SQL이 같다.
     * {@code @Version}이 붙은 뒤로는 더티체킹 UPDATE에 {@code WHERE version = ?}이 자동으로 붙어
     * 기준선이 낙관적 락으로 변해버리기 때문에, 비교용 기준선은 이 조건 없는 UPDATE로 재현한다.
     * <p>
     * 벌크 UPDATE에는 JPA Auditing이 걸리지 않아 {@code updatedAt}을 직접 넣는다.
     * 정리 커밋에서 삭제한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TicketGrade tg set tg.remainingQuantity = :remainingQuantity, tg.updatedAt = :now
            where tg.id = :id
            """)
    int overwriteRemainingQuantity(@Param("id") Long id,
                                   @Param("remainingQuantity") int remainingQuantity,
                                   @Param("now") Instant now);
}
