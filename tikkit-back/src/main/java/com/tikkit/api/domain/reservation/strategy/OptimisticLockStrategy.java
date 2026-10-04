package com.tikkit.api.domain.reservation.strategy;

import com.tikkit.api.domain.performance.entity.TicketGrade;
import org.springframework.stereotype.Component;

/**
 * 낙관적 락 — {@code TicketGrade.version}(V3)으로 충돌을 감지하고 재시도한다.
 * <p>
 * Task 019 비교 실험용이며 정리 커밋에서 삭제한다.
 */
@Component
public class OptimisticLockStrategy implements SeatHoldStrategy {

    public static final String NAME = "낙관적 락";

    /**
     * 더티체킹을 그대로 쓴다. {@code @Version} 덕분에 flush 시점의 UPDATE에
     * {@code WHERE id = ? AND version = ?}이 붙고, 그 사이 다른 트랜잭션이 커밋했으면 0행이 되어
     * Hibernate가 {@code OptimisticLockException}을 던진다.
     * <p>
     * <b>재시도는 여기서 하지 않는다.</b> 충돌은 커밋(flush) 시점에 터지고 그 트랜잭션은 이미
     * rollback-only로 표시되므로, 재시도는 트랜잭션을 완전히 끝낸 뒤 새로 시작해야 한다.
     * {@code ReservationService.create()}의 루프가 그 역할을 한다.
     */
    @Override
    public void hold(TicketGrade grade, int quantity) {
        grade.decreaseRemaining(quantity);
    }

    @Override
    public String name() {
        return NAME;
    }
}
