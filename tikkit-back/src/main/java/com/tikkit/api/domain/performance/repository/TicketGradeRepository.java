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

    /**
     * 잔여 수량을 조건부로 차감한다. 영향받은 행이 0이면 재고가 부족한 것이다.
     * <p>
     * 읽기와 검증, 차감을 DB가 한 문장으로 원자적으로 처리한다 — 애플리케이션이 읽은 값을 쓰지 않으므로
     * lost update가 구조적으로 불가능하다. 락을 잡지 않으니 대기도 없고 재시도도 필요 없다.
     * <p>
     * <b>호출한 뒤 {@code TicketGrade} 엔티티의 필드를 건드리면 안 된다.</b> 영속 인스턴스가 더티가 되면
     * flush 시점에 Hibernate가 메모리의 낡은 값으로 UPDATE를 또 발행해 이 조건부 UPDATE를 덮어쓴다.
     * <p>
     * 벌크 UPDATE에는 JPA Auditing이 걸리지 않아 {@code updatedAt}을 직접 넣는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TicketGrade tg set tg.remainingQuantity = tg.remainingQuantity - :quantity, tg.updatedAt = :now
            where tg.id = :id and tg.remainingQuantity >= :quantity
            """)
    int decreaseRemainingQuantity(@Param("id") Long id,
                                  @Param("quantity") int quantity,
                                  @Param("now") Instant now);

    /**
     * 취소·만료된 예약의 수량만큼 잔여 수량을 복원한다. 총 수량을 넘기면 0행이다.
     * <p>
     * 상한 조건({@code remaining + :quantity <= total})이 있어서, 이중 복원이 일어나도
     * {@code ck_ticket_grades_remaining_range} CHECK 위반(500)이 아니라 0행으로 드러난다.
     * 호출 측이 상태 전이를 조건부 UPDATE로 먼저 성공시킨 경우에만 복원하므로 실제로는 도달하지 않아야 한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TicketGrade tg set tg.remainingQuantity = tg.remainingQuantity + :quantity, tg.updatedAt = :now
            where tg.id = :id and tg.remainingQuantity + :quantity <= tg.totalQuantity
            """)
    int increaseRemainingQuantity(@Param("id") Long id,
                                  @Param("quantity") int quantity,
                                  @Param("now") Instant now);
}
