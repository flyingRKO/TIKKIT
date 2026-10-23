package com.tikkit.api.domain.queue.service;

import com.tikkit.api.domain.performance.repository.ScheduleRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * {@code schedules.queue_enabled} 스냅샷 (Task 031). 보호된 API가 요청마다 DB를 읽지 않게 한다.
 *
 * <h2>왜 Spring Cache를 쓰지 않았나</h2>
 * {@code tikkit.cache.type}의 기본값이 {@code none}({@code NoOpCacheManager})이다. 거기에 얹으면
 * <b>캐시를 끈 환경에서는 좌석 조회마다 DB로 간다</b> — 대기열이 캐시 토글에 묶이는 셈이고,
 * 세 토글이 서로 독립이어야 한다는 전제를 깬다.
 *
 * <h2>왜 {@code @Scheduled}로 갱신하지 않나</h2>
 * 두 가지가 동시에 걸린다.
 * <ol>
 *   <li>{@code SchedulingConfig}가 {@code @Profile("!test")}다. 테스트에서는 갱신이 돌지 않아
 *       스냅샷이 영원히 비어 있고, 그러면 <b>대기열 테스트 전체가 "플래그 off"로 조용히
 *       통과한다.</b> 가장 위험한 실패 모드다 — 초록색인데 아무것도 검증하지 않는다</li>
 *   <li>이 갱신은 JVM 로컬 상태라 인스턴스마다 돌아야 한다. 즉 {@code @SchedulerLock}을 붙이면
 *       안 되는데, 그건 "새 {@code @Scheduled}에는 반드시 락"이라는 프로젝트 규칙의 예외가 되고
 *       예외는 다음 사람이 밟는 함정이다</li>
 * </ol>
 * 조회 시 자기 갱신이면 둘 다 없다.
 *
 * <h2>감수한 것</h2>
 * <b>스탬피드</b>: TTL이 지난 직후 동시 요청 몇 개가 각자 재적재를 시도할 수 있다. 쿼리는
 * {@code select s.id from Schedule s where s.queueEnabled = true}(회차 테이블 전체 스캔, ms 단위)라
 * 10초에 한 번 몇 회 중복되는 비용은 측정되지 않는다. 락으로 막으면 <b>모든 조회가 락을 지나게
 * 되고 그게 더 비싸다.</b>
 * <p>
 * <b>staleness</b>: 플래그를 켜고 최대 TTL만큼은 일부 인스턴스가 통과시킨다. 이 플래그는 관리
 * API가 없어 사람이 SQL로 바꾸는 값이므로(공연 데이터에 쓰기 API가 없다는 Task 029의 논거와
 * 같다) 10초 지연은 문제가 아니다.
 */
public class QueueFlagRegistry {

    private final ScheduleRepository scheduleRepository;
    private final Duration ttl;

    /**
     * {@code volatile}이면 충분하다. 쓰기는 "새 스냅샷으로 통째로 교체"뿐이고 읽기는 그 참조를
     * 한 번 읽는 것뿐이라, 서로를 덮어써도 둘 다 유효한 스냅샷이다. 읽기-수정-쓰기가 없으니
     * {@code AtomicReference}의 CAS가 필요 없다.
     */
    private volatile Snapshot snapshot = Snapshot.stale();

    public QueueFlagRegistry(ScheduleRepository scheduleRepository, Duration ttl) {
        this.scheduleRepository = scheduleRepository;
        this.ttl = ttl;
    }

    /** 이 회차가 대기열을 쓰는가. */
    public boolean enabled(long scheduleId) {
        return current().ids().contains(scheduleId);
    }

    /** 대기열이 켜진 회차 전부. 입장 스케줄러가 순회한다. */
    public Set<Long> enabledScheduleIds() {
        return current().ids();
    }

    /**
     * 스냅샷을 버린다. 다음 조회가 DB를 다시 읽는다.
     * <p>
     * 테스트가 TTL을 기다리지 않고 플래그 변경을 반영시키려고 쓴다. 운영 코드에서 부르는 곳은
     * 없다 — 부르고 싶어지면 그건 플래그에 쓰기 API가 생겼다는 뜻이고, 그때는 이 캐시 구조
     * 자체를 다시 봐야 한다.
     */
    public void invalidate() {
        snapshot = Snapshot.stale();
    }

    private Snapshot current() {
        Snapshot cached = snapshot;
        if (!cached.isExpired(ttl)) {
            return cached;
        }
        List<Long> ids = scheduleRepository.findQueueEnabledScheduleIds();
        Snapshot reloaded = new Snapshot(Set.copyOf(ids), Instant.now());
        snapshot = reloaded;
        return reloaded;
    }

    private record Snapshot(Set<Long> ids, Instant loadedAt) {

        /** 아직 한 번도 읽지 않은 상태. {@code EPOCH}라 항상 만료로 판정된다. */
        static Snapshot stale() {
            return new Snapshot(Set.of(), Instant.EPOCH);
        }

        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(loadedAt.plus(ttl));
        }
    }
}
