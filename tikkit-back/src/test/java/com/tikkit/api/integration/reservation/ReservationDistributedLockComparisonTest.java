package com.tikkit.api.integration.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.security.MemberDetails;
import com.tikkit.api.support.AbstractRedisConcurrencyTest;
import com.tikkit.lockexperiment.DistributedLockedHoldFacade;
import com.tikkit.lockexperiment.HoldMode;
import com.tikkit.lockexperiment.LockExperimentConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Redis 분산 락과 DB(조건부 UPDATE / 제약)를 같은 조건에서 비교 측정한다 (Task 020).
 * <p>
 * <b>이 클래스는 측정 하네스이고, 채택이 끝나면 삭제한다.</b> 수치는 커밋 메시지와
 * {@code docs/improvements/003-redis-distributed-lock.md}에 남는다. 회귀 보장은
 * {@link ReservationConcurrencyTest}가 계속 맡는다.
 *
 * <h2>두 축</h2>
 * <ul>
 *   <li><b>축 A — 재고 차감</b>: 조건부 UPDATE가 이미 원자적으로 처리하는 경쟁이다.
 *       분산 락을 얹으면 무엇이 좋아지고 무엇을 잃는지 본다.</li>
 *   <li><b>축 B — 중복 선점 가드</b>: {@code existsBy...} 후 INSERT라 한 문장으로 묶을 수 없는
 *       경쟁이다. 분산 락이 실제로 값을 발휘할 수 있는 자리다.</li>
 * </ul>
 *
 * <h2>읽는 법</h2>
 * 측정 경로에 실험 컨트롤러 위임이 한 단 끼므로
 * {@code docs/improvements/002-db-lock-comparison.md}의 절대 수치와 직접 비교하면 안 된다.
 * 기준선({@code PLAIN})도 같은 하네스를 통과하므로 <b>arm 간 상대 비교</b>로만 읽는다.
 * Testcontainers Redis는 같은 호스트의 루프백이라 측정된 Redis 오버헤드는 <b>하한</b>이다.
 */
@Import(LockExperimentConfig.class)
class ReservationDistributedLockComparisonTest extends AbstractRedisConcurrencyTest {

    private static final Logger log =
            LoggerFactory.getLogger(ReservationDistributedLockComparisonTest.class);

    private static final int STOCK_QUANTITY = 10;
    private static final int STOCK_THREADS = 100;
    private static final int DUPLICATE_THREADS = 20;

    /** 워밍업 라운드의 스레드 수. 결과는 버린다 (MockMvc·JPA·Redisson 첫 호출이 느리다). */
    private static final int WARM_UP_THREADS = 20;
    private static final int MEASURED_ROUNDS = 3;

    private static final String SOLD_OUT = "SOLD_OUT";
    private static final String DUPLICATE = "DUPLICATE_PENDING_RESERVATION";
    private static final String LOCK_FAILED = "LOCK_ACQUISITION_FAILED";

    /** arm 이름 -> 측정 라운드들. 모든 arm이 끝난 뒤 표로 찍는다. */
    private static final Map<String, List<Round>> RESULTS = new LinkedHashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DistributedLockedHoldFacade lockedFacade;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterAll
    static void printComparisonTables() {
        log.info("\n{}", renderTable("축 A — 재고 차감 (10석 / 100스레드)", "A:", "판매"));
        log.info("\n{}", renderTable("축 B — 중복 선점 가드 (같은 회원 / 20스레드)", "B:", "PENDING"));
    }

    // ------------------------------------------------------------------ 축 A

    @ParameterizedTest(name = "[{0}]")
    @EnumSource(value = HoldMode.class, names = {"PLAIN", "LOCK_GRADE", "LOCK_GRADE_IN_TX"})
    @Timeout(900)
    @DisplayName("축 A: 10석에 100명이 동시에 선점한다")
    void 축A_재고_차감(HoldMode mode) throws Exception {
        runRound(mode, WARM_UP_THREADS, WARM_UP_THREADS, false, SOLD_OUT);

        List<Round> rounds = new ArrayList<>();
        for (int i = 0; i < MEASURED_ROUNDS; i++) {
            rounds.add(runRound(mode, STOCK_THREADS, STOCK_QUANTITY, false, SOLD_OUT));
        }
        RESULTS.put("A:" + mode, rounds);

        for (Round round : rounds) {
            assertThat(round.unexpected()).as("[%s] 예상 밖 응답·예외가 없다", mode).isEmpty();
            assertThat(round.lockFailed()).as("[%s] 락 획득 실패가 없다 (waitTime이 충분하다)", mode).isZero();
            assertThat(round.created() + round.rejected())
                    .as("[%s] 모든 요청이 201 또는 409 SOLD_OUT으로 끝났다", mode).isEqualTo(STOCK_THREADS);

            // 세 arm 모두 정확히 10매다. LOCK_GRADE_IN_TX(커밋 전 해제)까지 정상으로 나오는 것이 요점이다 —
            // 아래에 깔린 조건부 UPDATE가 받쳐주므로 락을 잘못 걸어도 재고 수치로는 드러나지 않는다.
            assertThat(round.created()).as("[%s] 재고만큼만 성공했다", mode).isEqualTo(STOCK_QUANTITY);
            assertThat(round.activeQuantity()).as("[%s] 초과 판매가 없다", mode).isEqualTo(STOCK_QUANTITY);
            assertThat(round.activeQuantity() + round.remaining())
                    .as("[%s] 재고 불변식이 지켜졌다", mode).isEqualTo(STOCK_QUANTITY);
        }
    }

    // ------------------------------------------------------------------ 축 B

    @ParameterizedTest(name = "[{0}]")
    @EnumSource(value = HoldMode.class, names = {
            "PLAIN", "LOCK_MEMBER_GRADE", "LOCK_MEMBER_GRADE_IN_TX", "LOCK_MEMBER_GRADE_IN_TX_DELAYED"})
    @Timeout(900)
    @DisplayName("축 B: 같은 회원이 같은 등급에 동시에 선점한다")
    void 축B_중복_선점(HoldMode mode) throws Exception {
        runRound(mode, WARM_UP_THREADS, WARM_UP_THREADS, true, DUPLICATE);

        List<Round> rounds = new ArrayList<>();
        for (int i = 0; i < MEASURED_ROUNDS; i++) {
            rounds.add(runRound(mode, DUPLICATE_THREADS, DUPLICATE_THREADS, true, DUPLICATE));
        }
        RESULTS.put("B:" + mode, rounds);

        for (Round round : rounds) {
            assertThat(round.unexpected()).as("[%s] 예상 밖 응답·예외가 없다", mode).isEmpty();
            assertThat(round.lockFailed()).as("[%s] 락 획득 실패가 없다", mode).isZero();
            assertThat(round.created() + round.rejected())
                    .as("[%s] 모든 요청이 201 또는 409 중복으로 끝났다", mode).isEqualTo(DUPLICATE_THREADS);
            assertThat(round.pendingCount()).as("[%s] 201 건수와 PENDING 건수가 일치한다", mode)
                    .isEqualTo(round.created());

            // V3_2의 부분 유니크 인덱스가 들어온 뒤로는 네 arm 모두 1건이다.
            // 분산 락이 있든 없든, 심지어 커밋 전에 풀어서 잘못 걸어도 결과가 같다 —
            // 축 A에서 조건부 UPDATE가 보여준 것과 똑같은 구도다.
            // 인덱스 적용 전 PLAIN은 20건이었다 (커밋 be81103의 측정 표 참조).
            assertThat(round.pendingCount())
                    .as("[%s] 부분 유니크 인덱스가 중복을 막는다", mode).isEqualTo(1);
            assertThat(round.rejected()).as("[%s] 나머지는 모두 거절됐다", mode)
                    .isEqualTo(DUPLICATE_THREADS - 1);
            assertThat(round.remaining()).as("[%s] 거절된 요청의 재고는 롤백됐다", mode)
                    .isEqualTo(DUPLICATE_THREADS - 1);
        }
    }

    // ------------------------------------------------------------------ 순서 검증

    @Test
    @DisplayName("@DistributedLock을 트랜잭션 안에서 호출하면 가드가 막는다")
    void 트랜잭션_활성_가드() {
        TicketGrade grade = createOnSaleGrade(2);
        MockHttpSession session = createOneMemberWithSessions(1).get(0);
        Long memberId = memberIdOf(session);
        ReservationCreateRequest request =
                new ReservationCreateRequest(grade.getSchedule().getId(), grade.getId(), 1);

        // 트랜잭션 밖 호출은 정상 동작한다 = @Order(HIGHEST_PRECEDENCE)로 락이 트랜잭션보다 바깥에 있다.
        assertThat(lockedFacade.holdWithMemberGradeLock(memberId, request))
                .as("트랜잭션 밖 호출은 그대로 성공한다").isNotNull();

        // 트랜잭션 안에서 부르면 커밋 전에 락이 풀리므로 Aspect가 거부한다.
        assertThatThrownBy(() -> transactionTemplate.execute(
                status -> lockedFacade.holdWithMemberGradeLock(memberId, request)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("트랜잭션 안에서 걸렸다");
    }

    // ------------------------------------------------------------------ 측정

    /**
     * 한 라운드를 측정한다. 라운드마다 새 등급·새 회원을 만들고 집계는 등급 기준으로 하므로,
     * 라운드 사이에 DB를 비우지 않아도 수치가 섞이지 않는다.
     */
    private Round runRound(HoldMode mode, int threadCount, int stock, boolean sameMember,
                           String expectedRejectCode) throws Exception {
        TicketGrade grade = createOnSaleGrade(stock);
        Long gradeId = grade.getId();
        Long scheduleId = grade.getSchedule().getId();
        List<MockHttpSession> sessions = sameMember
                ? createOneMemberWithSessions(threadCount)
                : createMembersWithSession(threadCount);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger lockFailed = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();
        Queue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        long totalNanos;
        try {
            for (MockHttpSession session : sessions) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        long begin = System.nanoTime();
                        Response response = hold(session, mode, scheduleId, gradeId);
                        latenciesNanos.add(System.nanoTime() - begin);
                        classify(response, expectedRejectCode, created, rejected, lockFailed, unexpected);
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
            assertThat(done.await(300, TimeUnit.SECONDS)).as("모든 요청이 끝났다").isTrue();
            totalNanos = System.nanoTime() - begin;
        } finally {
            executor.shutdownNow();
        }

        List<Long> latencies = new ArrayList<>(latenciesNanos);
        long avgNanos = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long maxNanos = latencies.stream().mapToLong(Long::longValue).max().orElse(0);

        return new Round(
                created.get(), rejected.get(), lockFailed.get(), new ArrayList<>(unexpected),
                toMillis(totalNanos), toMillis(avgNanos), toMillis(maxNanos),
                activeQuantityOf(gradeId), pendingCountOf(gradeId), remainingOf(gradeId));
    }

    private Response hold(MockHttpSession session, HoldMode mode, Long scheduleId, Long gradeId)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/experiment/reservations/{mode}", mode)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReservationCreateRequest(scheduleId, gradeId, 1))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Response(result.getResponse().getStatus(),
                objectMapper.readTree(body).path("code").asText());
    }

    private void classify(Response response, String expectedRejectCode, AtomicInteger created,
                          AtomicInteger rejected, AtomicInteger lockFailed, Queue<String> unexpected) {
        if (response.status() == 201) {
            created.incrementAndGet();
        } else if (response.status() == 409 && expectedRejectCode.equals(response.code())) {
            rejected.incrementAndGet();
        } else if (response.status() == 409 && LOCK_FAILED.equals(response.code())) {
            lockFailed.incrementAndGet();
        } else {
            unexpected.add(response.toString());
        }
    }

    // ------------------------------------------------------------------ 표 렌더링

    private static String renderTable(String title, String keyPrefix, String accuracyLabel) {
        boolean stockAxis = keyPrefix.startsWith("A");
        StringBuilder table = new StringBuilder("[").append(title).append("]\n");
        table.append(row("arm", "201", "409", "락실패", accuracyLabel, "총 소요", "요청 평균", "요청 최대"));
        RESULTS.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(keyPrefix))
                .forEach(entry -> {
                    List<Round> rounds = entry.getValue();
                    table.append(row(
                            entry.getKey().substring(keyPrefix.length()),
                            range(rounds, Round::created),
                            range(rounds, Round::rejected),
                            range(rounds, Round::lockFailed),
                            range(rounds, r -> stockAxis ? r.activeQuantity() : r.pendingCount()),
                            rangeMillis(rounds, Round::totalMillis),
                            rangeMillis(rounds, Round::avgMillis),
                            rangeMillis(rounds, Round::maxMillis)));
                });
        return table.toString();
    }

    private static String row(String arm, String created, String rejected, String lockFailed,
                              String accuracy, String total, String avg, String max) {
        return "%-32s | %7s | %7s | %7s | %9s | %12s | %12s | %12s%n"
                .formatted(arm, created, rejected, lockFailed, accuracy, total, avg, max);
    }

    /** 라운드별 값을 10 또는 10~12 형태로 모은다 (improvements 문서가 범위로 적는 관행을 따른다). */
    private static String range(List<Round> rounds, ToIntFunction<Round> getter) {
        int min = rounds.stream().mapToInt(getter).min().orElse(0);
        int max = rounds.stream().mapToInt(getter).max().orElse(0);
        return min == max ? String.valueOf(min) : "%d~%d".formatted(min, max);
    }

    private static String rangeMillis(List<Round> rounds, ToLongFunction<Round> getter) {
        long min = rounds.stream().mapToLong(getter).min().orElse(0);
        long max = rounds.stream().mapToLong(getter).max().orElse(0);
        return min == max ? "%dms".formatted(min) : "%d~%dms".formatted(min, max);
    }

    // ------------------------------------------------------------------ DB 집계

    /** 재고를 점유하고 있는 예약의 수량 합. */
    private int activeQuantityOf(Long gradeId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM reservations
                WHERE ticket_grade_id = ? AND status IN ('PENDING', 'CONFIRMED')
                """, Integer.class, gradeId);
    }

    private int pendingCountOf(Long gradeId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM reservations WHERE ticket_grade_id = ? AND status = 'PENDING'
                """, Integer.class, gradeId);
    }

    private int remainingOf(Long gradeId) {
        return jdbcTemplate.queryForObject(
                "SELECT remaining_quantity FROM ticket_grades WHERE id = ?", Integer.class, gradeId);
    }

    private Long memberIdOf(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return ((MemberDetails) context.getAuthentication().getPrincipal()).getMemberId();
    }

    private static long toMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private record Response(int status, String code) {
    }

    private record Round(int created, int rejected, int lockFailed, List<String> unexpected,
                         long totalMillis, long avgMillis, long maxMillis,
                         int activeQuantity, int pendingCount, int remaining) {
    }
}
