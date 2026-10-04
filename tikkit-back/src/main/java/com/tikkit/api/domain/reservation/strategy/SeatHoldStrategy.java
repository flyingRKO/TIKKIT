package com.tikkit.api.domain.reservation.strategy;

import com.tikkit.api.domain.performance.entity.TicketGrade;

/**
 * 재고 차감 방식. Task 019에서 네 가지를 같은 조건으로 비교하기 위한 추상화다.
 * <p>
 * <b>비교 실험이 끝나면 채택한 방식만 남기고 이 인터페이스와 나머지 구현체를 삭제한다.</b>
 * 영구적인 확장 지점이 아니다 — 재고 차감 방식을 런타임에 고르는 요구사항은 없다.
 * <p>
 * 이미 조회된 {@link TicketGrade}를 받는 이유: {@code ReservationService.create()}는 판매 기간 검증과
 * 가격 스냅샷 때문에 등급을 어차피 읽어야 한다. 네 구현체가 모두 이 인스턴스를 받으면 기존 검증 순서를
 * 바꾸지 않고 차감 방식만 갈아끼울 수 있다.
 */
public interface SeatHoldStrategy {

    /**
     * 잔여 수량을 차감한다. 재고가 부족하면 {@code SOLD_OUT} 예외를 던진다.
     * 낙관적 락 구현체는 충돌 시 {@code OptimisticLockingFailureException}을 밖으로 흘려보내고,
     * 재시도는 트랜잭션 밖(ReservationService.create)에서 처리한다.
     */
    void hold(TicketGrade grade, int quantity);

    /** 비교 측정 로그·표에 쓸 이름. */
    String name();
}
