package com.tikkit.api.domain.reservation.strategy;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 동시성을 보장하지 않는 기존 방식 (Task 012~018의 동작). 비교의 기준선이다.
 * <p>
 * 읽은 값을 메모리에서 빼고 조건 없이 그 절대값을 쓰므로, 동시 요청이 같은 값을 읽으면 나중 쓰기가
 * 먼저 쓰기를 덮어쓰는 lost update가 난다
 * (재현 결과: {@code docs/improvements/001-overselling-reproduction.md}).
 */
@Component
@RequiredArgsConstructor
public class NoLockStrategy implements SeatHoldStrategy {

    public static final String NAME = "미보장";

    private final TicketGradeRepository ticketGradeRepository;

    /**
     * <b>엔티티 더티체킹을 쓰지 않는 이유</b>
     * <p>
     * 이 커밋에서 {@code TicketGrade}에 {@code @Version}을 붙였기 때문에, 더티체킹 UPDATE에는
     * {@code WHERE version = ?}이 자동으로 붙어 <b>기준선이 저절로 낙관적 락이 되어버린다.</b>
     * 그러면 비교 표의 "미보장" 행을 측정할 수 없다. 그래서 조건 없는 절대값 UPDATE로 기존 동작을
     * 재현한다 — 발행되는 SQL은 Task 018까지의 더티체킹 UPDATE와 같다.
     */
    @Override
    public void hold(TicketGrade grade, int quantity) {
        if (grade.getRemainingQuantity() < quantity) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
        int next = grade.getRemainingQuantity() - quantity;
        ticketGradeRepository.overwriteRemainingQuantity(grade.getId(), next, Instant.now());
    }

    @Override
    public String name() {
        return NAME;
    }
}
