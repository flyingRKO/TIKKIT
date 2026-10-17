package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.SeatStatus;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ScheduleSeatRepository scheduleSeatRepository;

    /**
     * 불변 부분(등급 가격, 좌석 배치)만 캐시하는 계층 (Task 029).
     * 여기서는 캐시 동작이 아니라 <b>조립 로직</b>을 보므로 목으로 둔다 —
     * 실제 캐시 적중은 {@code CacheIntegrationTest}가 Redis 컨테이너로 검증한다.
     */
    @Mock
    private CatalogCache catalogCache;

    /**
     * 좌석맵 생성 시간 Timer(Task 029)를 만들려면 실제 레지스트리가 필요하다.
     * {@code @Mock}이면 {@code Timer.builder(...).register(registry)}가 null을 돌려줘 NPE가 된다.
     * {@code ReservationServiceTest}와 같은 처리다.
     */
    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private ScheduleService scheduleService;

    @Test
    @DisplayName("등급별 가격에 좌석 AVAILABLE 건수를 잔여 수량으로 합쳐 반환한다")
    void 등급_목록에_좌석_잔여수량_결합() {
        // given: VIP는 좌석 12석, R은 5석이 예매 가능하다
        given(catalogCache.gradePrices(1L)).willReturn(List.of(
                new TicketGradeRow(10L, Grade.VIP, new BigDecimal("150000")),
                new TicketGradeRow(11L, Grade.R, new BigDecimal("99000"))));
        given(scheduleSeatRepository.countAvailableByScheduleId(1L)).willReturn(Map.of(10L, 12, 11L, 5));

        // when
        List<TicketGradeResponse> result = scheduleService.getTicketGrades(1L);

        // then
        assertThat(result).containsExactly(
                new TicketGradeResponse(10L, Grade.VIP, new BigDecimal("150000"), 12),
                new TicketGradeResponse(11L, Grade.R, new BigDecimal("99000"), 5));
    }

    @Test
    @DisplayName("등급이 조회되면 회차 존재 확인을 생략한다 — 등급이 있다는 게 회차가 있다는 증거다 (Task 029)")
    void 등급이_있으면_존재확인_생략() {
        // given
        given(catalogCache.gradePrices(1L)).willReturn(List.of(
                new TicketGradeRow(10L, Grade.VIP, new BigDecimal("150000"))));
        given(scheduleSeatRepository.countAvailableByScheduleId(1L)).willReturn(Map.of(10L, 3));

        // when
        scheduleService.getTicketGrades(1L);

        // then: 왕복 하나가 줄었다 (기존 3문장 -> 2문장)
        verify(scheduleRepository, never()).existsById(1L);
    }

    @Test
    @DisplayName("좌석이 하나도 없는 등급은 잔여 수량이 0이다 — null을 내려주면 FE 매진 판정이 빗나간다")
    void 좌석_없는_등급은_잔여수량_0() {
        // given: COUNT 결과에 이 등급의 키가 없다 (전석 매진이거나 좌석이 아직 없는 경우)
        given(catalogCache.gradePrices(1L)).willReturn(List.of(
                new TicketGradeRow(10L, Grade.VIP, new BigDecimal("150000"))));
        given(scheduleSeatRepository.countAvailableByScheduleId(1L)).willReturn(Map.of());

        // when
        List<TicketGradeResponse> result = scheduleService.getTicketGrades(1L);

        // then
        assertThat(result).singleElement()
                .extracting(TicketGradeResponse::remainingQuantity).isEqualTo(0);
    }

    @Test
    @DisplayName("등급이 비었고 회차도 없으면 NOT_FOUND를 던지고 잔여 좌석은 세지 않는다")
    void 존재하지_않는_회차_등급조회() {
        // given
        given(catalogCache.gradePrices(1L)).willReturn(List.of());
        given(scheduleRepository.existsById(1L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> scheduleService.getTicketGrades(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(scheduleSeatRepository, never()).countAvailableByScheduleId(1L);
    }

    @Test
    @DisplayName("좌석맵은 캐시된 배치에 실시간 상태를 붙여 만든다 — 배치 순서가 응답 순서다 (Task 029)")
    void 좌석맵_배치와_상태_조립() {
        // given: 배치는 posY asc, posX asc 순서로 캐시돼 있다
        given(catalogCache.seatLayout(7L)).willReturn(List.of(
                new ScheduleSeatLayoutRow(100L, "A", "1", 1, 1, 1, 10L),
                new ScheduleSeatLayoutRow(101L, "A", "1", 2, 2, 1, 10L),
                new ScheduleSeatLayoutRow(102L, "A", "2", 1, 1, 2, 11L)));
        // 상태는 Map이라 순서가 없다. 그래도 응답은 배치 순서를 따라야 한다.
        given(scheduleSeatRepository.findSeatStatusesByScheduleId(7L)).willReturn(Map.of(
                102L, SeatStatus.SOLD,
                100L, SeatStatus.AVAILABLE,
                101L, SeatStatus.HELD));

        // when
        List<ScheduleSeatResponse> result = scheduleService.getSeats(7L);

        // then
        assertThat(result).containsExactly(
                new ScheduleSeatResponse(100L, "A", "1", 1, 1, 1, SeatStatus.AVAILABLE, 10L),
                new ScheduleSeatResponse(101L, "A", "1", 2, 2, 1, SeatStatus.HELD, 10L),
                new ScheduleSeatResponse(102L, "A", "2", 1, 1, 2, SeatStatus.SOLD, 11L));
        verify(scheduleRepository, never()).existsById(7L);
    }

    @Test
    @DisplayName("상태를 찾을 수 없는 좌석은 응답에서 빠진다 — status가 null인 좌석을 내려주면 FE가 깨진다")
    void 상태없는_좌석은_제외() {
        // given: 캐시된 배치에는 있는데 상태 조회 결과에 없는 좌석(101L)이 섞였다.
        // 운영에 좌석을 물리 삭제하는 경로는 없지만, 캐시가 상태보다 오래 사는 구조라 방어한다.
        given(catalogCache.seatLayout(7L)).willReturn(List.of(
                new ScheduleSeatLayoutRow(100L, "A", "1", 1, 1, 1, 10L),
                new ScheduleSeatLayoutRow(101L, "A", "1", 2, 2, 1, 10L)));
        given(scheduleSeatRepository.findSeatStatusesByScheduleId(7L))
                .willReturn(Map.of(100L, SeatStatus.AVAILABLE));

        // when
        List<ScheduleSeatResponse> result = scheduleService.getSeats(7L);

        // then
        assertThat(result).singleElement()
                .extracting(ScheduleSeatResponse::id).isEqualTo(100L);
    }

    @Test
    @DisplayName("배치가 비었고 회차도 없으면 NOT_FOUND를 던지고 상태는 조회하지 않는다")
    void 존재하지_않는_회차_좌석맵() {
        // given
        given(catalogCache.seatLayout(7L)).willReturn(List.of());
        given(scheduleRepository.existsById(7L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> scheduleService.getSeats(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(scheduleSeatRepository, never()).findSeatStatusesByScheduleId(7L);
    }
}
