package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.config.CacheProperties;
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
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ScheduleSeatRepository scheduleSeatRepository;

    /**
     * 등급 가격(불변)만 캐시하는 계층 (Task 029). 좌석맵은 캐시 대상이 아니다.
     * 여기서는 캐시 동작이 아니라 <b>조립 로직</b>을 보므로 목으로 둔다 —
     * 실제 캐시 적중은 {@code CacheIntegrationTest}가 Redis 컨테이너로 검증한다.
     */
    @Mock
    private CatalogCache catalogCache;

    /**
     * 좌석 배치(불변)만 캐시하는 계층 (Task 036). 기본 전략이 단일 쿼리라 아래 대부분의
     * 테스트에서는 쓰이지 않고, 조립 경로를 보는 테스트만 직접 전략을 바꿔 쓴다.
     */
    @Mock
    private SeatLayoutCache seatLayoutCache;

    /**
     * 좌석맵 전략을 들고 있다 (Task 036). 기본값 {@code SINGLE}이라 기존 테스트는 그대로다 —
     * 측정 before가 사라지지 않게 기본을 단일 쿼리로 둔 결정이 여기서도 그대로 드러난다.
     */
    @Spy
    private CacheProperties cacheProperties = new CacheProperties(
            CacheProperties.CacheType.NONE, CacheProperties.SeatmapStrategy.SINGLE, 0, null);

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
    @DisplayName("좌석맵은 캐시를 거치지 않는다 — 조회 결과를 그대로 내려준다")
    void 좌석맵은_캐시를_거치지_않는다() {
        // Task 029에서 배치(불변)와 상태(실시간)로 쪼개 앞쪽만 캐시해 봤고 측정 후 되돌렸다.
        // 캐시 항목이 2,000석에 337,885바이트라 Redis 왕복·역직렬화가 쿼리보다 비쌌다
        // (docs/improvements/008-caching.md 4절). 그래서 CatalogCache를 타지 않는다.
        given(scheduleRepository.existsById(7L)).willReturn(true);
        given(scheduleSeatRepository.findSeatResponsesByScheduleId(7L)).willReturn(List.of(
                new ScheduleSeatResponse(100L, "A", "1", 1, 1, 1, SeatStatus.AVAILABLE, 10L),
                new ScheduleSeatResponse(101L, "A", "1", 2, 2, 1, SeatStatus.HELD, 10L)));

        // when
        List<ScheduleSeatResponse> result = scheduleService.getSeats(7L);

        // then
        assertThat(result).extracting(ScheduleSeatResponse::status)
                .containsExactly(SeatStatus.AVAILABLE, SeatStatus.HELD);
        verifyNoInteractions(catalogCache);
    }

    @Test
    @DisplayName("존재하지 않는 회차의 좌석맵은 NOT_FOUND를 던지고 좌석은 조회하지 않는다")
    void 존재하지_않는_회차_좌석맵() {
        // given
        given(scheduleRepository.existsById(7L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> scheduleService.getSeats(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(scheduleSeatRepository, never()).findSeatResponsesByScheduleId(7L);
    }

    /**
     * 좌석맵 전략만 {@code LOCAL}로 바꾼 서비스. 전략이 생성자에서 필드로 굳으므로
     * ({@code ScheduleService} 생성자 주석) 인스턴스를 따로 만든다.
     */
    private ScheduleService localStrategyService() {
        return new ScheduleService(scheduleRepository, scheduleSeatRepository, catalogCache, seatLayoutCache,
                new CacheProperties(CacheProperties.CacheType.NONE,
                        CacheProperties.SeatmapStrategy.LOCAL, 0, null),
                meterRegistry);
    }

    @Test
    @DisplayName("로컬 전략은 캐시된 배치에 실시간 상태를 합친다 — 상태는 캐시에서 오지 않는다")
    void 로컬_전략_조립() {
        // given: 배치는 캐시가, 상태는 DB가 들고 있다. 이 분담이 Task 036 설계의 전부다 —
        // 상태까지 캐시하면 팔린 좌석이 TTL 동안 "선택 가능"으로 보인다.
        given(scheduleRepository.existsById(7L)).willReturn(true);
        given(seatLayoutCache.layout(7L)).willReturn(List.of(
                new ScheduleSeatLayoutRow(100L, "A", "1", 1, 1, 1, 10L),
                new ScheduleSeatLayoutRow(101L, "A", "1", 2, 2, 1, 10L)));
        given(scheduleSeatRepository.findSeatStatusesByScheduleId(7L)).willReturn(Map.of(
                100L, SeatStatus.AVAILABLE,
                101L, SeatStatus.HELD));

        // when
        List<ScheduleSeatResponse> result = localStrategyService().getSeats(7L);

        // then: 순서는 배치가 정한다(DB가 pos_y, pos_x로 정렬해 캐시에 넣었다).
        assertThat(result).extracting(ScheduleSeatResponse::id).containsExactly(100L, 101L);
        assertThat(result).extracting(ScheduleSeatResponse::status)
                .containsExactly(SeatStatus.AVAILABLE, SeatStatus.HELD);
        // 단일 쿼리는 타지 않아야 한다 — 타면 분리의 의미가 없고 왕복이 3이 된다.
        verify(scheduleSeatRepository, never()).findSeatResponsesByScheduleId(7L);
    }

    @Test
    @DisplayName("캐시된 배치에만 있는 좌석은 응답에서 뺀다 — 없는 좌석을 AVAILABLE로 내려주지 않는다")
    void 로컬_전략_캐시_불일치() {
        // 좌표·등급을 바꾸는 코드 경로가 없어서 실제로는 일어나지 않는 상태다(그래서 이 캐시에
        // evict가 없다). 그 가정이 틀렸을 때 **조용히 틀리지 않는지**를 고정한다 — 상태를
        // 기본값으로 채우면 이미 없는 좌석이 선택 가능으로 보이고, 사용자는 클릭해서야 안다.
        // 통합 테스트로는 만들 수 없는 조건이라 여기서만 잡힌다.
        given(scheduleRepository.existsById(7L)).willReturn(true);
        given(seatLayoutCache.layout(7L)).willReturn(List.of(
                new ScheduleSeatLayoutRow(100L, "A", "1", 1, 1, 1, 10L),
                new ScheduleSeatLayoutRow(101L, "A", "1", 2, 2, 1, 10L)));
        given(scheduleSeatRepository.findSeatStatusesByScheduleId(7L))
                .willReturn(Map.of(100L, SeatStatus.AVAILABLE));

        // when
        List<ScheduleSeatResponse> result = localStrategyService().getSeats(7L);

        // then
        assertThat(result).extracting(ScheduleSeatResponse::id).containsExactly(100L);
    }

    @Test
    @DisplayName("로컬 전략에서도 없는 회차는 NOT_FOUND다 — 조립 경로가 존재 확인을 건너뛰지 않는다")
    void 로컬_전략_존재하지_않는_회차() {
        given(scheduleRepository.existsById(7L)).willReturn(false);

        assertThatThrownBy(() -> localStrategyService().getSeats(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(seatLayoutCache);
    }
}
