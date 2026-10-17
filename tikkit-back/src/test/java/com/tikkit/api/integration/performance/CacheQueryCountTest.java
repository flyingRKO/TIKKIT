package com.tikkit.api.integration.performance;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.performance.entity.SeatStatus;
import com.tikkit.api.domain.performance.service.PerformanceService;
import com.tikkit.api.domain.performance.service.ScheduleService;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.service.ReservationService;
import com.tikkit.api.support.AbstractRedisCacheContainerTest;
import com.tikkit.api.support.QueryCounter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 캐시가 켜진 상태에서 미스 경로와 히트 경로의 문장 수를 따로 고정한다 (Task 029).
 *
 * <p>캐시가 꺼진 기준값은 {@code PerformanceQueryCountTest}에 있다. 둘을 나눈 이유는
 * {@code tikkit.cache.type}이 달라 스프링 컨텍스트가 어차피 분리되기 때문이다.
 *
 * <p><b>이 테스트가 잡으려는 사고</b><br>
 * {@code @Cacheable}은 조용히 실패한다. 캐시 이름을 오타 내거나 self-invocation으로 프록시를
 * 건너뛰면 예외도 경고도 없고 그냥 매번 DB를 친다. 적중률 지표를 봐야 알 수 있는데 그건
 * 부하 측정 중에나 보인다. "두 번째 호출의 쿼리 수가 줄었다"를 테스트로 고정해 두면 구현이
 * 깨지는 순간 CI에서 잡힌다.
 */
class CacheQueryCountTest extends AbstractRedisCacheContainerTest {

    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private ScheduleService scheduleService;
    // cacheManager는 AbstractRedisCacheContainerTest가 protected로 들고 있다.
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private QueryCounter queryCounter;
    private Long performanceId;
    private Long scheduleId;
    private Long gradeId;
    private Long memberId;

    @BeforeEach
    void setUpFixture() {
        queryCounter = new QueryCounter(entityManagerFactory);
        GradeWithSeats fixture = createOnSaleGradeWithSeats(10);
        scheduleId = fixture.scheduleId();
        gradeId = fixture.gradeId();
        performanceId = jdbcTemplate.queryForObject(
                "SELECT performance_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        memberId = memberRepository.save(Member.builder()
                .email("cache-query-count@tikkit.test")
                .password("암호화되지않은값이지만로그인을타지않는다")
                .name("캐시카운터")
                .phone("010-0000-0000")
                .role(MemberRole.USER)
                .build()).getId();
        // Redis 비우기는 AbstractRedisCacheContainerTest가 FLUSHDB로 처리한다.
        // truncate는 DB만 비우는데 id가 RESTART IDENTITY로 1부터 다시 시작하므로,
        // Redis를 같이 비우지 않으면 **다른 픽스처의 캐시를 히트한다**.
    }

    @Test
    @DisplayName("설정이 Redis 캐시 매니저를 올리고 선언한 캐시를 기동 시점에 전부 만든다")
    void 캐시_매니저_구성() {
        // RedisCacheManager가 올라와야 적중률 지표(cache_gets)가 Redis 기준으로 집계된다.
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        // 미리 만들지 않으면 첫 요청 전까지 지표 시리즈가 없어서 "적중률 0"과 "계측 없음"이
        // 구분되지 않는다. 응답 바이트 캐시는 기본 비활성이라 목록에 없어야 한다.
        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrderElementsOf(CacheNames.TTLS.keySet())
                .doesNotContain(CacheNames.SEATMAP_RESPONSE);
    }

    @Test
    @DisplayName("적중률 지표가 Prometheus로 나간다 — 006이 요구한 교차 확인 수단이다")
    void 적중률_지표_노출() {
        // 006 10절: "캐시 적용 후에는 적중률을 따로 재야 p95 개선이 캐시 덕인지 확인할 수 있다."
        // Micrometer가 캐시를 바인딩하지 않으면 Grafana 패널이 영구히 비어서 그 확인을 못 한다.
        performanceService.getDetail(performanceId);
        awaitCached(CacheNames.PERFORMANCE_DETAIL, performanceId);
        performanceService.getDetail(performanceId);

        FunctionCounter gets = meterRegistry.find("cache.gets")
                .tag("cache", CacheNames.PERFORMANCE_DETAIL)
                .tag("result", "hit")
                .functionCounter();

        assertThat(gets)
                .as("cache.gets{result=hit}이 없으면 RedisCacheManager의 enableStatistics()가 빠졌거나 "
                        + "Micrometer 바인딩이 안 된 것이다")
                .isNotNull();
        assertThat(gets.count()).isPositive();
    }

    @Test
    @DisplayName("공연 목록은 첫 호출에 쿼리를 보내고 두 번째 호출은 0문장이다")
    void 공연_목록_히트는_0문장() {
        long miss = queryCounter.count(() -> performanceService.list(null, null, "ON_SALE", 0, listPageSize));
        long hit = queryCounter.count(() -> performanceService.list(null, null, "ON_SALE", 0, listPageSize));

        assertThat(miss).isEqualTo(1);
        assertThat(hit).isZero();
    }

    @Test
    @DisplayName("keyword 검색은 캐시하지 않는다 — 사용자 입력이라 키 공간이 무한하다")
    void keyword_검색은_캐시하지_않는다() {
        long first = queryCounter.count(() -> performanceService.list(null, "부하", "ON_SALE", 0, 20));
        long second = queryCounter.count(() -> performanceService.list(null, "부하", "ON_SALE", 0, 20));

        // 두 번 다 DB를 친다. 캐시를 탔다면 두 번째가 0이 된다.
        assertThat(first).isEqualTo(second).isEqualTo(1);
        assertThat(cacheManager.getCache(CacheNames.PERFORMANCE_LIST).get(listCacheKey())).isNull();
    }

    @Test
    @DisplayName("공연 상세는 첫 호출에 2문장, 두 번째 호출은 0문장이다")
    void 공연_상세_히트는_0문장() {
        long miss = queryCounter.count(() -> performanceService.getDetail(performanceId));
        long hit = queryCounter.count(() -> performanceService.getDetail(performanceId));

        assertThat(miss).isEqualTo(2);
        assertThat(hit).isZero();
    }

    @Test
    @DisplayName("등급 조회는 가격만 캐시하므로 두 번째 호출도 잔여 수량 COUNT 1문장은 남는다")
    void 등급_조회_히트는_1문장() {
        long miss = queryCounter.count(() -> scheduleService.getTicketGrades(scheduleId));
        long hit = queryCounter.count(() -> scheduleService.getTicketGrades(scheduleId));

        // 미스: 등급 행 + COUNT. 히트: COUNT만.
        // 0이 아닌 게 핵심이다 — 잔여 수량까지 캐시하면 매진된 회차가 TTL 동안 "잔여 있음"으로 보인다.
        assertThat(miss).isEqualTo(2);
        assertThat(hit).isEqualTo(1);
    }

    @Test
    @DisplayName("좌석맵은 배치만 캐시하므로 두 번째 호출도 상태 조회 1문장은 남는다")
    void 좌석맵_히트는_1문장() {
        long miss = queryCounter.count(() -> scheduleService.getSeats(scheduleId));
        long hit = queryCounter.count(() -> scheduleService.getSeats(scheduleId));

        assertThat(miss).isEqualTo(2);
        assertThat(hit).isEqualTo(1);
    }

    @Test
    @DisplayName("캐시된 배치도 좌석 상태는 즉시 반영한다 — 선점 직후 재조회에 HELD가 보인다")
    void 상태는_캐시되지_않는다() {
        // given: 좌석맵을 한 번 조회해 배치를 캐시에 올린다
        List<ScheduleSeatResponse> before = scheduleService.getSeats(scheduleId);
        assertThat(before).allMatch(seat -> seat.status() == SeatStatus.AVAILABLE);
        Long targetSeatId = before.getFirst().id();

        // when: 실제 선점 경로로 좌석 하나를 잡는다.
        // DB를 직접 UPDATE하지 않는 이유는 ck_schedule_seats_status_holder다 —
        // HELD/SOLD는 reservation_id가 반드시 있어야 해서 status만 바꿀 수 없다(V4의 의도적 설계).
        // 서비스를 쓰면 제약을 지키면서 "사용자가 좌석을 잡았다"는 실제 상황이 된다.
        reservationService.create(memberId, new ReservationCreateRequest(
                scheduleId, gradeId, 1, List.of(targetSeatId)));

        // then: 배치는 캐시에서 오지만 상태는 새로 읽으므로 바로 보인다
        List<ScheduleSeatResponse> after = scheduleService.getSeats(scheduleId);
        assertThat(after)
                .filteredOn(seat -> seat.id().equals(targetSeatId))
                .singleElement()
                .extracting(ScheduleSeatResponse::status).isEqualTo(SeatStatus.HELD);
        // 배치는 여전히 캐시에 있다 (상태가 바뀌어도 캐시를 버리지 않는다)
        awaitCached(CacheNames.SEAT_LAYOUT, scheduleId);
    }

    @Test
    @DisplayName("없는 회차 조회는 캐시를 채우지 않는다 — 무작위 id로 캐시를 오염시킬 수 없다")
    void 없는_회차는_캐시하지_않는다() {
        // 키가 경로 변수라 사용자가 키 공간을 고를 수 있다. 빈 결과를 캐시하면 무작위 id를
        // 쏘는 것만으로 TTL 10분짜리 빈 항목이 쌓인다 — @Cacheable(unless)로 막았다.
        Long missingId = scheduleId + 100_000L;

        assertThatThrownBy(() -> scheduleService.getSeats(missingId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> scheduleService.getTicketGrades(missingId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);

        assertThat(cacheManager.getCache(CacheNames.SEAT_LAYOUT).get(missingId)).isNull();
        assertThat(cacheManager.getCache(CacheNames.GRADE_PRICES).get(missingId)).isNull();
    }
}
