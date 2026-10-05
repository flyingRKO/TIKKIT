package com.tikkit.lockexperiment;

import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.reservation.dto.ReservationCreateRequest;
import com.tikkit.api.domain.reservation.dto.ReservationResponse;
import com.tikkit.api.domain.reservation.service.ReservationService;
import com.tikkit.api.security.MemberDetails;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모드별 선점 경로를 노출하는 실험용 엔드포인트 (Task 020 비교 실험 전용).
 * <p>
 * {@code ReservationController}를 건드리지 않고 별도 컨트롤러를 둔 이유는 운영 코드에 실험 분기를
 * 넣지 않기 위함이다. 대신 측정 경로에 위임이 한 단 끼므로, <b>기준선(PLAIN)도 이 컨트롤러를
 * 통과시켜</b> arm 간 비교를 공정하게 맞춘다. {@code docs/improvements/002-db-lock-comparison.md}의
 * 절대 수치와는 하네스가 달라서 직접 비교하면 안 된다.
 *
 * <h2>왜 패키지가 com.tikkit.api 밖인가</h2>
 * {@code @SpringBootApplication}이 {@code com.tikkit.api}에 있어서 컴포넌트 스캔이 그 하위
 * 클래스패스 전체를 훑고, 거기에 {@code src/test}도 포함된다. 이 컨트롤러를 {@code com.tikkit.api}
 * 아래 두면 <b>모든 테스트 컨텍스트</b>가 빈으로 올리려 하고, 의존성
 * ({@link DistributedLockedHoldFacade})은 {@code @TestConfiguration}에만 있으므로 실험과 무관한
 * 테스트들이 컨텍스트 로딩부터 실패한다.
 * <p>
 * {@code @RestController}를 떼고 {@code @RequestMapping}만 남기는 방법은 통하지 않는다 —
 * Spring Framework 6.2의 {@code RequestMappingHandlerMapping.isHandler()}는 타입에
 * {@code @Controller}가 있는지만 보고, {@code @RequestMapping}만 붙은 타입은 핸들러로 인식하지
 * 않는다. 매핑이 안 잡혀서 요청이 정적 리소스 핸들러로 떨어지고
 * {@code NoResourceFoundException}이 난다.
 * <p>
 * 그래서 스테레오타입은 그대로 두고 <b>패키지를 스캔 범위 밖으로</b> 옮겼다.
 * {@link LockExperimentConfig}가 {@code @Bean}으로 명시 등록한다.
 * <p>
 * 경로가 {@code /api/v1/**} 아래라 {@code SecurityConfig}의 {@code anyRequest().authenticated()}가
 * 그대로 적용되고, 예외는 {@code GlobalExceptionHandler}가 같은 포맷으로 변환한다.
 */
@RestController
@RequestMapping("/api/v1/experiment/reservations")
public class HoldExperimentController {

    /** 락 해제와 커밋 사이에 끼울 지연. 함정의 경쟁 창을 결정론적으로 벌린다. */
    private static final long COMMIT_DELAY_MILLIS = 2;

    private final ReservationService reservationService;
    private final DistributedLockedHoldFacade lockedFacade;
    private final InTransactionLockedHoldRunner inTransactionRunner;

    public HoldExperimentController(ReservationService reservationService,
                                    DistributedLockedHoldFacade lockedFacade,
                                    InTransactionLockedHoldRunner inTransactionRunner) {
        this.reservationService = reservationService;
        this.lockedFacade = lockedFacade;
        this.inTransactionRunner = inTransactionRunner;
    }

    @PostMapping("/{mode}")
    public ResponseEntity<ApiResponse<ReservationResponse>> hold(
            @AuthenticationPrincipal MemberDetails memberDetails,
            @PathVariable HoldMode mode,
            @RequestBody @Valid ReservationCreateRequest request) {

        Long memberId = memberDetails.getMemberId();
        ReservationResponse response = switch (mode) {
            case PLAIN -> reservationService.create(memberId, request);
            case LOCK_GRADE -> lockedFacade.holdWithGradeLock(memberId, request);
            case LOCK_MEMBER_GRADE -> lockedFacade.holdWithMemberGradeLock(memberId, request);
            case LOCK_GRADE_IN_TX -> inTransactionRunner.hold(
                    memberId, request, gradeKey(request), 0);
            case LOCK_MEMBER_GRADE_IN_TX -> inTransactionRunner.hold(
                    memberId, request, memberGradeKey(memberId, request), 0);
            case LOCK_MEMBER_GRADE_IN_TX_DELAYED -> inTransactionRunner.hold(
                    memberId, request, memberGradeKey(memberId, request), COMMIT_DELAY_MILLIS);
        };
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // 키 문자열은 DistributedLockedHoldFacade의 SpEL 식과 같은 모양으로 맞춘다 (같은 키를 두고 경쟁해야 한다).
    private String gradeKey(ReservationCreateRequest request) {
        return "grade:" + request.ticketGradeId();
    }

    private String memberGradeKey(Long memberId, ReservationCreateRequest request) {
        return "hold:" + memberId + ":" + request.ticketGradeId();
    }
}
