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
 * 회차별 좌석 등급과 가격.
 * <p>
 * <b>재고를 들고 있지 않다</b>. 지정석 전환 전에는 {@code totalQuantity}/{@code remainingQuantity}
 * 두 컬럼이 재고였고, 그 값을 바꾸는 메서드를 일부러 두지 않아(Task 019) 조건부 UPDATE만 담당하게
 * 했다 — 엔티티에 수량을 고치는 메서드가 있으면 더티체킹 UPDATE가 조건부 UPDATE를 덮어써서
 * 동시성 제어가 무력화된다 (비교 과정: {@code docs/improvements/002-db-lock-comparison.md}).
 * <p>
 * Task 022에서 재고가 {@code schedule_seats}의 좌석 행으로 넘어가면서 두 컬럼을 {@code V6}에서
 * 제거했다. 같은 원칙이 {@code ScheduleSeat}으로 옮겨갔다 — 그쪽에도 상태를 바꾸는 메서드가 없다.
 * "잔여 수량"은 이제 AVAILABLE 좌석 건수로 계산되며 이 엔티티에는 저장되지 않는다.
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

    @Builder
    private TicketGrade(Schedule schedule, Grade grade, BigDecimal price) {
        this.schedule = schedule;
        this.grade = grade;
        this.price = price;
    }

}
