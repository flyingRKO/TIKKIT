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
import jakarta.persistence.Version;
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

    /**
     * 낙관적 락 버전 (Task 019 비교 실험용, V3 마이그레이션).
     * <p>
     * 이 필드가 붙으면 <b>이 엔티티의 모든 더티체킹 UPDATE에 {@code WHERE version = ?}이 자동으로 붙고
     * version이 증가한다.</b> 그래서 비교 기준선(동시성 미보장)은 더티체킹을 쓸 수 없고,
     * 조건 없는 절대값 UPDATE({@code TicketGradeRepository.overwriteRemainingQuantity})로 기존 동작을 재현한다.
     * <p>
     * 네이티브·벌크 UPDATE는 이 버전을 올려주지 않는다 — 만료 배치(data-modifying CTE)가 그 경로다.
     * 즉 낙관적 락은 애플리케이션의 모든 쓰기 경로가 JPA를 거친다는 전제에서만 동작한다.
     */
    @Version
    @Column(nullable = false)
    private Long version;

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

    /**
     * 취소·만료된 예약의 수량만큼 잔여 수량을 복원한다.
     * 동시성 미보장 — decreaseRemaining과 마찬가지로 더티체킹 방식이라 결제·만료 배치가 겹치면
     * 이중 복원이 날 수 있다. Phase 5(Task 018~020)에서 개선한다.
     */
    public void increaseRemaining(int quantity) {
        this.remainingQuantity += quantity;
    }
}
