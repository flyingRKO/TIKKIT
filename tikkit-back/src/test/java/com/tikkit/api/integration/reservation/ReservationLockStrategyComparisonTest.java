package com.tikkit.api.integration.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.strategy.RetryMetrics;
import com.tikkit.api.domain.reservation.strategy.SeatHoldStrategy;
import com.tikkit.api.domain.reservation.strategy.SeatHoldStrategyHolder;
import com.tikkit.api.support.AbstractConcurrencyTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 재고 차감 락 전략 4가지를 같은 조건에서 비교 측정한다 (Task 019).
 * <p>
 * <b>비교가 끝나면 이 테스트는 삭제한다.</b> 측정 수치는 커밋 메시지와
 * {@code docs/improvements/002-db-lock-comparison.md}에 남고, 채택한 전략의 회귀는
 * {@link ReservationConcurrencyTest}가 계속 지킨다.
 * <p>
 * 조건은 Task 018의 재현 테스트와 같다 — 재고 10석, 서로 다른 회원 100명, 1매씩 동시 요청,
 * 출발선 래치로 동시 진입 확보, HikariCP 풀 30 ({@link AbstractConcurrencyTest}).
 */
class ReservationLockStrategyComparisonTest extends AbstractConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ReservationLockStrategyComparisonTest.class);

    private static final int TOTAL_QUANTITY = 10;
    private static final int THREAD_COUNT = 100;
    /** 쿼리 플랜·JIT를 데우는 라운드. 결과는 버린다 — 첫 전략이 초기화 비용을 다 떠안으면 비교가 무의미해진다. */
    private static final int WARM_UP_THREADS = 20;
    private static final int ROUNDS = 3;

    private static final String BASELINE = "noLockStrategy";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeatHoldStrategyHolder seatHoldStrategyHolder;
    @Autowired
    private RetryMetrics retryMetrics;

    /** 스프링이 빈 이름을 키로 넣어준다 — 파라미터의 문자열이 그대로 키가 된다. */
    @Autowired
    private Map<String, SeatHoldStrategy> strategies;

    @AfterEach
    void restoreDefaults() {
        seatHoldStrategyHolder.reset();
        retryMetrics.reset();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            BASELINE,
            "pessimisticLockStrategy",
            "optimisticLockStrategy",
            "conditionalUpdateStrategy"
    })
    @Timeout(300)
    void 전략별_정확성_소요시간_재시도횟수(String beanName) throws Exception {
        SeatHoldStrategy strategy = strategies.get(beanName);
        assertThat(strategy).as("전략 빈 %s을 찾을 수 없다".formatted(beanName)).isNotNull();
        seatHoldStrategyHolder.setStrategy(strategy);

        runRound(strategy.name(), WARM_UP_THREADS, 0);

        for (int round = 1; round <= ROUNDS; round++) {
            Result result = runRound(strategy.name(), THREAD_COUNT, round);

            assertThat(result.unexpected()).as("예상 밖 응답·예외가 섞이면 수치를 신뢰할 수 없다").isEmpty();

            if (BASELINE.equals(beanName)) {
                assertThat(result.soldQuantity()).as("기준선은 초과 판매가 나야 비교 대상이 된다")
                        .isGreaterThan(TOTAL_QUANTITY);
            } else {
                // 낙관적 락은 재시도를 소진하면 재고가 남아도 거절할 수 있어서 "정확히 10"을 보장하지 못한다.
                // 그 사실 자체가 측정 결과이므로 여기서는 "초과 판매가 없다"까지만 단정한다.
                assertThat(result.soldQuantity()).as("초과 판매가 없다")
                        .isLessThanOrEqualTo(TOTAL_QUANTITY);
                assertThat(result.soldQuantity() + result.remaining()).as("재고 불변식(판매 + 잔여 = 총재고)")
                        .isEqualTo(TOTAL_QUANTITY);
            }
        }
    }

    /** 한 라운드를 돌리고 수치를 반환한다. {@code round == 0}이면 워밍업이라 로그를 생략한다. */
    private Result runRound(String strategyName, int threadCount, int round) throws Exception {
        TicketGrade grade = createOnSaleGrade(TOTAL_QUANTITY);
        Long gradeId = grade.getId();
        Long scheduleId = grade.getSchedule().getId();
        List<MockHttpSession> sessions = createMembersWithSession(threadCount);
        retryMetrics.reset();

        AtomicInteger created = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();
        Queue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        long elapsedNanos;
        try {
            for (MockHttpSession session : sessions) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        long begin = System.nanoTime();
                        Response response = reserve(session, scheduleId, gradeId);
                        latenciesNanos.add(System.nanoTime() - begin);
                        classify(response, created, soldOut, unexpected);
                    } catch (Exception e) {
                        unexpected.add("예외: " + e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(60, TimeUnit.SECONDS)).as("모든 스레드가 출발선에 모였다").isTrue();

            long begin = System.nanoTime();
            start.countDown();
            assertThat(done.await(180, TimeUnit.SECONDS)).as("모든 요청이 끝났다").isTrue();
            elapsedNanos = System.nanoTime() - begin;
        } finally {
            executor.shutdownNow();
        }

        Result result = new Result(created.get(), soldOut.get(), List.copyOf(unexpected),
                soldQuantityOf(gradeId), remainingOf(gradeId),
                elapsedNanos / 1_000_000, averageMillis(latenciesNanos), maxMillis(latenciesNanos),
                retryMetrics.count());

        if (round > 0) {
            log.info("""
                    [락 전략 비교] {} / {}회차
                                201 {}건 / 409 SOLD_OUT {}건 / 그 외 {}건
                                판매 {} + 잔여 {} = {} (총재고 {})
                                총 소요 {}ms / 요청 평균 {}ms / 요청 최대 {}ms / 재시도 {}회""",
                    strategyName, round,
                    result.created(), result.soldOut(), result.unexpected().size(),
                    result.soldQuantity(), result.remaining(), result.soldQuantity() + result.remaining(),
                    TOTAL_QUANTITY,
                    result.elapsedMillis(), result.averageLatencyMillis(), result.maxLatencyMillis(),
                    result.retries());
        }
        return result;
    }

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

    private long averageMillis(Queue<Long> nanos) {
        return nanos.isEmpty() ? 0 : nanos.stream().mapToLong(Long::longValue).sum() / nanos.size() / 1_000_000;
    }

    private long maxMillis(Queue<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000;
    }

    private record Response(int status, String code) {
    }

    private record Result(int created, int soldOut, List<String> unexpected,
                          int soldQuantity, int remaining,
                          long elapsedMillis, long averageLatencyMillis, long maxLatencyMillis,
                          int retries) {
    }
}
