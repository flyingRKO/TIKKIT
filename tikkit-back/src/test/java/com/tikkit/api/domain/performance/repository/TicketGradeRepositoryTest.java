package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.support.AbstractContainerTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class TicketGradeRepositoryTest extends AbstractContainerTest {

    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private TicketGradeRepository ticketGradeRepository;

    @PersistenceContext
    private EntityManager em;

    private Schedule schedule;

    @BeforeEach
    void setUp() {
        Venue venue = venueRepository.save(Venue.builder().name("테스트 공연장").address("서울").build());
        Performance performance = performanceRepository.save(Performance.builder()
                .title("테스트 공연").category(PerformanceCategory.CONCERT).venue(venue)
                .runningMinutes(120).ageRating("전체 관람가").status(PerformanceStatus.ON_SALE).build());
        schedule = scheduleRepository.save(schedule(performance, 10));
    }

    @Test
    @DisplayName("회차의 등급을 가격 내림차순(VIP → R → S)으로 조회한다")
    void 등급_가격_내림차순_정렬() {
        // given: 저장 순서를 가격과 다르게 뒤섞는다
        ticketGradeRepository.save(ticketGrade(schedule, Grade.S, "66000", 120));
        ticketGradeRepository.save(ticketGrade(schedule, Grade.VIP, "150000", 30));
        ticketGradeRepository.save(ticketGrade(schedule, Grade.R, "99000", 80));

        // when
        List<TicketGradeResponse> result = ticketGradeRepository.findResponsesByScheduleId(schedule.getId());

        // then
        assertThat(result).extracting(TicketGradeResponse::grade)
                .containsExactly(Grade.VIP, Grade.R, Grade.S);
    }

    @Test
    @DisplayName("다른 회차의 등급은 섞이지 않는다")
    void 다른_회차_등급_제외() {
        // given
        ticketGradeRepository.save(ticketGrade(schedule, Grade.VIP, "150000", 30));
        Schedule otherSchedule = scheduleRepository.save(schedule(schedule.getPerformance(), 20));
        ticketGradeRepository.save(ticketGrade(otherSchedule, Grade.R, "99000", 80));

        // when
        List<TicketGradeResponse> result = ticketGradeRepository.findResponsesByScheduleId(schedule.getId());

        // then
        assertThat(result).extracting(TicketGradeResponse::grade).containsExactly(Grade.VIP);
    }

    // ----- 조건부 UPDATE (Task 019) -----
    // 재고 차감·복원은 엔티티 메서드가 아니라 조건부 UPDATE가 담당하므로, 영향받은 행 수와 실제 DB 값으로 검증한다.
    // 벌크 UPDATE는 1차 캐시를 갱신하지 않아서 findById로는 바뀐 값을 볼 수 없다 — 스칼라 조회로 읽는다.

    @Test
    @DisplayName("재고가 충분하면 1행이 차감되고 잔여 수량이 줄어든다")
    void 조건부_차감_성공() {
        // given
        TicketGrade grade = ticketGradeRepository.save(ticketGrade(schedule, Grade.VIP, "150000", 10));

        // when
        int updated = ticketGradeRepository.decreaseRemainingQuantity(grade.getId(), 3, Instant.now());

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(remainingOf(grade.getId())).isEqualTo(7);
    }

    @Test
    @DisplayName("요청 수량이 잔여 수량과 같으면 0까지 차감된다")
    void 조건부_차감_경계() {
        // given
        TicketGrade grade = ticketGradeRepository.save(ticketGrade(schedule, Grade.R, "99000", 4));

        // when
        int updated = ticketGradeRepository.decreaseRemainingQuantity(grade.getId(), 4, Instant.now());

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(remainingOf(grade.getId())).isZero();
    }

    @Test
    @DisplayName("잔여 수량보다 많이 요청하면 0행이고 잔여 수량은 그대로다")
    void 조건부_차감_재고부족() {
        // given
        TicketGrade grade = ticketGradeRepository.save(ticketGrade(schedule, Grade.S, "77000", 2));

        // when
        int updated = ticketGradeRepository.decreaseRemainingQuantity(grade.getId(), 3, Instant.now());

        // then
        assertThat(updated).isZero();
        assertThat(remainingOf(grade.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("복원은 총 수량을 넘지 않을 때만 1행이다")
    void 조건부_복원() {
        // given
        TicketGrade grade = ticketGradeRepository.save(ticketGrade(schedule, Grade.A, "55000", 5));
        ticketGradeRepository.decreaseRemainingQuantity(grade.getId(), 2, Instant.now());

        // when
        int restored = ticketGradeRepository.increaseRemainingQuantity(grade.getId(), 2, Instant.now());

        // then
        assertThat(restored).isEqualTo(1);
        assertThat(remainingOf(grade.getId())).isEqualTo(5);
    }

    @Test
    @DisplayName("총 수량을 넘기는 복원은 0행이다 — CHECK 위반(500) 대신 영향 행 수로 드러난다")
    void 조건부_복원_상한초과() {
        // given: 차감 없이 가득 찬 상태
        TicketGrade grade = ticketGradeRepository.save(ticketGrade(schedule, Grade.VIP, "150000", 5));

        // when
        int restored = ticketGradeRepository.increaseRemainingQuantity(grade.getId(), 1, Instant.now());

        // then
        assertThat(restored).isZero();
        assertThat(remainingOf(grade.getId())).isEqualTo(5);
    }

    private int remainingOf(Long ticketGradeId) {
        return em.createQuery("select tg.remainingQuantity from TicketGrade tg where tg.id = :id", Integer.class)
                .setParameter("id", ticketGradeId)
                .getSingleResult();
    }

    private Schedule schedule(Performance performance, int daysFromNow) {
        Instant showAt = Instant.now().plus(daysFromNow, ChronoUnit.DAYS);
        return Schedule.builder()
                .performance(performance)
                .showAt(showAt)
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(showAt.minus(2, ChronoUnit.HOURS))
                .build();
    }

    private TicketGrade ticketGrade(Schedule schedule, Grade grade, String price, int quantity) {
        return TicketGrade.builder()
                .schedule(schedule)
                .grade(grade)
                .price(new BigDecimal(price))
                .totalQuantity(quantity)
                .remainingQuantity(quantity)
                .build();
    }
}
