package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.config.CacheProperties;
import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.ScheduleSeatLayoutRow;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.entity.SeatStatus;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ScheduleService {

    /**
     * 좌석맵 응답을 <b>만드는</b> 데 걸린 시간 (Task 029). DB 조회 + 조립까지만 포함하고
     * JSON 직렬화는 포함하지 않는다.
     *
     * <p>이게 왜 있나: 007이 "캐시 이득은 좌석맵 237.9KB의 직렬화 비용에서 나올 가능성이 크다"고
     * 넘겼는데, 그 가설을 재려면 응답 시간을 <b>만드는 시간</b>과 <b>쓰는 시간</b>으로 갈라야 한다.
     * {@code http_server_requests}(전체) 에서 이 값을 빼면 직렬화 + 서블릿·nginx 오버헤드가 남는다.
     * 캐시가 줄이는 건 앞쪽뿐이므로, 뒤쪽이 크면 캐시로 p95를 개선할 여지가 작다는 뜻이다.
     */
    private static final String SEATMAP_BUILD_METRIC = "tikkit.seatmap.build";

    private final ScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final CatalogCache catalogCache;
    private final SeatLayoutCache seatLayoutCache;
    private final CacheProperties.SeatmapStrategy seatmapStrategy;
    private final Timer buildTimer;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           ScheduleSeatRepository scheduleSeatRepository,
                           CatalogCache catalogCache,
                           SeatLayoutCache seatLayoutCache,
                           CacheProperties cacheProperties,
                           MeterRegistry meterRegistry) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleSeatRepository = scheduleSeatRepository;
        this.catalogCache = catalogCache;
        this.seatLayoutCache = seatLayoutCache;
        // 전략을 필드에 복사해 둔다. 요청마다 프로퍼티를 다시 읽을 이유가 없고,
        // 기동 후 바뀌지 않는 값이라는 것도 같이 드러난다 — 군을 바꾸려면 재기동한다.
        this.seatmapStrategy = cacheProperties.seatmap();
        // 기동 시점에 미리 등록한다. 한 번도 기록되지 않은 미터는 시리즈가 없어서
        // Grafana가 "No data"를 띄우고, 그러면 0과 계측 없음을 구분할 수 없다
        // (Task 025의 registerAttemptCounters()와 같은 이유).
        //
        // 퍼센타일 히스토그램을 켠다. 분위수를 미리 계산하는 percentile 방식은 BE 2대의 값을
        // 합산할 수 없어서 이 프로젝트는 처음부터 버킷 방식을 쓴다(application.yml 주석).
        this.buildTimer = Timer.builder(SEATMAP_BUILD_METRIC)
                .description("좌석맵 응답 생성 시간 (DB 조회 + 조립, 직렬화 제외)")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(meterRegistry);
    }

    /**
     * 회차의 등급별 가격과 예매 가능 좌석 수를 조회한다.
     * <p>
     * 지정석 전환 후 잔여 수량은 {@code ticket_grades}의 컬럼이 아니라 {@code schedule_seats}의
     * AVAILABLE 건수다. 응답 형식({@code remainingQuantity})은 그대로 둬서 FE 계약이 바뀌지 않는다 —
     * 재고의 원천만 뒤집혔고 "잔여 N석"이라는 의미는 같다.
     * <p>
     * 좌석이 하나도 없는 등급은 COUNT 결과에 키가 없으므로 0으로 채운다. {@code null}을 내려주면
     * FE의 매진 판정({@code remainingQuantity === 0})이 빗나간다.
     * <p>
     * <b>Task 029: 가격만 캐시하고 잔여 수량은 매번 센다.</b> 가격은 회차 생성 후 불변이고
     * 잔여 수량은 예매마다 바뀐다 — 둘을 한 덩어리로 캐시하면 매진된 회차가 TTL 동안 "잔여 있음"으로
     * 보인다.
     */
    public List<TicketGradeResponse> getTicketGrades(Long scheduleId) {
        List<TicketGradeRow> rows = catalogCache.gradePrices(scheduleId);
        if (rows.isEmpty()) {
            // 등급이 없는 회차와 없는 회차를 가른다. 빈 결과일 때만 확인하므로
            // 정상 경로에서는 existsById 왕복이 사라진다 (기존 3문장 -> 2문장).
            requireScheduleExists(scheduleId);
            return List.of();
        }

        Map<Long, Integer> available = scheduleSeatRepository.countAvailableByScheduleId(scheduleId);

        return rows.stream()
                .map(row -> new TicketGradeResponse(row.id(), row.grade(), row.price(),
                        available.getOrDefault(row.id(), 0)))
                .toList();
    }

    /**
     * 회차의 좌석 배치도를 조회한다.
     * <p>
     * 회차의 모든 좌석을 평면 배열로 한 번에 내려준다. 큰 공연장이면 수천 건이라 응답이 수백 KB가 되는데,
     * 배치도는 어차피 전부 그려야 하므로 페이지를 쪼개는 게 의미가 없다.
     * <p>
     * <b>Task 029: Redis 캐싱을 기각했다.</b> 응답 8필드 중 7개가 불변이라 그쪽만 캐시하고 상태는
     * 매번 읽는 구조를 만들어 실측했는데, 캐시 항목이 2,000석에 337,885바이트라서 Redis에서
     * 받아 역직렬화하는 비용이 이 쿼리 하나보다 비쌌다. 적중률 99.97%에서도 캐시를 켠 쪽이
     * 느렸고, 그 비용은 좌석 수에 비례해 같이 커진다 ({@code docs/improvements/008-caching.md} 4절).
     * <p>
     * <b>Task 036: 같은 구조를 로컬 캐시로 다시 본다.</b> 029의 같은 측정에서 Caffeine은
     * 4.98ms로 네 군 중 가장 빨랐다 — 직렬화도 네트워크도 없기 때문이다. 그때 로컬을 안 고른
     * 이유는 힙 용량과 분산 evict였고, <b>이 캐시는 불변 필드만 담아서 evict가 필요 없다.</b>
     * 남은 질문이 포화 구간이라서(008 4.2절이 "DB가 포화에 가까워지면 뒤집힐 수 있다"를 반례
     * 조건으로 남겼고 040이 벽을 postgres CPU 4코어로 특정했다) 전략을
     * {@code tikkit.cache.seatmap}으로 갈아 끼우며 측정한다. <b>기본값은 단일 쿼리다</b> —
     * before가 사라지지 않게 한다.
     */
    public List<ScheduleSeatResponse> getSeats(Long scheduleId) {
        return buildTimer.record(() -> {
            requireScheduleExists(scheduleId);
            return switch (seatmapStrategy) {
                case SINGLE -> scheduleSeatRepository.findSeatResponsesByScheduleId(scheduleId);
                // SPLIT과 LOCAL은 같은 코드 경로이고 차이는 캐시 매니저뿐이다. SPLIT이면
                // seatmapCacheManager가 NoOpCacheManager라 @Cacheable이 통과만 한다 —
                // cache.type=none이 "어노테이션을 지우지 않고 캐시를 끄는" 것과 같은 구조이고,
                // 그래서 분리 손해만 따로 재는 군이 코드 분기 없이 성립한다.
                case SPLIT, LOCAL -> assembleFromLayout(scheduleId);
            };
        });
    }

    /**
     * 캐시된 배치에 실시간 상태를 합쳐 좌석맵 응답을 만든다 (Task 036).
     *
     * <p>순서는 배치가 들고 있다({@code ORDER BY pos_y, pos_x}로 조회했고 캐시에 그대로 들어간다).
     * 상태 맵은 id로 찾기만 하므로 정렬하지 않는다.
     *
     * <p>상태 맵에 없는 좌석은 응답에서 뺀다. 좌석 구성이 바뀌지 않는 한 일어나지 않는 경우이고
     * (그래서 이 캐시에 evict가 없다), 일어났다면 캐시가 낡은 것이다 — <b>조용히 넘기지 않고
     * 경고를 남긴다.</b> 반증된 가정을 코드에 남겨두면 다음 사람이 그걸 근거로 움직인다
     * ({@code docs/improvements/README.md} 교훈 4).
     */
    private List<ScheduleSeatResponse> assembleFromLayout(Long scheduleId) {
        List<ScheduleSeatLayoutRow> layout = seatLayoutCache.layout(scheduleId);
        if (layout.isEmpty()) {
            return List.of();
        }

        Map<Long, SeatStatus> statuses = scheduleSeatRepository.findSeatStatusesByScheduleId(scheduleId);

        List<ScheduleSeatResponse> seats = new ArrayList<>(layout.size());
        for (ScheduleSeatLayoutRow row : layout) {
            SeatStatus status = statuses.get(row.scheduleSeatId());
            if (status == null) {
                continue;
            }
            seats.add(new ScheduleSeatResponse(row.scheduleSeatId(), row.section(), row.rowLabel(),
                    row.seatNumber(), row.posX(), row.posY(), status, row.ticketGradeId()));
        }

        if (seats.size() != layout.size()) {
            log.warn("좌석맵 캐시가 현재 좌석 구성과 어긋난다 — scheduleId={}, 캐시 {}건, 응답 {}건. "
                            + "좌표·등급을 바꾸는 경로가 생겼다면 이 캐시에 무효화가 필요하다",
                    scheduleId, layout.size(), seats.size());
        }
        return seats;
    }

    private void requireScheduleExists(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }
}
