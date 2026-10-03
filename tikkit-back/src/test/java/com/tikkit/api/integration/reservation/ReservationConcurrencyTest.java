package com.tikkit.api.integration.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.domain.payment.entity.PaymentMethod;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.reservation.dto.PaymentRequest;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.repository.ReservationRepository;
import com.tikkit.api.support.AbstractConcurrencyTest;
import com.tikkit.api.support.ConcurrencyTestConfig;
import com.tikkit.api.support.ConcurrencyTestConfig.DelayedPaymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 예매 선점의 동시성 미보장을 재현하는 테스트 (Task 018).
 * <p>
 * <b>이 테스트는 "고쳐진 동작"이 아니라 "현재의 잘못된 동작"을 단정한다.</b> Task 018의 목적은 초과 판매가
 * 실제로 발생한다는 것을 증명하고 수치를 남기는 것이고, 고치는 것은 Task 019(DB 락 전략 비교)의 몫이다.
 * 로드맵은 "테스트가 실패하며 재현"이라고 적었지만 그대로 두면 Task 019까지 CI가 영구 red가 되므로,
 * 현재 동작을 단정해 CI를 초록으로 유지하고 Task 019에서 단정만 뒤집는다 (뒤집을 지점은 주석으로 표시).
 *
 * @see com.tikkit.api.domain.reservation.service.ReservationService#create
 * @see TicketGrade#decreaseRemaining(int)
 */
@Import(ConcurrencyTestConfig.class)
class ReservationConcurrencyTest extends AbstractConcurrencyTest {

    // src/test에는 Lombok이 적용되지 않는다(build.gradle에 testAnnotationProcessor 선언 없음) — 로거를 직접 만든다.
    private static final Logger log = LoggerFactory.getLogger(ReservationConcurrencyTest.class);

    private static final int TOTAL_QUANTITY = 10;
    private static final int THREAD_COUNT = 100;

    /** 실제 PG의 승인 왕복 지연을 모사하는 값. 만료 트리거(1.5초)보다 충분히 길어야 경쟁이 성립한다. */
    private static final Duration PG_APPROVE_DELAY = Duration.ofSeconds(5);
    /** 결제 스레드가 예약을 조회한 뒤에 홀드가 끝나도록 잡은 여유. */
    private static final Duration HOLD_REMAINING = Duration.ofMillis(1_500);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private DelayedPaymentGateway delayedPaymentGateway;

    /** 컨텍스트가 캐시되어 테스트 간에 공유되므로 지연 설정을 반드시 되돌린다. */
    @AfterEach
    void resetPaymentGatewayDelay() {
        delayedPaymentGateway.resetApproveDelay();
    }

    @Test
    @Timeout(120)
    @DisplayName("[재현] 10석에 100명이 동시에 선점하면 재고보다 많이 팔린다 (lost update)")
    void 초과_판매_재현() throws Exception {
        // given: 재고 10석 등급 하나와, 서로 다른 회원 100명의 세션
        // 회원을 100명 따로 두는 이유: ReservationService.create()가 (회원, 등급, PENDING) 단위로
        // 중복 선점을 막기 때문에, 같은 회원으로 100번 쏘면 1건만 성공하고 99건이
        // DUPLICATE_PENDING_RESERVATION(409)으로 떨어져 초과 판매가 재현되지 않는다.
        TicketGrade grade = createOnSaleGrade(TOTAL_QUANTITY);
        Long gradeId = grade.getId();
        Long scheduleId = grade.getSchedule().getId();
        List<MockHttpSession> sessions = createMembersWithSession(THREAD_COUNT);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();

        // 모든 스레드를 한 지점에 모아두고 동시에 풀어, 같은 remaining_quantity 값을 읽는 스레드 수를 늘린다.
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // when: 100명이 동시에 1매씩 선점 요청
        try {
            for (MockHttpSession session : sessions) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        classify(reserve(session, scheduleId, gradeId), created, soldOut, unexpected);
                    } catch (Exception e) {
                        unexpected.add("예외: " + e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("모든 스레드가 출발선에 모였다").isTrue();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("모든 요청이 끝났다").isTrue();
        } finally {
            executor.shutdownNow();
        }

        // then: 커밋된 DB 상태를 직접 읽어 집계한다 (영속성 컨텍스트를 거치지 않는다)
        int soldQuantity = soldQuantityOf(gradeId);
        int remaining = remainingOf(gradeId);

        log.info("""
                [초과 판매 재현] 요청 {}건 / 201 성공 {}건 / 409 SOLD_OUT {}건 / 그 외 {}건
                            판매 수량 합 {} vs 총재고 {} (잔여 {})""",
                THREAD_COUNT, created.get(), soldOut.get(), unexpected.size(),
                soldQuantity, TOTAL_QUANTITY, remaining);

        assertThat(unexpected).as("예상 밖 응답·예외가 없어야 재현 결과를 신뢰할 수 있다").isEmpty();
        assertThat(created.get() + soldOut.get()).as("모든 요청이 201 또는 409로 끝났다").isEqualTo(THREAD_COUNT);

        // ↓↓↓ Task 019에서 조건부 UPDATE를 적용하면 이 두 줄을 isEqualTo(TOTAL_QUANTITY)로 뒤집는다 ↓↓↓
        assertThat(soldQuantity).as("팔린 수량이 총재고를 넘었다 = 초과 판매").isGreaterThan(TOTAL_QUANTITY);
        assertThat(soldQuantity + remaining).as("재고 불변식(판매 + 잔여 = 총재고)이 깨졌다")
                .isGreaterThan(TOTAL_QUANTITY);
        // ↑↑↑ 여기까지 ↑↑↑

        // CHECK 제약은 끝까지 살아있다 — 즉 DB 제약으로는 초과 판매를 막을 수 없다.
        // 각 트랜잭션이 쓰는 값은 "자기가 읽은 값 - 1"이라 항상 [0, total] 범위 안이고,
        // 재고가 모자라면 그 전에 SOLD_OUT으로 끊기기 때문이다 (V1__init_schema.sql:64).
        assertThat(remaining).as("ck_ticket_grades_remaining_range를 위반하지 않았다")
                .isBetween(0, TOTAL_QUANTITY);
    }

    @Test
    @Timeout(120)
    @DisplayName("[재현] PG 승인이 지연되는 동안 만료 배치가 끼어들면 결제는 확정되는데 재고가 복원된다")
    void 결제_만료_배치_경쟁_재현() throws Exception {
        // 워밍업: MockMvc·JPA 첫 호출은 느려서(쿼리 플랜·JIT) 결제 스레드가 만료 시각 전에 예약 조회까지
        // 도달하지 못할 수 있다. 다른 등급으로 선점+결제를 한 번 돌려 경로를 데워둔다.
        warmUpReserveAndPay();

        // given: 재고 10석에서 1매를 정상 경로로 선점한다 (remaining 10 -> 9)
        TicketGrade grade = createOnSaleGrade(TOTAL_QUANTITY);
        Long gradeId = grade.getId();
        Long scheduleId = grade.getSchedule().getId();
        MockHttpSession session = createMembersWithSession(1).get(0);

        Long reservationId = reserveAndGetId(session, scheduleId, gradeId);
        assertThat(remainingOf(gradeId)).as("선점으로 재고가 1 줄었다").isEqualTo(TOTAL_QUANTITY - 1);

        // 홀드가 PG 승인 "도중"에 끝나도록 만료 시각을 당긴다.
        // createPending()이 expires_at을 now+10분으로 못 박으므로, 10분이 흐른 상황은 이렇게만 만들 수 있다.
        // timestamptz 컬럼이라 OffsetDateTime(UTC)으로 바인딩해 시간대 해석 모호성을 없앤다.
        Instant expiresAt = Instant.now().plus(HOLD_REMAINING);
        jdbcTemplate.update("UPDATE reservations SET expires_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC), reservationId);
        delayedPaymentGateway.setApproveDelay(PG_APPROVE_DELAY);

        // when: 결제와 만료 배치를 같이 돌린다. 순서를 래치로 강제하지 않고, PG 지연과 만료 시각만으로
        // 운영에서와 같은 조건(승인 대기 중 만료 시각 경과)을 만든다.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int expiredGrades;
        int payStatus;
        try {
            Future<Integer> payment = executor.submit(() -> pay(session, reservationId));
            Future<Integer> expiry = executor.submit(() -> {
                // 스케줄러는 test 프로필에서 꺼져 있다(SchedulingConfig @Profile("!test"))
                // — 배치 본체를 직접 호출한다. 만료 시각이 실제로 지난 뒤에 돌려야 WHERE 조건에 걸린다.
                sleepUntil(expiresAt.plusMillis(100));
                return reservationRepository.expirePendingReservations(Instant.now());
            });

            expiredGrades = expiry.get(30, TimeUnit.SECONDS);
            payStatus = payment.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        String status = statusOf(reservationId);
        int remaining = remainingOf(gradeId);
        int soldQuantity = soldQuantityOf(gradeId);

        log.info("""
                [결제-만료 경쟁 재현] 만료 배치가 복원한 등급 {}건 / 결제 응답 {}
                            예약 상태 {} / 결제 상태 {} / 잔여 {} vs 총재고 {} (활성 판매 수량 {})""",
                expiredGrades, payStatus, status, paymentStatusOf(reservationId),
                remaining, TOTAL_QUANTITY, soldQuantity);

        assertThat(expiredGrades).as("만료 배치가 이 등급의 재고를 복원했다").isEqualTo(1);
        assertThat(payStatus).as("결제는 성공으로 끝났다").isEqualTo(200);

        // ↓↓↓ Task 019에서 상태 전이를 조건부 UPDATE(WHERE status='PENDING')로 바꾸면 뒤집을 단정 ↓↓↓
        // 결제 트랜잭션의 confirm()이 WHERE id만 걸고 UPDATE해서 배치가 쓴 EXPIRED를 덮어썼다.
        assertThat(status).as("만료 처리를 결제가 덮어썼다").isEqualTo("CONFIRMED");
        assertThat(paymentStatusOf(reservationId)).as("결제는 승인됐다").isEqualTo("PAID");
        assertThat(remaining).as("팔린 좌석인데 재고가 복원됐다").isEqualTo(TOTAL_QUANTITY);
        assertThat(soldQuantity + remaining).as("재고 불변식(판매 + 잔여 = 총재고)이 깨졌다")
                .isGreaterThan(TOTAL_QUANTITY);
        // ↑↑↑ 여기까지 ↑↑↑
    }

    /** 다른 등급에서 선점+결제를 한 번 수행해 MockMvc·JPA 경로를 데운다 (지연 없이). */
    private void warmUpReserveAndPay() throws Exception {
        TicketGrade warmUpGrade = createOnSaleGrade(TOTAL_QUANTITY);
        MockHttpSession warmUpSession = createMembersWithSession(1).get(0);
        Long warmUpReservationId = reserveAndGetId(
                warmUpSession, warmUpGrade.getSchedule().getId(), warmUpGrade.getId());
        assertThat(pay(warmUpSession, warmUpReservationId)).as("워밍업 결제가 성공했다").isEqualTo(200);
    }

    private void sleepUntil(Instant target) throws InterruptedException {
        long waitMillis = Duration.between(Instant.now(), target).toMillis();
        if (waitMillis > 0) {
            Thread.sleep(waitMillis);
        }
    }

    private Long reserveAndGetId(MockHttpSession session, Long scheduleId, Long gradeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReservationCreateRequest(scheduleId, gradeId, 1))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("선점이 성공했다").isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    private int pay(MockHttpSession session, Long reservationId) throws Exception {
        return mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
                .andReturn().getResponse().getStatus();
    }

    private String statusOf(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
    }

    private String paymentStatusOf(Long reservationId) {
        return jdbcTemplate.query(
                "SELECT status FROM payments WHERE reservation_id = ?",
                rs -> rs.next() ? rs.getString(1) : "결제 없음",
                reservationId);
    }

    /** 선점 API를 호출하고 (HTTP 상태, 에러 코드)를 돌려준다. */
    private Response reserve(MockHttpSession session, Long scheduleId, Long gradeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReservationCreateRequest(scheduleId, gradeId, 1))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Response(result.getResponse().getStatus(), objectMapper.readTree(body).path("code").asText());
    }

    private void classify(Response response, AtomicInteger created, AtomicInteger soldOut, Queue<String> unexpected) {
        if (response.status() == 201) {
            created.incrementAndGet();
        } else if (response.status() == 409 && "SOLD_OUT".equals(response.code())) {
            soldOut.incrementAndGet();
        } else {
            unexpected.add(response.toString());
        }
    }

    /** 재고를 점유하고 있는(활성) 예약의 수량 합. CANCELLED·EXPIRED는 재고를 반납했으므로 제외한다. */
    private int soldQuantityOf(Long gradeId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM reservations
                WHERE ticket_grade_id = ? AND status IN ('PENDING', 'CONFIRMED')
                """, Integer.class, gradeId);
    }

    private int remainingOf(Long gradeId) {
        return jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM ticket_grades WHERE id = ?", Integer.class, gradeId);
    }

    private record Response(int status, String code) {
    }
}
