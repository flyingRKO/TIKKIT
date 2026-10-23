package com.tikkit.api.domain.queue.service;

import com.tikkit.api.common.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 대기열 지표 (Task 031). 선례는 {@code tikkit.reservation.attempts}다.
 *
 * <h2>기동 시 0으로 미리 등록한다</h2>
 * 한 번도 증가하지 않은 Counter는 {@code /actuator/prometheus}에 시리즈 자체가 없어서, Grafana가
 * "No data"를 띄우고 <b>"0건"과 "계측 없음"을 구분할 수 없다.</b> 대기열은 평소 아무 일도 일어나지
 * 않는 기능이라 이 구분이 특히 중요하다 — 오픈런 때 "지표가 안 나온다"와 "아무도 줄을 안 섰다"가
 * 같이 보이면 판단할 수 없다.
 *
 * <h2>{@code scheduleId}를 태그로 넣지 않는다</h2>
 * 회차 수만큼 시리즈가 늘어나는 무한 카디널리티다. 회차별로 봐야 하면 지표가 아니라 로그로
 * 남긴다. 그리고 Micrometer는 같은 이름에 태그 <b>키 집합</b>이 다른 미터를 거부하므로
 * ({@code reservation.attempts}에서 이미 겪은 제약) 태그 구성을 나중에 바꾸기도 어렵다.
 *
 * <h2>Gauge가 Redis를 왕복하지 않는다</h2>
 * Gauge 함수는 Prometheus 스크랩마다 호출된다. 거기서 {@code ZCARD}를 돌리면 왕복이
 * "켜진 회차 수 × 스크랩 주기"로 늘어난다. 그래서 입장 스케줄러가 틱마다 계산해
 * {@link AtomicLong}에 담고, Gauge는 그 값만 읽는다(왕복 0).
 */
public class QueueMetrics {

    private static final String ADMITTED_METRIC = "tikkit.queue.admitted";
    private static final String PASS_CHECKS_METRIC = "tikkit.queue.pass.checks";
    private static final String WAITING_SIZE_METRIC = "tikkit.queue.waiting.size";

    private static final String RESULT_PASS = "pass";
    private static final String RESULT_REJECT = "reject";
    private static final String REASON_OK = "OK";

    private final MeterRegistry meterRegistry;
    private final Counter admitted;
    private final AtomicLong waitingSize = new AtomicLong();

    public QueueMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.admitted = meterRegistry.counter(ADMITTED_METRIC);

        // 입장권 검증 결과. 성공에도 reason을 붙인다 — 태그 키 집합이 같아야 한다.
        passCheckCounter(RESULT_PASS, REASON_OK);
        passCheckCounter(RESULT_REJECT, ErrorCode.QUEUE_PASS_REQUIRED.name());
        passCheckCounter(RESULT_REJECT, ErrorCode.QUEUE_PASS_EXPIRED.name());

        Gauge.builder(WAITING_SIZE_METRIC, waitingSize, AtomicLong::get)
                .description("대기열이 켜진 회차 전체의 대기 인원 합계")
                .register(meterRegistry);
    }

    /** 입장시킨 인원을 누적한다. */
    public void recordAdmitted(long count) {
        admitted.increment(count);
    }

    /** 입장권 검증 통과. */
    public void recordPass() {
        passCheckCounter(RESULT_PASS, REASON_OK).increment();
    }

    /** 입장권 검증 거절. {@code reason}은 {@link ErrorCode} 이름이라 값이 유계다. */
    public void recordReject(ErrorCode reason) {
        passCheckCounter(RESULT_REJECT, reason.name()).increment();
    }

    /** 입장 스케줄러가 틱마다 호출한다. Gauge는 이 값을 읽을 뿐이다. */
    public void updateWaitingSize(long total) {
        waitingSize.set(total);
    }

    private Counter passCheckCounter(String result, String reason) {
        return meterRegistry.counter(PASS_CHECKS_METRIC, "result", result, "reason", reason);
    }
}
