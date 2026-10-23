package com.tikkit.api.domain.queue.web;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.queue.service.QueueGateway;
import com.tikkit.api.security.MemberDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 좌석 조회에 입장권을 요구하는 인터셉터 (Task 031).
 *
 * <h2>왜 Security 필터가 아니라 인터셉터인가</h2>
 * 경로 변수 {@code {id}}를 꺼내야 한다. 인터셉터는 {@code HandlerMapping}이 끝난 뒤에 돌기 때문에
 * 파싱된 경로 변수를 요청 속성에서 바로 읽을 수 있고, {@code SecurityContext}도 이미 채워져 있다.
 * 필터에서 하면 URI를 정규식으로 쪼개야 하고 필터 순서도 직접 맞춰야 한다.
 * <p>
 * 그리고 인터셉터에서 던진 예외는 {@code DispatcherServlet}이
 * {@code processHandlerException}으로 보내므로 {@code GlobalExceptionHandler}가 처리한다 —
 * 응답이 다른 API와 같은 {@code ApiResponse} 포맷이 된다. 필터에서 던지면
 * {@code ApiAuthenticationEntryPoint}처럼 JSON을 직접 써야 한다.
 *
 * <h2>예매 선점은 여기서 막을 수 없다</h2>
 * {@code POST /api/v1/reservations}의 {@code scheduleId}는 <b>요청 본문</b>에 있다. 인터셉터가
 * 본문을 읽으면 {@code @RequestBody} 역직렬화가 빈 스트림을 만난다. 그래서 그쪽은
 * {@code ReservationService}가 가드를 직접 호출한다 — 로드맵의 "인터셉터 적용"이 경로에 회차 id가
 * 있는 쪽에만 성립한다.
 *
 * <h2>비로그인 요청을 여기서 401로 끊는다</h2>
 * {@code /schedules/*}/seats}는 {@code SecurityConfig}에서 {@code permitAll}이다 — Task 022가
 * "어떤 자리가 남았는지 보고 나서 가입하는 흐름이 정상"이라고 의도적으로 열었다. 대기열을 켠
 * 회차는 {@code memberId}로 줄을 세우므로 로그인이 필요하지만, {@code SecurityConfig}를
 * {@code authenticated()}로 올리면 <b>플래그가 꺼진 회차의 공개 조회까지 닫힌다.</b>
 * 그래서 보호는 여기서만 한다.
 */
public class QueuePassInterceptor implements HandlerInterceptor {

    private final QueueGateway queueGateway;

    public QueuePassInterceptor(QueueGateway queueGateway) {
        this.queueGateway = queueGateway;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long scheduleId = scheduleId(request);
        if (scheduleId == null) {
            // 경로 변수를 못 읽었다면 이 인터셉터가 걸릴 자리가 아니다. 보호를 "조용히 통과"로
            // 끝내지 않도록 패턴 등록과 이 추출은 QueueWebConfig에서 짝으로 관리한다.
            return true;
        }

        // 플래그를 인증보다 먼저 묻는다. 꺼진 회차는 비로그인도 그대로 봐야 하기 때문이다
        // (Task 022의 공개 조회). 순서를 뒤집으면 플래그와 무관하게 로그인 벽이 생긴다.
        if (!queueGateway.isQueueEnabled(scheduleId)) {
            return true;
        }

        Long memberId = currentMemberId();
        if (memberId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        queueGateway.requireAdmitted(scheduleId, memberId);
        return true;
    }

    /**
     * {@code SecurityContext}에서 회원 id를 꺼낸다. 비로그인이면 {@code null}.
     * <p>
     * {@code AnonymousAuthenticationToken}은 {@code isAuthenticated()}가 true라서 그것만으로는
     * 가를 수 없다. principal 타입으로 판단한다.
     */
    private static Long currentMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof MemberDetails details)) {
            return null;
        }
        return details.getMemberId();
    }

    @SuppressWarnings("unchecked")
    private static Long scheduleId(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?>)) {
            return null;
        }
        Object raw = ((Map<String, String>) attribute).get("id");
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            // 숫자가 아닌 id는 컨트롤러의 타입 변환이 400으로 끊는다. 여기서 500을 내지 않는다.
            return null;
        }
    }
}
