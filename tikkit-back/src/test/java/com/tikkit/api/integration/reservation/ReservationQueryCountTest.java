package com.tikkit.api.integration.reservation;

import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.service.ReservationService;
import com.tikkit.api.support.AbstractConcurrencyTest;
import com.tikkit.api.support.QueryCounter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예매 선점·조회 경로가 보내는 JDBC 문장 수를 고정한다 (Task 028).
 * <p>
 * 선점은 이 서비스에서 가장 뜨거운 쓰기 경로다. 조회와 달리 캐시로 가릴 수 없으므로 왕복 수를
 * 줄이는 것이 그대로 효과가 된다. 베이스 클래스를 고른 이유는
 * {@link com.tikkit.api.integration.performance.PerformanceQueryCountTest}와 같다.
 */
class ReservationQueryCountTest extends AbstractConcurrencyTest {

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private QueryCounter queryCounter;
    private Long memberId;
    private GradeWithSeats fixture;

    @BeforeEach
    void setUpFixture() {
        queryCounter = new QueryCounter(entityManagerFactory);
        fixture = createOnSaleGradeWithSeats(10);
        memberId = memberRepository.save(Member.builder()
                .email("query-count@tikkit.test")
                .password("암호화되지않은값이지만로그인을타지않는다")
                .name("쿼리카운터")
                .phone("010-0000-0000")
                .role(MemberRole.USER)
                .build()).getId();
    }

    @Test
    @DisplayName("예매 선점은 좌석 수와 무관하게 6문장을 보낸다")
    void 예매_선점_6문장() {
        long queries = queryCounter.count(() -> reservationService.create(memberId, request(fixture, 2)));

        // 등급+회차 fetch join / 중복 선점 확인 / 좌석 소속 확인 / 예약번호 nextval /
        // 예약 INSERT / 좌석 선점 CTE = 6.
        // Task 028 전에는 7이었다 — findById로 등급만 읽고 schedule.isBookingOpen()에서
        // Schedule 프록시가 초기화되면서 SELECT가 한 번 더 나갔다.
        assertThat(queries).isEqualTo(6);
    }

    @Test
    @DisplayName("예매 선점 문장 수는 고른 좌석 수가 1석이든 4석이든 같다")
    void 예매_선점_좌석수_무관() {
        // 등급을 따로 두는 이유: 중복 선점 가드가 (회원, 등급, PENDING) 단위라
        // 같은 등급에 두 번 선점하면 두 번째가 409로 떨어진다.
        GradeWithSeats other = createOnSaleGradeWithSeats(10);

        long oneSeat = queryCounter.count(() -> reservationService.create(memberId, request(fixture, 1)));
        long fourSeats = queryCounter.count(() -> reservationService.create(memberId, request(other, 4)));

        // 좌석마다 문장이 늘어나면 선점을 루프로 돌린 것이다. holdSeats는 CTE 한 방이어야 한다.
        assertThat(oneSeat).isEqualTo(fourSeats);
    }

    @Test
    @DisplayName("내 예매 목록은 행마다 공연·회차·등급을 당겨오지 않고 content 1문장으로 끝낸다")
    void 내_예매_목록_1문장() {
        reservationService.create(memberId, request(fixture, 2));

        long queries = queryCounter.count(() -> reservationService.getMyReservations(memberId, null, 0, 20));

        // 응답에 공연 제목·회차 시각·등급이 들어가지만 DTO 프로젝션이 조인으로 한 번에 뽑는다.
        // count는 PageableExecutionUtils가 생략한다 (첫 페이지가 꽉 차지 않았다).
        assertThat(queries).isEqualTo(1);
    }

    @Test
    @DisplayName("내 예매 목록은 페이지가 꽉 차면 content와 count 2문장을 보낸다")
    void 내_예매_목록_2문장() {
        reservationService.create(memberId, request(fixture, 2));

        long queries = queryCounter.count(() -> reservationService.getMyReservations(memberId, null, 0, 1));

        assertThat(queries).isEqualTo(2);
    }

    @Test
    @DisplayName("예매 상세는 예약 본문·결제·좌석 3문장을 보낸다")
    void 예매_상세_3문장() {
        Long reservationId = reservationService.create(memberId, request(fixture, 2)).id();

        long queries = queryCounter.count(() -> reservationService.getMyReservation(memberId, reservationId));

        // 본문은 schedule/performance/ticketGrade를 fetch join으로 한 번에 받고,
        // 결제(없으면 null)와 좌석 목록이 각각 1문장이다.
        assertThat(queries).isEqualTo(3);
    }

    private ReservationCreateRequest request(GradeWithSeats target, int seatCount) {
        List<Long> seatIds = target.scheduleSeatIds().subList(0, seatCount);
        return new ReservationCreateRequest(target.scheduleId(), target.gradeId(), seatCount, seatIds);
    }
}
