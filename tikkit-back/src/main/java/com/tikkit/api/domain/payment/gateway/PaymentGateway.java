package com.tikkit.api.domain.payment.gateway;

import com.tikkit.api.domain.payment.entity.PaymentMethod;

import java.math.BigDecimal;

/**
 * 결제 승인·환불을 처리하는 게이트웨이. 지금은 항상 성공하는 {@link MockPaymentGateway} 하나만 구현체로 둔다.
 * 이후 실제 PG(토스/포트원 등)로 교체할 때 이 인터페이스 뒤로만 구현체를 바꾸면 되고, ReservationService는 건드리지 않는다.
 * 실제 PG 연동 시에는 approve 호출이 네트워크 I/O라 트랜잭션 밖으로 빼야 한다 — 지금은 MVP 범위상 트랜잭션 안에서 호출한다
 * (docs/PRD.md, README "알려진 한계" 참조).
 */
public interface PaymentGateway {

    /**
     * 결제를 승인하고 거래 고유 키(transactionKey)를 반환한다.
     * orderId에는 예약번호(TK...)를 넘긴다 — 토스의 orderId, 포트원의 merchant_uid에 대응되는 멱등키다.
     */
    String approve(String orderId, BigDecimal amount, PaymentMethod method);

    /** 결제를 환불 처리한다. */
    void refund(String transactionKey);
}
