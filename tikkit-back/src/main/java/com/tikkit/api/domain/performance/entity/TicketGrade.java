package com.tikkit.api.domain.performance.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
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

/**
 * 회차별 좌석 등급과 재고.
 * <p>
 * <b>재고를 바꾸는 메서드를 일부러 두지 않는다</b> (Task 019). 잔여 수량 변경은
 * {@code TicketGradeRepository}의 조건부 UPDATE({@code decreaseRemainingQuantity} /
 * {@code increaseRemainingQuantity})만 담당한다. 엔티티에 수량을 고치는 메서드를 남겨두면
 * 더티체킹 UPDATE가 조건부 UPDATE를 덮어써서 동시성 제어가 무력화되므로, 그런 경로를 아예 만들지 않았다
 * (비교 과정: {@code docs/improvements/002-db-lock-comparison.md}).
 */
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

}
