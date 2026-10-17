package com.tikkit.api.domain.performance.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.performance.dto.ScheduleSeatResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeResponse;
import com.tikkit.api.domain.performance.dto.TicketGradeRow;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.ScheduleSeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private final Timer buildTimer;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           ScheduleSeatRepository scheduleSeatRepository,
                           CatalogCache catalogCache,
                           MeterRegistry meterRegistry) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleSeatRepository = scheduleSeatRepository;
        this.catalogCache = catalogCache;
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
     * <b>Task 029: 캐시하지 않는다.</b> 응답 8필드 중 7개가 불변이라 그쪽만 캐시하고 상태는
     * 매번 읽는 구조를 만들어 실측했는데, 캐시 항목이 2,000석에 337,885바이트라서 Redis에서
     * 받아 역직렬화하는 비용이 이 쿼리 하나보다 비쌌다. 적중률 99.97%에서도 캐시를 켠 쪽이
     * 느렸고, 그 비용은 좌석 수에 비례해 같이 커진다. 좌석맵의 확장성 해법은 캐싱이 아니라
     * 응답 크기를 줄이는 것이고 그건 Task 030이 맡는다
     * ({@code docs/improvements/008-caching.md} 4절).
     * <p>
     * 그래서 좌석 좌표는 공연장마다 고정이지만 상태와 함께 매번 조회한다.
     */
    public List<ScheduleSeatResponse> getSeats(Long scheduleId) {
        return buildTimer.record(() -> {
            requireScheduleExists(scheduleId);
            return scheduleSeatRepository.findSeatResponsesByScheduleId(scheduleId);
        });
    }

    private void requireScheduleExists(Long scheduleId) {
        if (!scheduleRepository.existsById(scheduleId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }
}
