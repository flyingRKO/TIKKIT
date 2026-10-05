package com.tikkit.lockexperiment;

import com.tikkit.api.common.aop.DistributedLock;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.service.ReservationService;

/**
 * {@code @DistributedLock}을 <b>트랜잭션 밖</b>에서 걸고 선점 서비스를 호출한다
 * (Task 020 비교 실험 전용).
 * <p>
 * 이 클래스가 {@code ReservationService}와 별개 빈이어야 하는 이유: 같은 빈 안에서
 * {@code this.method()}로 부르면 프록시를 거치지 않아 Aspect가 아예 돌지 않는다. 자기 호출 함정이다.
 * <p>
 * 메서드에 {@code @Transactional}을 붙이지 않는다. 트랜잭션은 안쪽의
 * {@code ReservationService.create()}가 열고 닫으므로, 락은 그보다 바깥에서 잡히고
 * <b>커밋이 끝난 뒤에</b> 풀린다.
 */
public class DistributedLockedHoldFacade {

    private final ReservationService reservationService;

    // src/test에는 Lombok이 적용되지 않는다 (build.gradle에 testAnnotationProcessor 선언 없음).
    public DistributedLockedHoldFacade(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** 등급 하나를 두고 모든 요청이 줄 선다 (축 A). */
    @DistributedLock(key = "'grade:' + #request.ticketGradeId()", waitTime = 30)
    public ReservationResponse holdWithGradeLock(Long memberId, ReservationCreateRequest request) {
        return reservationService.create(memberId, request);
    }

    /** 같은 회원의 같은 등급 요청끼리만 줄 선다 (축 B). */
    @DistributedLock(key = "'hold:' + #memberId + ':' + #request.ticketGradeId()", waitTime = 30)
    public ReservationResponse holdWithMemberGradeLock(Long memberId, ReservationCreateRequest request) {
        return reservationService.create(memberId, request);
    }
}
