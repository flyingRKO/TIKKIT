package com.tikkit.api.integration.reservation;

import com.tikkit.api.domain.payment.entity.PaymentMethod;
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
import tools.jackson.databind.json.JsonMapper;

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
 * 예매 선점의 동시성 제어를 검증하는 테스트.
 * <p>
 * Task 018에서 <b>초과 판매와 결제-만료 경쟁을 재현</b>하려고 만들었고(그때는 잘못된 동작을 단정했다),
 * Task 019에서 조건부 UPDATE를 적용하면서 단정을 뒤집어 <b>이제는 개선이 유지되는지를 지키는
 * 회귀 테스트</b>가 되었다. 비교 과정과 수치는 {@code docs/improvements/002-db-lock-comparison.md}에 있다.
 * <p>
 * Task 020에서 세 번째 시나리오(중복 선점 가드)가 추가됐다. {@code existsBy...} 후 INSERT는
 * 한 문장으로 묶을 수 없어 조건부 UPDATE로 막히지 않는다. Redis 분산 락과 부분 유니크 인덱스를
 * 비교한 뒤 <b>인덱스</b>를 택했고({@code V3_2}), 비교 수치는
 * {@code docs/improvements/003-redis-distributed-lock.md}에 있다.
 * <p>
 * <b>Task 022에서 경쟁 대상이 바뀌었다.</b> 재고가 {@code ticket_grades} 한 행에서
 * {@code schedule_seats} N행으로 쪼개지면서 "같은 숫자를 깎으려는 경쟁"이 "같은 좌석을 잡으려는
 * 경쟁"이 됐다. 재현 조건을 만드는 방법도 바뀐다 — 스레드 수가 아니라 <b>어느 좌석을 고르는지</b>가
 * 경쟁을 만든다. 여러 행을 한 번에 잠그면서 생기는 데드락과 부분 선점이 새 검증 대상이다.
 *
 * @see com.tikkit.api.domain.reservation.service.ReservationService#create
 * @see com.tikkit.api.domain.performance.repository.ScheduleSeatRepository#holdSeats
 * @see com.tikkit.api.domain.reservation.repository.ReservationRepository#confirmIfPending
 */
@Import(ConcurrencyTestConfig.class)
class ReservationConcurrencyTest extends AbstractConcurrencyTest {

    // src/test에는 Lombok이 적용되지 않는다(build.gradle에 testAnnotationProcessor 선언 없음) — 로거를 직접 만든다.
    private static final Logger log = LoggerFactory.getLogger(ReservationConcurrencyTest.class);

    private static final int SEAT_COUNT = 10;
    private static final int THREAD_COUNT = 100;

    /**
     * 중복 선점 가드 재현용 스레드 수. 같은 회원이므로 성공은 어차피 1건이어야 하고,
     * 100개까지 늘릴 필요가 없다 (Task 020의 Redis 락 비교에서도 같은 값을 쓴다).
     */
    private static final int DUPLICATE_THREAD_COUNT = 20;

    /** 겹치는 좌석 집합 시나리오에서 집합 하나당 띄우는 스레드 수. */
    private static final int OVERLAP_THREADS_PER_SET = 7;

    /** 실제 PG의 승인 왕복 지연을 모사하는 값. 만료 트리거(1.5초)보다 충분히 길어야 경쟁이 성립한다. */
    private static final Duration PG_APPROVE_DELAY = Duration.ofSeconds(5);
    /** 결제 스레드가 예약을 조회한 뒤에 홀드가 끝나도록 잡은 여유. */
    private static final Duration HOLD_REMAINING = Duration.ofMillis(1_500);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private DelayedPaymentGateway delayedPaymentGateway;

    /** 컨텍스트가 캐시되어 테스트 간에 공유되므로 지연 설정을 반드시 되돌린다. */
    @AfterEach
    void resetPaymentGatewayDelay() {
        delayedPaymentGateway.resetApproveDelay();
        delayedPaymentGateway.resetRefunds();
    }

    @Test
    @Timeout(120)
    @DisplayName("10석에 100명이 좌석당 10명씩 몰리면 좌석마다 한 명씩 정확히 10명만 성공한다")
    void 초과_판매_차단() throws Exception {
        // given: 좌석 10석과 서로 다른 회원 100명의 세션
        // 회원을 100명 따로 두는 이유: ReservationService.create()가 (회원, 등급, PENDING) 단위로
        // 중복 선점을 막기 때문에, 같은 회원으로 100번 쏘면 1건만 성공하고 99건이
        // DUPLICATE_PENDING_RESERVATION(409)으로 떨어져 좌석 경쟁이 재현되지 않는다.
        GradeWithSeats fixture = createOnSaleGradeWithSeats(SEAT_COUNT);
        List<MockHttpSession> sessions = createMembersWithSession(THREAD_COUNT);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();

        // 모든 스레드를 한 지점에 모아두고 동시에 풀어, 같은 좌석 행을 노리는 스레드 수를 늘린다.
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // when: i번째 스레드가 (i % 10)번 좌석 하나를 노린다 — 좌석마다 정확히 10명이 경쟁한다.
        // 무작위로 고르게 하면 "선택된 서로 다른 좌석 수"가 매번 달라져 성공 건수를 단정할 수 없다.
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                MockHttpSession session = sessions.get(i);
                List<Long> seatIds = List.of(fixture.scheduleSeatIds().get(i % SEAT_COUNT));
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        classify(reserve(session, fixture, seatIds), "SOLD_OUT", created, soldOut, unexpected);
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
        int available = seatCountOf(fixture, "AVAILABLE");
        int held = seatCountOf(fixture, "HELD");

        log.info("""
                        [초과 판매 차단] 요청 {}건 / 201 성공 {}건 / 409 SOLD_OUT {}건 / 그 외 {}건
                                    좌석 HELD {} / AVAILABLE {} / 총 {}석""",
                THREAD_COUNT, created.get(), soldOut.get(), unexpected.size(),
                held, available, SEAT_COUNT);

        assertThat(unexpected).as("예상 밖 응답·예외가 없어야 결과를 신뢰할 수 있다").isEmpty();
        assertThat(created.get() + soldOut.get()).as("모든 요청이 201 또는 409로 끝났다").isEqualTo(THREAD_COUNT);

        // 좌석 수만큼만 팔리고 나머지는 전부 거절된다.
        // "HELD 좌석 수"만 보면 "10건 성공 + 90건 거절"과 "5건 성공 + 95건 거절"을 구분할 수 없으므로,
        // 성공·거절 건수까지 단정해서 "좌석이 남았는데 거절"하는 회귀도 잡는다.
        assertThat(created.get()).as("좌석마다 한 명씩만 선점에 성공했다").isEqualTo(SEAT_COUNT);
        assertThat(soldOut.get()).as("나머지는 모두 SOLD_OUT으로 거절됐다")
                .isEqualTo(THREAD_COUNT - SEAT_COUNT);

        assertThat(held).as("선점된 좌석이 성공 건수와 같다 = 초과 판매 없음").isEqualTo(SEAT_COUNT);
        assertThat(available).as("좌석이 정확히 소진됐다").isZero();
        assertThat(available + held + seatCountOf(fixture, "SOLD"))
                .as("재고 불변식(AVAILABLE + HELD + SOLD = 총 좌석)이 지켜졌다").isEqualTo(SEAT_COUNT);
        assertThat(orphanOccupiedSeatCount(fixture)).as("점유 좌석에 이력이 없는 행이 없다").isZero();
    }

    @Test
    @Timeout(120)
    @DisplayName("한 좌석을 100명이 동시에 고르면 한 명만 성공한다")
    void 좌석당_한_명만_선점_성공() throws Exception {
        // given: 좌석 1석에 회원 100명을 모은다 — 좌석 단위 배타성의 가장 단순한 형태다
        GradeWithSeats fixture = createOnSaleGradeWithSeats(1);
        List<Long> contested = List.of(fixture.scheduleSeatIds().get(0));
        List<MockHttpSession> sessions = createMembersWithSession(THREAD_COUNT);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // when
        try {
            for (MockHttpSession session : sessions) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        classify(reserve(session, fixture, contested), "SOLD_OUT", created, soldOut, unexpected);
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

        // then
        log.info("[좌석당 한 명] 요청 {}건 / 201 성공 {}건 / 409 SOLD_OUT {}건 / 그 외 {}건",
                THREAD_COUNT, created.get(), soldOut.get(), unexpected.size());

        assertThat(unexpected).as("예상 밖 응답·예외가 없어야 결과를 신뢰할 수 있다").isEmpty();
        assertThat(created.get()).as("한 명만 좌석을 가져갔다").isEqualTo(1);
        assertThat(soldOut.get()).as("나머지는 모두 SOLD_OUT으로 거절됐다").isEqualTo(THREAD_COUNT - 1);
        assertThat(seatCountOf(fixture, "HELD")).as("좌석 1석이 선점됐다").isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("겹치는 좌석 집합을 동시에 선점해도 데드락이 없고 일부만 잡히는 예약이 생기지 않는다")
    void 겹치는_좌석_집합_동시_선점() throws Exception {
        // given: 6석에 서로 한 자리씩 겹치는 집합 3개를 만든다
        //   A{0,1,2}  B{2,3,4}  C{1,4,5}  — 세 쌍이 모두 겹치므로 성공은 한 건뿐이어야 한다
        // 여러 행을 잠그는 순서가 요청마다 다르면 서로를 기다리는 데드락이 생길 수 있다.
        // 서비스가 좌석 ID를 정렬해서 넘기므로 모든 요청이 같은 순서로 잡고, 그 순환이 성립하지 않는다.
        GradeWithSeats fixture = createOnSaleGradeWithSeats(6);
        List<Long> seats = fixture.scheduleSeatIds();
        List<List<Long>> seatSets = List.of(
                List.of(seats.get(0), seats.get(1), seats.get(2)),
                List.of(seats.get(2), seats.get(3), seats.get(4)),
                List.of(seats.get(1), seats.get(4), seats.get(5)));

        int threadCount = seatSets.size() * OVERLAP_THREADS_PER_SET;
        List<MockHttpSession> sessions = createMembersWithSession(threadCount);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // when: 집합을 번갈아 배정해 서로 다른 집합이 같은 순간에 부딪히게 한다
        try {
            for (int i = 0; i < threadCount; i++) {
                MockHttpSession session = sessions.get(i);
                List<Long> seatIds = seatSets.get(i % seatSets.size());
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        classify(reserve(session, fixture, seatIds), "SOLD_OUT", created, soldOut, unexpected);
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

        // then
        int occupied = seatCountOf(fixture, "HELD") + seatCountOf(fixture, "SOLD");

        log.info("""
                        [겹치는 좌석 집합] 요청 {}건 / 201 성공 {}건 / 409 SOLD_OUT {}건 / 그 외 {}건
                                    점유 좌석 {}석 / 총 6석""",
                threadCount, created.get(), soldOut.get(), unexpected.size(), occupied);

        // 데드락(40P01)이 났다면 500이 섞여 여기서 드러난다. 조건부 UPDATE는 재시도를 하지 않으므로
        // 데드락이 그대로 응답까지 올라온다 — 조용히 넘어가지 않는다.
        assertThat(unexpected).as("데드락이나 예상 밖 응답이 없다").isEmpty();
        assertThat(created.get() + soldOut.get()).as("모든 요청이 201 또는 409로 끝났다").isEqualTo(threadCount);

        // 세 집합이 서로 한 자리씩 겹치므로 어느 두 집합도 같이 성공할 수 없다.
        assertThat(created.get()).as("겹치는 집합 중 한 건만 성공했다").isEqualTo(1);
        assertThat(occupied).as("성공한 예약의 좌석 3석만 점유됐다 = 부분 선점이 없다").isEqualTo(3);
        assertThat(orphanOccupiedSeatCount(fixture)).as("점유 좌석에 이력이 없는 행이 없다").isZero();
        assertThat(partialReservationCount()).as("매수보다 적은 좌석을 받은 예약이 없다").isZero();
    }

    @Test
    @Timeout(120)
    @DisplayName("같은 회원이 같은 등급에 동시에 선점하면 한 건만 성공하고 나머지는 거절된다")
    void 중복_선점_차단() throws Exception {
        // given: 좌석을 스레드 수만큼 두고 각자 다른 좌석을 고르게 한다 — 여기서 재는 건 좌석 경쟁이
        // 아니라 (회원, 등급) 중복 선점 가드이므로, 좌석이 겹쳐서 SOLD_OUT이 섞이면
        // 무엇 때문에 거절된 건지 알 수 없다.
        GradeWithSeats fixture = createOnSaleGradeWithSeats(DUPLICATE_THREAD_COUNT);
        List<MockHttpSession> sessions = createOneMemberWithSessions(DUPLICATE_THREAD_COUNT);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        Queue<String> unexpected = new ConcurrentLinkedQueue<>();

        // 출발선을 맞춰야 재현된다. existsBy...는 READ COMMITTED에서 커밋된 행만 보므로,
        // 먼저 들어온 요청이 커밋을 끝내기 전에 다른 요청들이 가드를 통과해야 중복이 생긴다.
        CountDownLatch ready = new CountDownLatch(DUPLICATE_THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(DUPLICATE_THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(DUPLICATE_THREAD_COUNT);

        // when: 같은 회원이 같은 등급의 서로 다른 좌석을 동시에 20번 선점 요청
        try {
            for (int i = 0; i < DUPLICATE_THREAD_COUNT; i++) {
                MockHttpSession session = sessions.get(i);
                List<Long> seatIds = List.of(fixture.scheduleSeatIds().get(i));
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        classify(reserve(session, fixture, seatIds),
                                "DUPLICATE_PENDING_RESERVATION", created, duplicate, unexpected);
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

        // then: 이 테스트에는 회원이 한 명뿐이라 등급 기준 집계가 곧 (회원, 등급) 기준 집계다.
        int pendingCount = pendingCountOf(fixture);
        int held = seatCountOf(fixture, "HELD");

        log.info("""
                        [중복 선점 차단] 요청 {}건 / 201 성공 {}건 / 409 중복 {}건 / 그 외 {}건
                                    같은 (회원, 등급)의 PENDING {}건 (1건이어야 한다) / 선점 좌석 {}석 / 총 {}석""",
                DUPLICATE_THREAD_COUNT, created.get(), duplicate.get(), unexpected.size(),
                pendingCount, held, DUPLICATE_THREAD_COUNT);

        assertThat(unexpected).as("예상 밖 응답·예외가 없어야 결과를 신뢰할 수 있다").isEmpty();
        assertThat(created.get() + duplicate.get()).as("모든 요청이 201 또는 409로 끝났다")
                .isEqualTo(DUPLICATE_THREAD_COUNT);

        // 성공 건수와 PENDING 건수는 같아야 한다 — 좌석이 겹치지 않으므로 201을 받은 요청은 모두 행을 남긴다.
        assertThat(pendingCount).as("201을 받은 요청 수와 남은 PENDING 수가 일치한다")
                .isEqualTo(created.get());

        // V3_2의 부분 유니크 인덱스가 최종 방어선이다 (Task 020).
        // existsBy... 가드는 check-then-insert라 동시 요청에 뚫리고(Task 020 전에는 20건 전원 통과),
        // Redis 분산 락으로도 막히지만 "모든 쓰기 경로가 같은 키로 락을 잡아야 한다"는 전역 규약을
        // 요구한다. 제약은 경로와 무관하게 성립한다 — 비교 수치는
        // docs/improvements/003-redis-distributed-lock.md에 있다.
        assertThat(pendingCount).as("같은 (회원, 등급)의 PENDING은 정확히 한 건이다").isEqualTo(1);
        assertThat(created.get()).as("한 요청만 선점에 성공했다").isEqualTo(1);
        assertThat(duplicate.get()).as("나머지는 모두 중복으로 거절됐다")
                .isEqualTo(DUPLICATE_THREAD_COUNT - 1);

        // 인덱스 위반으로 롤백된 요청은 좌석도 잡지 않아야 한다 — 선점이 같은 트랜잭션 안이라 함께 되돌아간다.
        assertThat(held).as("거절된 요청의 좌석은 그대로 남았다").isEqualTo(1);
    }

    @Test
    @Timeout(120)
    @DisplayName("PG 승인이 지연되는 동안 만료 배치가 끼어들면 결제가 거절되고 좌석이 반환된다")
    void 결제_만료_배치_경쟁_차단() throws Exception {
        // 워밍업: MockMvc·JPA 첫 호출은 느려서(쿼리 플랜·JIT) 결제 스레드가 만료 시각 전에 예약 조회까지
        // 도달하지 못할 수 있다. 다른 등급으로 선점+결제를 한 번 돌려 경로를 데워둔다.
        warmUpReserveAndPay();

        // given: 좌석 10석에서 1석을 정상 경로로 선점한다
        GradeWithSeats fixture = createOnSaleGradeWithSeats(SEAT_COUNT);
        MockHttpSession session = createMembersWithSession(1).get(0);
        List<Long> seatIds = List.of(fixture.scheduleSeatIds().get(0));

        Long reservationId = reserveAndGetId(session, fixture, seatIds);
        assertThat(seatCountOf(fixture, "AVAILABLE")).as("선점으로 좌석이 1석 줄었다")
                .isEqualTo(SEAT_COUNT - 1);

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
        int releasedSeats;
        int payStatus;
        try {
            Future<Integer> payment = executor.submit(() -> pay(session, reservationId));
            Future<Integer> expiry = executor.submit(() -> {
                // 스케줄러는 test 프로필에서 꺼져 있다(SchedulingConfig @Profile("!test"))
                // — 배치 본체를 직접 호출한다. 만료 시각이 실제로 지난 뒤에 돌려야 WHERE 조건에 걸린다.
                sleepUntil(expiresAt.plusMillis(100));
                return reservationRepository.expirePendingReservations(Instant.now());
            });

            releasedSeats = expiry.get(30, TimeUnit.SECONDS);
            payStatus = payment.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then
        String status = statusOf(reservationId);
        int available = seatCountOf(fixture, "AVAILABLE");

        log.info("""
                        [결제-만료 경쟁 차단] 만료 배치가 반환한 좌석 {}석 / 결제 응답 {}
                                    예약 상태 {} / 결제 상태 {} / 예매 가능 좌석 {}석 vs 총 {}석""",
                releasedSeats, payStatus, status, paymentStatusOf(reservationId), available, SEAT_COUNT);

        assertThat(releasedSeats).as("만료 배치가 좌석을 반환했다").isEqualTo(1);

        // 상태 전이가 조건부 UPDATE(WHERE status = 'PENDING')로 바뀌어서, 만료 배치가 먼저 EXPIRED로
        // 바꾼 뒤에 들어온 확정은 0행이 되어 실패한다. 그 전에 받은 PG 승인은 보상 환불로 되돌린다.
        assertThat(payStatus).as("결제가 만료로 거절됐다").isEqualTo(409);
        assertThat(status).as("만료 처리가 덮어쓰이지 않았다").isEqualTo("EXPIRED");
        assertThat(paymentStatusOf(reservationId)).as("예외로 롤백되어 결제 행이 남지 않았다").isEqualTo("결제 없음");
        assertThat(delayedPaymentGateway.refundedKeys()).as("승인됐던 결제가 보상 환불됐다").hasSize(1);
        assertThat(available).as("만료로 좌석이 정상 반환됐다").isEqualTo(SEAT_COUNT);
        assertThat(seatCountOf(fixture, "HELD")).as("HELD로 고착된 좌석이 없다").isZero();
    }

    /** 다른 등급에서 선점+결제를 한 번 수행해 MockMvc·JPA 경로를 데운다 (지연 없이). */
    private void warmUpReserveAndPay() throws Exception {
        GradeWithSeats warmUp = createOnSaleGradeWithSeats(1);
        MockHttpSession warmUpSession = createMembersWithSession(1).get(0);
        Long warmUpReservationId = reserveAndGetId(
                warmUpSession, warmUp, List.of(warmUp.scheduleSeatIds().get(0)));
        assertThat(pay(warmUpSession, warmUpReservationId)).as("워밍업 결제가 성공했다").isEqualTo(200);
    }

    private void sleepUntil(Instant target) throws InterruptedException {
        long waitMillis = Duration.between(Instant.now(), target).toMillis();
        if (waitMillis > 0) {
            Thread.sleep(waitMillis);
        }
    }

    private Long reserveAndGetId(MockHttpSession session, GradeWithSeats fixture, List<Long> seatIds)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(createRequest(fixture, seatIds))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("선점이 성공했다").isEqualTo(201);
        return jsonMapper.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    private int pay(MockHttpSession session, Long reservationId) throws Exception {
        return mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new PaymentRequest(PaymentMethod.CARD))))
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

    private ReservationCreateRequest createRequest(GradeWithSeats fixture, List<Long> seatIds) {
        return new ReservationCreateRequest(
                fixture.scheduleId(), fixture.gradeId(), seatIds.size(), seatIds);
    }

    /** 선점 API를 호출하고 (HTTP 상태, 에러 코드)를 돌려준다. */
    private Response reserve(MockHttpSession session, GradeWithSeats fixture, List<Long> seatIds)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(createRequest(fixture, seatIds))))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Response(result.getResponse().getStatus(), jsonMapper.readTree(body).path("code").asString());
    }

    /** 선점 응답을 201 성공 / 409(기대한 에러 코드) 거절 / 그 외로 분류한다. */
    private void classify(Response response, String expectedRejectCode,
                          AtomicInteger created, AtomicInteger rejected, Queue<String> unexpected) {
        if (response.status() == 201) {
            created.incrementAndGet();
        } else if (response.status() == 409 && expectedRejectCode.equals(response.code())) {
            rejected.incrementAndGet();
        } else {
            unexpected.add(response.toString());
        }
    }

    /** 등급의 좌석 중 주어진 상태인 것의 건수. 커밋된 DB를 직접 읽는다. */
    private int seatCountOf(GradeWithSeats fixture, String status) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM schedule_seats WHERE ticket_grade_id = ? AND status = ?
                """, Integer.class, fixture.gradeId(), status);
    }

    /**
     * 점유 중인데 {@code reservation_seats} 이력이 없는 좌석 수.
     * <p>
     * 선점 UPDATE와 이력 INSERT를 한 문장(데이터 변경 CTE)으로 묶은 게 의도대로 동작하는지 본다.
     * 둘이 쪼개져 있으면 한쪽만 성공한 좌석이 여기 잡힌다.
     */
    private int orphanOccupiedSeatCount(GradeWithSeats fixture) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM schedule_seats ss
                WHERE ss.ticket_grade_id = ?
                  AND ss.status IN ('HELD', 'SOLD')
                  AND NOT EXISTS (SELECT 1 FROM reservation_seats rs
                                  WHERE rs.schedule_seat_id = ss.id
                                    AND rs.reservation_id = ss.reservation_id)
                """, Integer.class, fixture.gradeId());
    }

    /** 매수보다 적은 좌석을 받은 활성 예약 수 — 부분 선점이 남았는지 직접 센다. */
    private int partialReservationCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM reservations r
                WHERE r.status IN ('PENDING', 'CONFIRMED')
                  AND (SELECT count(*) FROM reservation_seats rs WHERE rs.reservation_id = r.id) <> r.quantity
                """, Integer.class);
    }

    /** 해당 등급에 남아 있는 PENDING 예약 건수. 중복 선점이 몇 건 생겼는지를 직접 센다. */
    private int pendingCountOf(GradeWithSeats fixture) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM reservations
                WHERE ticket_grade_id = ? AND status = 'PENDING'
                """, Integer.class, fixture.gradeId());
    }

    private record Response(int status, String code) {
    }
}
