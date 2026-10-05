package com.tikkit.api.common.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Redis 분산 락으로 메서드를 감싼다 (Task 020 비교 실험 전용 — 실험이 끝나면 제거한다).
 * <p>
 * <b>반드시 트랜잭션 바깥에서 걸려야 한다.</b> 트랜잭션 안에서 락을 잡고 풀면 커밋 전에 락이
 * 풀려서, 다음 요청이 아직 커밋되지 않은 데이터를 못 보고 통과한다. {@link DistributedLockAspect}가
 * 진입 시점에 트랜잭션 활성 여부를 확인해 이 상황을 막는다.
 *
 * @see DistributedLockAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /**
     * 락 키를 만드는 SpEL 식. 메서드 파라미터를 {@code #이름}으로 참조한다.
     * <p>
     * record 파라미터는 프로퍼티 문법과 메서드 호출 문법이 <b>둘 다</b> 통한다 — 스프링의
     * {@code ReflectivePropertyAccessor}가 record 접근자를 인식한다.
     * {@code DistributedLockAspectTest}가 두 문법을 모두 단정해 둬서 동작이 바뀌면 테스트가 잡는다.
     * <pre>
     * "'hold:' + #memberId + ':' + #request.ticketGradeId()"
     * "'hold:' + #memberId + ':' + #request.ticketGradeId"
     * </pre>
     * {@code #이름} 참조에는 컴파일 시 {@code -parameters}가 필요하다 (Spring Boot Gradle 플러그인이
     * 기본으로 넣는다).
     */
    String key();

    /** 락 획득을 기다리는 시간. 넘기면 {@code LOCK_ACQUISITION_FAILED}로 거절한다. */
    long waitTime() default 5L;

    /**
     * 락 자동 해제 시간. 트랜잭션 최악 소요보다 길어야 한다 — 리스가 먼저 끝나면 다음 스레드가
     * 들어오고, 먼저 잡은 스레드의 해제는 건너뛰게 된다.
     * <p>
     * Redisson의 watchdog({@code leaseTime = -1})을 쓰지 않는 이유: 백그라운드 갱신 스레드가
     * 주기적으로 Redis 왕복을 추가해서 지연 측정에 변수가 섞인다.
     */
    long leaseTime() default 10L;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
