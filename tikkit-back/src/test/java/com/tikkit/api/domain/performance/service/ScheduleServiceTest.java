package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
    private TicketGradeRepository ticketGradeRepository;

    @Mock
    private ScheduleSeatRepository scheduleSeatRepository;

    @InjectMocks
    private ScheduleService scheduleService;

    @Test
    @DisplayName("존재하지 않는 회차의 등급을 조회하면 NOT_FOUND 예외를 던지고 등급 조회는 하지 않는다")
    void 존재하지_않는_회차() {
        // given
        given(scheduleRepository.existsById(1L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> scheduleService.getTicketGrades(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(ticketGradeRepository, never()).findRowsByScheduleId(1L);
    }

    @Test
    @DisplayName("등급별 가격에 좌석 AVAILABLE 건수를 잔여 수량으로 합쳐 반환한다")
    void 등급_목록에_좌석_잔여수량_결합() {
        // given: VIP는 좌석 12석, R은 5석이 예매 가능하다
        given(scheduleRepository.existsById(1L)).willReturn(true);
        given(ticketGradeRepository.findRowsByScheduleId(1L)).willReturn(List.of(
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
    @DisplayName("좌석이 하나도 없는 등급은 잔여 수량이 0이다 — null을 내려주면 FE 매진 판정이 빗나간다")
    void 좌석_없는_등급은_잔여수량_0() {
        // given: COUNT 결과에 이 등급의 키가 없다 (전석 매진이거나 좌석이 아직 없는 경우)
        given(scheduleRepository.existsById(1L)).willReturn(true);
        given(ticketGradeRepository.findRowsByScheduleId(1L)).willReturn(List.of(
                new TicketGradeRow(10L, Grade.VIP, new BigDecimal("150000"))));
        given(scheduleSeatRepository.countAvailableByScheduleId(1L)).willReturn(Map.of());

        // when
        List<TicketGradeResponse> result = scheduleService.getTicketGrades(1L);

        // then
        assertThat(result).singleElement()
                .extracting(TicketGradeResponse::remainingQuantity).isEqualTo(0);
    }
}
