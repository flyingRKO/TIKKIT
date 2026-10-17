package com.tikkit.api.common.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 캐시 이름과 TTL을 한곳에 모아둔다 (Task 029).
 *
 * <p>이름을 문자열 리터럴로 {@code @Cacheable}에 흩뿌리지 않는 이유가 두 가지다. 하나는 오타가
 * 조용히 "새 캐시"를 만들어서 적중률 0%로 나타난다는 것. 다른 하나는 {@link #TTLS}에 선언한
 * 이름만 기동 시점에 미리 만들어지기 때문에(= eager) 지표 시리즈가 요청 전에 등록된다는 것이다 —
 * lazy로 두면 갓 띄운 스택의 Grafana가 "No data"가 되어 <b>적중률 0과 계측 없음을 구분할 수
 * 없다</b>. Task 025의 {@code ReservationService.registerAttemptCounters()}와 같은 이유다.
 *
 * <h2>TTL을 왜 이렇게 잡았나</h2>
 * 공연 데이터를 바꾸는 쓰기 API가 이 서비스에 없다 — 엔티티에 setter가 0개고, 관리자 API도 없다.
 * 유일한 변경 지점은 {@code ReservationExpiryScheduler}가 60초마다 돌리는
 * {@code PerformanceRepository.recalculateDerivedFields()}이고, 그게
 * {@code performances.status}/{@code start_date}/{@code end_date}를 바꾼다. 세 컬럼 모두 목록·상세
 * 응답에 들어 있다.
 * <p>
 * 그래서 목록·상세 TTL을 배치 주기와 같은 60초로 맞췄다. 실제 무효화는 배치가 변경 행 수를
 * 보고 직접 evict하므로, <b>TTL은 그 evict가 실패했을 때의 안전망</b>이다.
 * <p>
 * 반면 등급 가격은 회차 생성 후 바뀌지 않는다(바꿀 코드 경로가 없다). 10분은 "불변이지만
 * 영구 캐시는 두지 않는다"는 선 정도의 의미다 — 시드를 다시 넣거나 회차를 수동으로 고쳤을 때
 * 재기동 없이 따라오게 하려는 것이다.
 *
 * <h2>좌석 배치는 왜 빠졌나</h2>
 * {@code seatLayout}(좌표·등급)이 여기 있었다. 불변이라 캐시할 수 있는 모양이었지만 항목이
 * 2,000석에 337,885바이트여서, Redis에서 받아 역직렬화하는 비용이 원래 쿼리보다 비쌌다 —
 * <b>적중률 99.97%에서도 캐시를 켠 쪽이 느렸다.</b> 게다가 그 비용은 좌석 수에 비례해 같이
 * 커지므로 규모가 커질수록 나빠진다. 좌석맵의 확장성 해법은 캐싱이 아니라 응답 크기를 줄이는
 * 것이고 그건 Task 030이 맡는다 ({@code docs/improvements/008-caching.md} 4절).
 * <p>
 * 그래서 이 목록에 새 캐시를 넣을 때는 <b>항목 크기</b>를 같이 본다. 값이 커지면 캐시가 아끼는
 * DB 시간보다 네트워크·역직렬화 비용이 먼저 커진다.
 */
public final class CacheNames {

    /** 공연 목록. 키는 {@code category|status|page|size} — keyword 검색은 캐시하지 않는다. */
    public static final String PERFORMANCE_LIST = "performanceList";

    /** 공연 상세(회차 목록 포함). 키는 공연 id. */
    public static final String PERFORMANCE_DETAIL = "performanceDetail";

    /** 회차의 등급·가격. 잔여 수량은 들어 있지 않다. 키는 회차 id. */
    public static final String GRADE_PRICES = "gradePrices";

    /** 배치가 비워야 하는 캐시 — 공연 파생값(status/start_date/end_date)에 의존하는 것들. */
    public static final List<String> PERFORMANCE_DERIVED = List.of(PERFORMANCE_LIST, PERFORMANCE_DETAIL);

    /** 기동 시점에 미리 만들 캐시와 각각의 TTL. */
    public static final Map<String, Duration> TTLS = Map.of(
            PERFORMANCE_LIST, Duration.ofSeconds(60),
            PERFORMANCE_DETAIL, Duration.ofSeconds(60),
            GRADE_PRICES, Duration.ofMinutes(10));

    private CacheNames() {
    }
}
