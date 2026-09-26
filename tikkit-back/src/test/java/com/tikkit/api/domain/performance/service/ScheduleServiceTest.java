package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

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
        verify(ticketGradeRepository, never()).findResponsesByScheduleId(1L);
    }

    @Test
    @DisplayName("존재하는 회차면 등급별 가격·잔여 수량 목록을 반환한다")
    void 등급_목록_조회_성공() {
        // given
        List<TicketGradeResponse> grades = List.of(
                new TicketGradeResponse(1L, Grade.VIP, new BigDecimal("150000"), 30));
        given(scheduleRepository.existsById(1L)).willReturn(true);
        given(ticketGradeRepository.findResponsesByScheduleId(1L)).willReturn(grades);

        // when
        List<TicketGradeResponse> result = scheduleService.getTicketGrades(1L);

        // then
        assertThat(result).isEqualTo(grades);
    }
}
