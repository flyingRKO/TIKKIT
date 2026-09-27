package com.tikkit.api.domain.reservation.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.entity.ReservationStatus;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import com.tikkit.api.domain.venue.entity.Venue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long SCHEDULE_ID = 10L;
    private static final Long TICKET_GRADE_ID = 100L;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private TicketGradeRepository ticketGradeRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ReservationNoGenerator reservationNoGenerator;

    @InjectMocks
    private ReservationService reservationService;

    private TicketGrade ticketGrade(Instant bookingOpenAt, Instant bookingCloseAt, int remainingQuantity) {
        Venue venue = Venue.builder().name("테스트 공연장").address("서울").build();
        Performance performance = Performance.builder()
                .title("테스트 공연")
                .category(PerformanceCategory.CONCERT)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .status(PerformanceStatus.ON_SALE)
                .build();
        Schedule schedule = Schedule.builder()
                .performance(performance)
                .showAt(bookingCloseAt.plus(1, ChronoUnit.HOURS))
                .bookingOpenAt(bookingOpenAt)
                .bookingCloseAt(bookingCloseAt)
                .build();
        ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);

        TicketGrade grade = TicketGrade.builder()
                .schedule(schedule)
                .grade(Grade.VIP)
                .price(new BigDecimal("150000"))
                .totalQuantity(10)
                .remainingQuantity(remainingQuantity)
                .build();
        ReflectionTestUtils.setField(grade, "id", TICKET_GRADE_ID);
        return grade;
    }

    @Test
    @DisplayName("예매 가능 기간이고 재고가 충분하면 재고를 차감하고 PENDING 예약을 생성한다")
    void 선점_성공() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 2);
        Member member = Member.builder()
                .email("test@tikkit.com").password("encoded").name("홍길동").phone("010-1111-2222")
                .role(MemberRole.USER).build();

        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));
        given(memberRepository.getReferenceById(MEMBER_ID)).willReturn(member);
        given(reservationNoGenerator.generate(any())).willReturn("TK260927-000001");

        // when
        ReservationResponse response = reservationService.create(MEMBER_ID, request);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.reservationNo()).isEqualTo("TK260927-000001");
        assertThat(response.expiresAt()).isNotNull();
        assertThat(grade.getRemainingQuantity()).isEqualTo(3);
        verify(reservationRepository).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 등급이면 NOT_FOUND 예외를 던지고 저장하지 않는다")
    void 등급_없음() {
        // given
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.empty());
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("요청한 scheduleId가 등급이 속한 회차와 다르면 NOT_FOUND 예외를 던진다")
    void 회차_불일치() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(999L, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("예매 시작 전이면 BOOKING_NOT_OPEN 예외를 던진다")
    void 오픈_전() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BOOKING_NOT_OPEN);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("예매 마감 후면 BOOKING_NOT_OPEN 예외를 던진다")
    void 마감_후() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BOOKING_NOT_OPEN);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 회원이 같은 등급에 이미 PENDING 예약이 있으면 DUPLICATE_PENDING_RESERVATION 예외를 던진다")
    void 중복_선점() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 5);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 1);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));
        given(reservationRepository.existsByMemberIdAndTicketGradeIdAndStatus(
                MEMBER_ID, TICKET_GRADE_ID, ReservationStatus.PENDING)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_PENDING_RESERVATION);
        verify(reservationRepository, never()).save(any());
        // 중복 체크에서 걸렸으므로 재고는 차감되지 않아야 한다
        assertThat(grade.getRemainingQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("잔여 수량보다 많이 요청하면 SOLD_OUT 예외를 던진다")
    void 재고_부족() {
        // given
        Instant now = Instant.now();
        TicketGrade grade = ticketGrade(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 1);
        ReservationCreateRequest request = new ReservationCreateRequest(SCHEDULE_ID, TICKET_GRADE_ID, 2);
        given(ticketGradeRepository.findById(TICKET_GRADE_ID)).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.create(MEMBER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOLD_OUT);
        verify(reservationRepository, never()).save(any());
    }
}
