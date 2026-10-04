package com.tikkit.api.domain.reservation.strategy;

import com.tikkit.api.domain.performance.entity.TicketGrade;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * 비관적 락 — 재고 행에 {@code SELECT ... FOR UPDATE}를 걸어 다른 트랜잭션을 줄 세운다.
 * <p>
 * Task 019 비교 실험용이며 정리 커밋에서 삭제한다.
 */
@Component
public class PessimisticLockStrategy implements SeatHoldStrategy {

    public static final String NAME = "비관적 락";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * <b>{@code @Lock(PESSIMISTIC_WRITE)} 조회 메서드를 쓰지 않고 {@code refresh}를 쓰는 이유</b>
     * <p>
     * {@code ReservationService.create()}는 판매 기간 검증과 가격 스냅샷 때문에 이미 등급을 영속성
     * 컨텍스트에 올려둔 상태다. 이 상태에서 {@code @Lock}이 붙은 쿼리 메서드를 호출하면 SQL은
     * {@code FOR UPDATE}로 나가서 행 락은 잡히지만, Hibernate는 <b>이미 관리 중인 인스턴스의 필드를
     * DB 값으로 덮어쓰지 않는다.</b> 그래서 락을 걸고도 1차 캐시의 낡은 잔여 수량으로 계산하게 되고,
     * 비관적 락인데도 초과 판매가 난다.
     * <p>
     * {@code refresh(entity, lockMode)}는 행 락 획득과 DB 재조회를 한 문장으로 처리하므로 이 함정이 없다.
     */
    @Override
    public void hold(TicketGrade grade, int quantity) {
        entityManager.refresh(grade, LockModeType.PESSIMISTIC_WRITE);
        grade.decreaseRemaining(quantity);
    }

    @Override
    public String name() {
        return NAME;
    }
}
