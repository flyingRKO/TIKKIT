package com.tikkit.api.integration.performance;

import com.tikkit.api.common.config.CacheNames;
import com.tikkit.api.domain.performance.dto.PerformanceDetailResponse;
import com.tikkit.api.domain.performance.dto.PerformanceListPage;
import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.service.CatalogCache;
import com.tikkit.api.domain.performance.service.PerformanceService;
import com.tikkit.api.support.AbstractRedisCacheContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 값이 Redis를 왕복해도 그대로 돌아오는지 고정한다 (Task 029).
 *
 * <p><b>왜 이 테스트가 필요한가</b><br>
 * 직렬화는 조용히 틀린다. {@code BigDecimal}은 scale이 날아가고({@code 150000} vs
 * {@code 150000.00}), {@code Instant}는 숫자/문자열 표기에 따라 정밀도가 깎이고, record는
 * 역직렬화 생성자를 못 찾으면 {@code LinkedHashMap}으로 복원된다. 전부 "저장은 됐는데 꺼낸
 * 값이 다른" 형태라 캐시를 켠 뒤 한참 지나서 엉뚱한 증상으로 나타난다.
 *
 * <p>특히 record는 {@code -parameters} 컴파일 옵션이 있어야 파라미터명이 바이트코드에 남는다.
 * Spring Boot Gradle 플러그인이 기본으로 넣어주지만 <b>검증한 적이 없는 가정</b>이었다 —
 * 이 테스트가 그 가정을 고정한다.
 *
 * <p>제네릭 컬렉션({@code List<TicketGradeRow>})이 특히 위험하다. 타입 정보를 같이 쓰지 않으면
 * 원소가 {@code LinkedHashMap}이 되고, 캐스팅하는 쪽에서 {@code ClassCastException}이 난다.
 */
class CacheSerializationTest extends AbstractRedisCacheContainerTest {

    @Autowired
    private CatalogCache catalogCache;
    @Autowired
    private PerformanceService performanceService;

    private Long scheduleId;
    private Long performanceId;

    @BeforeEach
    void setUpFixture() {
        GradeWithSeats fixture = createOnSaleGradeWithSeats(3);
        scheduleId = fixture.scheduleId();
        performanceId = jdbcTemplate.queryForObject(
                "SELECT performance_id FROM schedules WHERE id = ?", Long.class, scheduleId);
        // Redis 비우기는 AbstractRedisCacheContainerTest가 FLUSHDB로 처리한다.
    }

    @Test
    @DisplayName("등급 가격 목록은 제네릭 원소 타입과 BigDecimal scale을 유지한 채 왕복한다")
    void 등급_가격_왕복() {
        List<TicketGradeRow> written = catalogCache.gradePrices(scheduleId);
        assertThat(written).isNotEmpty();

        // 캐시에서 직접 꺼낸다 — 두 번째 호출로 받으면 Redis를 거치지 않았어도 통과할 수 있다.
        awaitCached(CacheNames.GRADE_PRICES, scheduleId);
        Object cached = cacheManager.getCache(CacheNames.GRADE_PRICES).get(scheduleId).get();

        assertThat(cached).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<TicketGradeRow> read = (List<TicketGradeRow>) cached;
        // 원소가 LinkedHashMap으로 복원되면 여기서 깨진다.
        assertThat(read).containsExactlyElementsOf(written);
        assertThat(read.getFirst().price())
                // BigDecimal은 equals가 scale까지 본다. compareTo로 값만 보는 비교를 따로 둬서
                // "값은 같은데 scale이 달라졌다"와 "값이 틀렸다"를 구분한다.
                .isEqualByComparingTo(written.getFirst().price())
                .isEqualTo(written.getFirst().price());
    }

    @Test
    @DisplayName("좌석 배치 목록은 record 원소 그대로 왕복한다")
    void 좌석_배치_왕복() {
        List<ScheduleSeatLayoutRow> written = catalogCache.seatLayout(scheduleId);
        assertThat(written).hasSize(3);

        awaitCached(CacheNames.SEAT_LAYOUT, scheduleId);
        Object cached = cacheManager.getCache(CacheNames.SEAT_LAYOUT).get(scheduleId).get();

        @SuppressWarnings("unchecked")
        List<ScheduleSeatLayoutRow> read = (List<ScheduleSeatLayoutRow>) cached;
        assertThat(read).containsExactlyElementsOf(written);
    }

    @Test
    @DisplayName("공연 상세는 중첩 record와 Instant·enum·LocalDate를 유지한 채 왕복한다")
    void 공연_상세_왕복() {
        PerformanceDetailResponse written = performanceService.getDetail(performanceId);
        assertThat(written.schedules()).isNotEmpty();

        awaitCached(CacheNames.PERFORMANCE_DETAIL, performanceId);
        Object cached = cacheManager.getCache(CacheNames.PERFORMANCE_DETAIL).get(performanceId).get();

        assertThat(cached).isInstanceOf(PerformanceDetailResponse.class);
        PerformanceDetailResponse read = (PerformanceDetailResponse) cached;
        // record의 equals가 중첩 List<ScheduleSummaryResponse>까지 비교한다.
        assertThat(read).isEqualTo(written);
        // Instant는 나노초를 가진다. 밀리초로 깎이면 equals는 통과하지 못하지만,
        // 어디가 틀렸는지 바로 보이게 따로 짚어둔다.
        Instant writtenShowAt = written.schedules().getFirst().showAt();
        assertThat(read.schedules().getFirst().showAt()).isEqualTo(writtenShowAt);
    }

    @Test
    @DisplayName("공연 목록 페이지는 PageImpl 대신 record라 왕복한다 — totalElements가 보존된다")
    void 공연_목록_왕복() {
        PerformanceListPage written = performanceService.list(null, null, "ON_SALE", 0, listPageSize);

        awaitCached(CacheNames.PERFORMANCE_LIST, listCacheKey());
        Object cached = cacheManager.getCache(CacheNames.PERFORMANCE_LIST).get(listCacheKey()).get();

        assertThat(cached).isInstanceOf(PerformanceListPage.class);
        assertThat((PerformanceListPage) cached).isEqualTo(written);
        assertThat(((PerformanceListPage) cached).totalElements()).isEqualTo(written.totalElements());
    }
}
