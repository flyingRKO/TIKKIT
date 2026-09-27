package com.tikkit.api.domain.reservation.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.entity.Reservation;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final TicketGradeRepository ticketGradeRepository;
    private final MemberRepository memberRepository;
    private final ReservationNoGenerator reservationNoGenerator;

    /**
     * 예매 선점(PENDING 홀드)을 생성한다.
     * 동시성 미보장 — 재고 차감(TicketGrade.decreaseRemaining)이 단순 읽기-쓰기 방식이라 동시 요청이 몰리면
     * 초과 판매가 날 수 있다. Phase 5(Task 018~020)에서 조건부 UPDATE로 개선한다.
     */
    public ReservationResponse create(Long memberId, ReservationCreateRequest request) {
        TicketGrade ticketGrade = ticketGradeRepository.findById(request.ticketGradeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Schedule schedule = ticketGrade.getSchedule();
        if (!schedule.getId().equals(request.scheduleId())) {
            // 요청한 회차와 등급이 서로 다른 회차를 가리키는 경우 — 존재하지 않는 조합으로 취급한다.
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        Instant now = Instant.now();
        if (!schedule.isBookingOpen(now)) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_OPEN);
        }

        // 같은 회원이 같은 등급에 이미 선점 중인 PENDING 예약이 있으면 중복 선점을 막는다.
        if (reservationRepository.existsByMemberIdAndTicketGradeIdAndStatus(
                memberId, ticketGrade.getId(), ReservationStatus.PENDING)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PENDING_RESERVATION);
        }

        ticketGrade.decreaseRemaining(request.quantity());

        String reservationNo = reservationNoGenerator.generate(now);
        Reservation reservation = Reservation.createPending(
                reservationNo, memberRepository.getReferenceById(memberId), ticketGrade, request.quantity(), now);
        reservationRepository.save(reservation);

        return new ReservationResponse(
                reservation.getId(), reservation.getReservationNo(), reservation.getStatus(),
                reservation.getExpiresAt(), reservation.getConfirmedAt(), reservation.getCancelledAt());
    }
}
