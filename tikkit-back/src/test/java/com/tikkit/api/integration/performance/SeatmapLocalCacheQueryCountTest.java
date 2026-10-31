package com.tikkit.api.integration.performance;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.entity.SeatStatus;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import com.tikkit.api.domain.performance.service.ScheduleService;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.service.ReservationService;
import com.tikkit.api.support.AbstractConcurrencyTest;
import com.tikkit.api.support.QueryCountReport;
import com.tikkit.api.support.QueryCounter;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좌석맵 로컬 캐시 경로의 문장 수와 계약을 고정한다 (Task 036).
 *
 * <p>{@code PerformanceQueryCountTest}가 고정한 좌석맵 2문장은 <b>단일 쿼리 전략(기본값)</b>의
 * 값이고 그대로 유지된다. 이 클래스는 {@code tikkit.cache.seatmap=local}로 띄운 컨텍스트에서
 * 미스·히트 경로를 따로 고정한다 — {@code CacheQueryCountTest}가 {@code cache.type=redis}를
 * 위해 컨텍스트를 나눈 것과 같은 이유다(프로퍼티가 다르면 컨텍스트가 어차피 분리된다).
 *
 * <p><b>Redis를 띄우지 않는다.</b> 이 캐시는 Caffeine이라 {@code cache.type}이 기본값
 * {@code none}이어도 동작한다. 008 9절이 "컨텍스트가 하나 더 늘면 Postgres 커넥션 고갈에 다시
 * 부딪힌다"고 남겼으므로, Redis 컨테이너까지 띄우는 베이스를 피해 가장 가벼운 쪽을 골랐다.
 *
 * <p><b>미스 경로 3문장이 "분리만 했을 때"의 값이다.</b> 측정 전용이던 {@code split} 전략은
 * 판정 후 지웠지만(교훈 5), 그 경로의 왕복 수는 캐시 미스가 그대로 보여준다 — 캐시 히트가
 * 되찾아야 하는 손해가 이 한 문장이다.
 */
@Tag("query-count")
@TestPropertySource(properties = "tikkit.cache.seatmap=local")
class SeatmapLocalCacheQueryCountTest extends AbstractConcurrencyTest {

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    @Qualifier("seatmapCacheManager")
    private CacheManager seatmapCacheManager;

    private QueryCounter queryCounter;
    private Long scheduleId;
    private Long gradeId;
    private Long memberId;

    @BeforeEach
    void setUpFixture() {
        queryCounter = new QueryCounter(entityManagerFactory);
        GradeWithSeats fixture = createOnSaleGradeWithSeats(10);
        scheduleId = fixture.scheduleId();
        gradeId = fixture.gradeId();
        memberId = memberRepository.save(Member.builder()
                .email("seatmap-local@tikkit.test")
                .password("암호화되지않은값이지만로그인을타지않는다")
                .name("좌석맵카운터")
                .phone("010-0000-0000")
                .role(MemberRole.USER)
                .build()).getId();

        // truncate가 id를 RESTART IDENTITY로 1부터 되돌리므로, 비우지 않으면 **직전 테스트의
        // 배치를 히트한다**(같은 scheduleId에 다른 좌석이 들어 있다). AbstractRedisCacheContainerTest가
        // Redis에서 키 공간 분리로 막는 것과 같은 문제인데, 로컬 캐시는 컨텍스트 전용
        // 인스턴스라 다른 테스트에 피해를 주지 않으므로 그냥 비우면 된다.
        seatmapCacheManager.getCache(CacheNames.SEAT_LAYOUT).clear();
    }

    @Test
    @DisplayName("전략이 local이면 Caffeine 캐시 매니저가 올라가고 좌석맵 캐시만 들고 있다")
    void 캐시_매니저_구성() {
        assertThat(seatmapCacheManager).isInstanceOf(CaffeineCacheManager.class);
        // 이름을 고정해 두지 않으면 오타가 조용히 "새 캐시"를 만들어 적중률 0%로만 나타난다.
        // 카탈로그 캐시는 이 매니저에 없어야 한다 — 그쪽은 Redis로 가고, 섞이면 측정 군에서
        // 처리량 변화를 좌석맵에 귀속할 수 없다.
        assertThat(seatmapCacheManager.getCacheNames()).containsExactly(CacheNames.SEAT_LAYOUT);
    }

    @Test
    @DisplayName("좌석맵 미스는 3문장, 히트는 2문장 — 캐시가 없애는 것은 배치 조회 하나다")
    void 좌석맵_미스_3문장_히트_2문장() {
        long miss = queryCounter.count(() -> scheduleService.getSeats(scheduleId));
        long hit = queryCounter.count(() -> scheduleService.getSeats(scheduleId));
        QueryCountReport.record("GET /schedules/{id}/seats (로컬 캐시, 미스)", miss);
        QueryCountReport.record("GET /schedules/{id}/seats (로컬 캐시, 히트)", hit);

        // 미스: 회차 존재 확인 + 배치 + 상태 = 3. 단일 쿼리(2문장)보다 하나 많다 —
        // **분리 자체는 순손실이고**(029 실측 6.63 -> 8.62ms) 그 손해를 캐시 히트로 되찾는 구조다.
        assertThat(miss).isEqualTo(3);
        // 히트: 회차 존재 확인 + 상태 = 2. 1이 아닌 게 핵심이다 — 상태를 캐시하면 팔린 좌석이
        // TTL 동안 "선택 가능"으로 보인다. gradePrices가 잔여 수량을 매번 세는 것과 같은 선이다.
        assertThat(hit).isEqualTo(2);
    }

    @Test
    @DisplayName("히트 경로의 문장 수가 좌석 수에 흔들리지 않는다")
    void 문장_수는_좌석_수와_무관하다() {
        Long bigScheduleId = createOnSaleGradeWithSeats(200).scheduleId();

        long miss = queryCounter.count(() -> scheduleService.getSeats(bigScheduleId));
        long hit = queryCounter.count(() -> scheduleService.getSeats(bigScheduleId));

        // 10석과 200석이 같아야 한다. 늘어나면 좌석마다 뭔가를 당겨 쓴 것이고, 그건
        // 캐시 히트에서도 DB로 가는 N+1이라 캐시가 아무것도 아끼지 못한다.
        assertThat(miss).isEqualTo(3);
        assertThat(hit).isEqualTo(2);
        assertThat(scheduleService.getSeats(bigScheduleId)).hasSize(200);
    }

    @Test
    @DisplayName("캐시를 켜도 응답이 단일 쿼리 결과와 완전히 같다 — FE 계약이 바뀌지 않는다")
    void 응답은_단일_쿼리와_동일하다() {
        // 캐시를 채운 뒤 비교한다. 조립 경로(배치 + 상태 합성)가 단일 쿼리와 같은 순서·같은
        // 값을 내는지는 이 Task에서 유일하게 **계약**에 해당하는 부분이다. 응답 바이트가
        // 달라지면 033이 기각한 "응답 축소"를 모르게 해버리는 것이고, 그러면 before/after
        // 비교의 전제가 깨진다.
        List<ScheduleSeatResponse> assembled = scheduleService.getSeats(scheduleId);
        List<ScheduleSeatResponse> single = scheduleSeatRepository.findSeatResponsesByScheduleId(scheduleId);

        assertThat(assembled).containsExactlyElementsOf(single);
    }

    @Test
    @DisplayName("좌석 상태는 선점 직후 재조회에 즉시 보인다 — 캐시가 상태를 지연시키지 않는다")
    void 좌석_상태는_즉시_반영된다() {
        // given: 캐시를 미리 채운다. 비어 있으면 상태 지연이 생길 수 없어서 테스트가 무의미해진다.
        List<ScheduleSeatResponse> before = scheduleService.getSeats(scheduleId);
        assertThat(before).allMatch(seat -> seat.status() == SeatStatus.AVAILABLE);
        Long targetSeatId = before.getFirst().id();

        // when: 실제 선점 경로로 좌석 하나를 잡는다. DB를 직접 UPDATE하지 않는 이유는
        // ck_schedule_seats_status_holder다 — HELD는 reservation_id가 반드시 있어야 한다(V4).
        reservationService.create(memberId, new ReservationCreateRequest(
                scheduleId, gradeId, 1, List.of(targetSeatId)));

        // then: 배치는 캐시에서 오고 상태는 DB에서 와야 HELD가 보인다. 이 단정이 깨지면
        // 상태까지 캐시된 것이고, 그건 이 설계가 008부터 거부해 온 선택이다.
        assertThat(scheduleService.getSeats(scheduleId))
                .filteredOn(seat -> seat.id().equals(targetSeatId))
                .singleElement()
                .extracting(ScheduleSeatResponse::status).isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("좌석이 깔리지 않은 회차는 캐시를 채우지 않는다 — 빈 항목으로 상한을 소모할 수 없다")
    void 빈_결과는_캐시되지_않는다() {
        // 키가 경로 변수라 사용자가 키 공간을 고를 수 있다. 빈 결과를 캐시하면 보관 상한
        // (seatmap-max-entries)이 빈 항목으로 차고, 그러면 실제 회차가 LRU로 밀려난다 —
        // 항목이 큰 캐시에서는 CatalogCache보다 피해가 크다. unless로 막는다.
        Long emptyScheduleId = createOnSaleGradeWithSeats(0).scheduleId();

        assertThat(scheduleService.getSeats(emptyScheduleId)).isEmpty();
        assertThat(seatmapCacheManager.getCache(CacheNames.SEAT_LAYOUT).get(emptyScheduleId)).isNull();
    }

    @Test
    @DisplayName("없는 회차는 전략과 무관하게 404다 — 조립 경로가 존재 확인을 건너뛰지 않는다")
    void 없는_회차는_404() {
        // 단일 쿼리 경로와 조립 경로가 갈리는 자리에 존재 확인이 있다. 조립 쪽에서 그걸
        // 빼먹으면 빈 좌석맵 200이 내려가고, FE는 "좌석이 없는 공연"으로 그린다.
        assertThatThrownBy(() -> scheduleService.getSeats(999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
