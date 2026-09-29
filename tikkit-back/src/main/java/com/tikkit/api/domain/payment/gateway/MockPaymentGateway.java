package com.tikkit.api.domain.payment.gateway;

import com.tikkit.api.domain.payment.entity.PaymentMethod;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 모의 결제 게이트웨이. 실제 PG 연동 없이 항상 성공한다 (docs/PRD.md 비즈니스 규칙 참조).
 * transactionKey는 UUID로 발급한다.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String approve(String orderId, BigDecimal amount, PaymentMethod method) {
        return UUID.randomUUID().toString();
    }

    @Override
    public void refund(String transactionKey) {
        // 모의 환불이라 별도 처리 없이 항상 성공한다.
    }
}
