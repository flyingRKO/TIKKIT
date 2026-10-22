package com.tikkit.api.domain.performance.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;

@Getter
@Entity
@Table(name = "schedules")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id", nullable = false)
    private Performance performance;

    @Column(nullable = false)
    private Instant showAt;

    @Column(nullable = false)
    private Instant bookingOpenAt;

    @Column(nullable = false)
    private Instant bookingCloseAt;

    /**
     * 대기열 사용 여부 (Task 031). true면 좌석 조회·예매 선점이 입장권을 요구한다.
     * <p>
     * 값을 바꾸는 API는 없다 — 운영자가 SQL로 켠다. 공연 데이터 자체에 쓰기 API가 없는 것과 같은
     * 이유이고, 그래서 {@code QueueFlagRegistry}가 10초 TTL 스냅샷으로 캐시해도 문제가 없다.
     */
    @Column(nullable = false)
    private boolean queueEnabled;

    @Builder
    private Schedule(Performance performance, Instant showAt, Instant bookingOpenAt, Instant bookingCloseAt,
                     boolean queueEnabled) {
        this.performance = performance;
        this.showAt = showAt;
        this.bookingOpenAt = bookingOpenAt;
        this.bookingCloseAt = bookingCloseAt;
        this.queueEnabled = queueEnabled;
    }

    /** 예매 가능 기간(bookingOpenAt ~ bookingCloseAt) 안인지 확인한다. 마감 시각은 포함하지 않는다. */
    public boolean isBookingOpen(Instant now) {
        return !now.isBefore(bookingOpenAt) && now.isBefore(bookingCloseAt);
    }
}
