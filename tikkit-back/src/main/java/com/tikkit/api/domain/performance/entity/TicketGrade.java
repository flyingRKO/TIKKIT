package com.tikkit.api.domain.performance.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "ticket_grades")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketGrade extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Grade grade;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer totalQuantity;

    @Column(nullable = false)
    private Integer remainingQuantity;

    @Builder
    private TicketGrade(Schedule schedule, Grade grade, BigDecimal price, Integer totalQuantity,
                         Integer remainingQuantity) {
        this.schedule = schedule;
        this.grade = grade;
        this.price = price;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = remainingQuantity;
    }

    /**
     * 잔여 수량을 차감한다.
     * 동시성 미보장 — 단순히 읽은 값을 그대로 빼고 더티체킹으로 반영하는 방식이라 동시 요청이 몰리면
     * lost update(초과 판매)가 발생할 수 있다. Phase 5(Task 018~020)에서 조건부 UPDATE로 개선한다.
     */
    public void decreaseRemaining(int quantity) {
        if (remainingQuantity < quantity) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
        this.remainingQuantity -= quantity;
    }
}
