package com.tikkit.api.domain.queue.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 틱 간격과 ShedLock 값의 관계를 고정한다 (Task 037).
 *
 * <h2>왜 리플렉션인가</h2>
 * 어노테이션 속성은 컴파일 타임 상수여야 해서 {@code lockAtLeastFor}에 "간격의 90%"라는 관계를
 * 쓸 수 없다. Task 037 전에는 간격이 설정값({@code tikkit.queue.admit-interval})이고
 * {@code lockAtLeastFor}는 어노테이션 리터럴이어서 <b>둘이 수동으로 묶여 있었고</b>, 간격만
 * 크게 바꾸면 "설정은 5초인데 실제로는 1초마다 입장"이 되면서 증상이 "예상 대기시간이 계속
 * 틀리다"로만 나타났다 — 조용히 틀리는 종류다.
 * <p>
 * 간격을 상수로 내려서 설정 쪽 결합은 없앴지만 <b>두 상수 사이의 관계는 여전히 사람이 지켜야
 * 한다.</b> 그래서 어노테이션을 직접 읽어 관계를 어서션한다. 013이 쿼리 카운트 기준값을 외부
 * 베이스라인 파일로 빼지 않은 것과 같은 이유다 — 관계가 테스트 코드 안에 있으면 고치려면
 * 왜 바뀌었는지를 PR에 써야 하고, 데이터 파일이면 한 줄 diff로 지나간다.
 *
 * <h2>Spring 컨텍스트를 띄우지 않는다</h2>
 * 검증 대상이 바이트코드의 어노테이션이라 빈이 필요 없다. {@code SchedulingConfig}가
 * {@code @Profile("!test")}라 테스트에서는 어드바이저가 아예 없어서, 컨텍스트를 띄워도
 * 이 관계를 <b>동작으로는</b> 검증할 수 없다({@code QueueAdmissionTest}가 그 실증이다).
 */
class QueueAdmissionSchedulerContractTest {

    private static final Method RUN = runMethod();

    private static Method runMethod() {
        try {
            return QueueAdmissionScheduler.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("QueueAdmissionScheduler.run()이 없다", e);
        }
    }

    /** Spring이 {@code fixedDelayString}을 푸는 방식 그대로 읽는다. */
    private static Duration parse(String value) {
        return DurationStyle.detectAndParse(value);
    }

    @Test
    @DisplayName("틱 간격이 1초다 — 이 값이어야 '틱당 예산 = 초당 입장 인원'이 성립한다")
    void 틱_간격이_1초다() {
        Scheduled scheduled = RUN.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();

        // run()이 properties.admitRatePerSecond()를 틱당 예산으로 그대로 쓴다. 간격이 1초가
        // 아니면 그 등식이 깨지고, 설정 키 이름("초당")이 뜻과 어긋난다.
        assertThat(parse(scheduled.fixedDelayString())).isEqualTo(Duration.ofSeconds(1));
        assertThat(parse(QueueAdmissionScheduler.ADMIT_INTERVAL)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("lockAtLeastFor가 틱 간격보다 작다 — 같거나 크면 틱을 거른다")
    void 락_최소_보유_시간이_틱_간격보다_작다() {
        SchedulerLock lock = RUN.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();

        Duration interval = parse(QueueAdmissionScheduler.ADMIT_INTERVAL);
        Duration lockAtLeastFor = parse(lock.lockAtLeastFor());

        // 작업이 수 ms라 락을 즉시 놓으면 같은 틱 안에서 다른 인스턴스가 다시 집는다.
        // 그래서 간격에 가깝게 붙여 두지만, 넘기면 다음 틱이 락을 못 얻어 입장이 건너뛰어진다.
        assertThat(lockAtLeastFor).isLessThan(interval);
        assertThat(lockAtLeastFor).isGreaterThanOrEqualTo(interval.dividedBy(2));
    }

    @Test
    @DisplayName("lockAtMostFor가 틱 간격보다 크고 만료 배치(5분)보다 짧다")
    void 락_최대_보유_시간이_구간_안이다() {
        SchedulerLock lock = RUN.getAnnotation(SchedulerLock.class);
        Duration interval = parse(QueueAdmissionScheduler.ADMIT_INTERVAL);
        Duration lockAtMostFor = parse(lock.lockAtMostFor());

        // 정상 실행이 이 시한에 걸리면 안 되므로 간격보다는 커야 한다.
        assertThat(lockAtMostFor).isGreaterThan(interval);
        // 락을 들고 죽으면 이 시간만큼 입장이 완전히 멈추고 순번이 얼어붙는다.
        // ReservationExpiryScheduler의 5분을 그대로 쓰지 않는 이유다.
        assertThat(lockAtMostFor).isLessThan(Duration.ofMinutes(1));
    }
}
