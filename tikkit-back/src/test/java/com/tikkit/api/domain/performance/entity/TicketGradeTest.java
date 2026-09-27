package com.tikkit.api.domain.performance.entity;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketGradeTest {

    private TicketGrade grade(int remainingQuantity) {
        return TicketGrade.builder()
                .grade(Grade.VIP)
                .price(new BigDecimal("150000"))
                .totalQuantity(10)
                .remainingQuantity(remainingQuantity)
                .build();
    }

    @Test
    @DisplayName("잔여 수량이 요청 수량과 같으면 0까지 차감된다")
    void 잔여_수량과_동일하게_차감() {
        // given
        TicketGrade grade = grade(3);

        // when
        grade.decreaseRemaining(3);

        // then
        assertThat(grade.getRemainingQuantity()).isZero();
    }

    @Test
    @DisplayName("잔여 수량보다 많이 요청하면 SOLD_OUT 예외를 던지고 수량은 그대로다")
    void 잔여_수량_초과_요청() {
        // given
        TicketGrade grade = grade(2);

        // when & then
        assertThatThrownBy(() -> grade.decreaseRemaining(3))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOLD_OUT);
        assertThat(grade.getRemainingQuantity()).isEqualTo(2);
    }
}
