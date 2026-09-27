package com.tikkit.api.domain.reservation.service;

import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 예약번호(TK{yyMMdd}-{6자리})를 생성한다.
 * 뒤 6자리는 DB 시퀀스(reservation_no_seq)로 채번해 동시 요청에서도 겹치지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ReservationNoGenerator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyMMdd");

    private final ReservationRepository reservationRepository;

    public String generate(Instant now) {
        String datePart = DATE_FORMATTER.format(now.atZone(ZONE));
        long seq = reservationRepository.nextReservationNoSeq();
        return "TK%s-%06d".formatted(datePart, seq);
    }
}
