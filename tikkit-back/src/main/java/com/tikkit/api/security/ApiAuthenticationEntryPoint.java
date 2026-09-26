package com.tikkit.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 미인증 상태로 보호된 API에 접근했을 때 GlobalExceptionHandler와 동일한 ApiResponse 포맷으로 401을 내려준다.
 * 시큐리티 필터 단계에서 막히는 요청은 @RestControllerAdvice(GlobalExceptionHandler)를 타지 않으므로 별도 처리한다.
 */
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(ErrorCode.UNAUTHORIZED.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.UNAUTHORIZED)));
    }
}
