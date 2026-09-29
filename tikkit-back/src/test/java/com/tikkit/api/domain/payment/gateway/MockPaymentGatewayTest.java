package com.tikkit.api.domain.payment.gateway;

import com.tikkit.api.domain.payment.entity.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();

    @Test
    @DisplayName("승인 요청은 항상 성공하고 UUID 형식의 transactionKey를 반환한다")
    void 승인_항상_성공() {
        // when
        String transactionKey = gateway.approve("TK260101-000001", new BigDecimal("300000"), PaymentMethod.CARD);

        // then
        assertThat(UUID.fromString(transactionKey)).isNotNull();
    }

    @Test
    @DisplayName("환불 요청은 예외 없이 항상 성공한다")
    void 환불_항상_성공() {
        assertThatCode(() -> gateway.refund(UUID.randomUUID().toString())).doesNotThrowAnyException();
    }
}
