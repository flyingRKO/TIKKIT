package com.tikkit.api.support;

import com.tikkit.api.domain.payment.entity.PaymentMethod;
import com.tikkit.api.domain.payment.gateway.PaymentGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 동시성 재현 테스트용 설정 (Task 018).
 * <p>
 * {@code @TestConfiguration}이라 컴포넌트 스캔에 걸리지 않고, {@code @Import}한 테스트에만 적용된다.
 * 운영 코드의 {@code MockPaymentGateway}는 그대로 두고 주입 지점만 가로챈다.
 */
@TestConfiguration
public class ConcurrencyTestConfig {

    @Bean
    @Primary
    public DelayedPaymentGateway delayedPaymentGateway() {
        return new DelayedPaymentGateway();
    }

    /**
     * 승인에 시간이 걸리는 테스트용 결제 게이트웨이.
     * <p>
     * 실제 PG(토스·포트원 등)의 승인 왕복 지연을 모사한다. {@code ReservationService.pay()}는 이 호출을
     * <b>트랜잭션 안에서</b> 하므로, 승인을 기다리는 동안 예약 상태 조회와 {@code confirm()} 사이의 틈이
     * 그만큼 벌어진다 — 결제-만료 배치 경쟁이 운영에서 발생하는 실제 원인이다
     * (README "알려진 한계: 결제 게이트웨이 호출이 트랜잭션 안에 있음" 참조).
     * <p>
     * 기본 지연은 0이라 설정하지 않으면 {@code MockPaymentGateway}와 동일하게 즉시 응답한다.
     * 스프링 컨텍스트가 캐시되어 테스트 간에 공유되므로, 지연을 건 테스트는 끝나고 반드시 되돌려야 한다.
     */
    public static class DelayedPaymentGateway implements PaymentGateway {

        private final AtomicLong approveDelayMillis = new AtomicLong(0);

        public void setApproveDelay(Duration delay) {
            approveDelayMillis.set(delay.toMillis());
        }

        public void resetApproveDelay() {
            approveDelayMillis.set(0);
        }

        @Override
        public String approve(String orderId, BigDecimal amount, PaymentMethod method) {
            sleepQuietly(approveDelayMillis.get());
            return UUID.randomUUID().toString();
        }

        @Override
        public void refund(String transactionKey) {
            // 환불은 이 테스트의 관심사가 아니라 지연 없이 즉시 성공시킨다.
        }

        private void sleepQuietly(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("결제 승인 지연 중 인터럽트", e);
            }
        }
    }
}
