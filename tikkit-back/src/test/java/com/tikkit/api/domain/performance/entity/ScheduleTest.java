package com.tikkit.api.domain.performance.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTest {

    private Schedule schedule(Instant bookingOpenAt, Instant bookingCloseAt) {
        return Schedule.builder()
                .showAt(bookingCloseAt.plus(1, ChronoUnit.HOURS))
                .bookingOpenAt(bookingOpenAt)
                .bookingCloseAt(bookingCloseAt)
                .build();
    }

    @Test
    @DisplayName("예매 오픈 시각과 정확히 같으면 예매 가능하다 (오픈 시각 포함)")
    void 오픈_시각_경계_포함() {
        // given
        Instant openAt = Instant.now();
        Schedule schedule = schedule(openAt, openAt.plus(1, ChronoUnit.DAYS));

        // when & then
        assertThat(schedule.isBookingOpen(openAt)).isTrue();
    }

    @Test
    @DisplayName("예매 마감 시각과 정확히 같으면 예매 불가능하다 (마감 시각 제외)")
    void 마감_시각_경계_제외() {
        // given
        Instant closeAt = Instant.now();
        Schedule schedule = schedule(closeAt.minus(1, ChronoUnit.DAYS), closeAt);

        // when & then
        assertThat(schedule.isBookingOpen(closeAt)).isFalse();
    }
}
