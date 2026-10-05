package com.tikkit.api.common.aop;

import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 락 키 SpEL 평가만 떼어내 검증한다 (Task 020). Redis도 스프링 컨텍스트도 필요 없다.
 * <p>
 * 두 가지 가정을 실제로 확인하는 것이 목적이다 — {@code #파라미터이름} 참조가 되려면 컴파일에
 * {@code -parameters}가 들어가야 하고, record 파라미터 접근에는 메서드 호출 문법이 필요하다.
 */
class DistributedLockAspectTest {

    // 키 평가 경로는 RedissonClient를 쓰지 않으므로 null로 둔다.
    private final DistributedLockAspect aspect = new DistributedLockAspect(null);

    @Test
    @DisplayName("파라미터 이름과 record 접근자로 락 키를 만든다")
    void resolveKey_파라미터_이름과_record_접근자() throws Exception {
        Method method = target();
        Object[] args = {7L, new ReservationCreateRequest(1L, 42L, 2)};

        String key = aspect.resolveKey("'hold:' + #memberId + ':' + #request.ticketGradeId()", method, args);

        assertThat(key).as("#파라미터이름이 해석됐다 = -parameters 플래그가 적용돼 있다").isEqualTo("hold:7:42");
    }

    @Test
    @DisplayName("record를 프로퍼티 문법으로 접근했을 때의 동작을 기록한다")
    void resolveKey_record_프로퍼티_문법() throws Exception {
        Method method = target();
        Object[] args = {7L, new ReservationCreateRequest(1L, 42L, 2)};

        // 프로퍼티 문법이 통하는지 여기서 확정한다. 통하면 애노테이션 javadoc의 주의 문구를 고쳐야 한다.
        String key = aspect.resolveKey("'hold:' + #request.ticketGradeId", method, args);

        assertThat(key).isEqualTo("hold:42");
    }

    private Method target() throws NoSuchMethodException {
        return Target.class.getDeclaredMethod("hold", Long.class, ReservationCreateRequest.class);
    }

    @SuppressWarnings("unused")
    private static class Target {
        void hold(Long memberId, ReservationCreateRequest request) {
        }
    }
}
