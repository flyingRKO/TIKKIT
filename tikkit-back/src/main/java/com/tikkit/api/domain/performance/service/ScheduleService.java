package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final TicketGradeRepository ticketGradeRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;

    /**
     * 회차의 등급별 가격과 예매 가능 좌석 수를 조회한다.
     * <p>
     * 지정석 전환 후 잔여 수량은 {@code ticket_grades}의 컬럼이 아니라 {@code schedule_seats}의
     * AVAILABLE 건수다. 응답 형식({@code remainingQuantity})은 그대로 둬서 FE 계약이 바뀌지 않는다 —
     * 재고의 원천만 뒤집혔고 "잔여 N석"이라는 의미는 같다.
     * <p>
     * 좌석이 하나도 없는 등급은 COUNT 결과에 키가 없으므로 0으로 채운다. {@code null}을 내려주면
     * FE의 매진 판정({@code remainingQuantity === 0})이 빗나간다.
     */
    public List<TicketGradeResponse> getTicketGrades(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        List<TicketGradeRow> rows = ticketGradeRepository.findRowsByScheduleId(scheduleId);
        Map<Long, Integer> available = scheduleSeatRepository.countAvailableByScheduleId(scheduleId);

        return rows.stream()
                .map(row -> new TicketGradeResponse(row.id(), row.grade(), row.price(),
                        available.getOrDefault(row.id(), 0)))
                .toList();
    }

    /**
     * 회차의 좌석 배치도를 조회한다.
     * <p>
     * 회차의 모든 좌석을 평면 배열로 한 번에 내려준다. 큰 공연장이면 수천 건이라 응답이 수백 KB가 되는데,
     * 배치도는 어차피 전부 그려야 하므로 페이지를 쪼개는 게 의미가 없다. 좌석 좌표는 공연장마다 고정이라
     * 상태와 분리해 캐시할 수도 있지만, 배치도 화면(Task 023)이 아직 없어서 어떤 형태가 쓰기 좋은지
     * 모르는 상태다. 단순한 쪽을 먼저 내보내고 실제 크기가 문제로 드러나면 그때 줄인다.
     */
    public List<ScheduleSeatResponse> getSeats(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return scheduleSeatRepository.findSeatResponsesByScheduleId(scheduleId);
    }
}
