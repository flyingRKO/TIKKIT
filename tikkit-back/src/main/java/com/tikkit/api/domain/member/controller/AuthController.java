package com.tikkit.api.domain.member.controller;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.common.response.ApiResponse;
import com.tikkit.api.domain.member.dto.LoginRequest;
import com.tikkit.api.domain.member.dto.MemberResponse;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.domain.member.dto.SignupResponse;
import com.tikkit.api.domain.member.service.AuthService;
import com.tikkit.api.domain.member.service.MemberService;
import com.tikkit.api.security.MemberDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "회원가입·로그인")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final MemberService memberService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@RequestBody @Valid SignupRequest request) {
        return ApiResponse.success(authService.signup(request));
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ApiResponse<MemberResponse> login(@RequestBody @Valid LoginRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (BadCredentialsException e) {
            // 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분하지 않는다 — 계정 존재 여부를 노출하지 않기 위함.
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 세션 고정(session fixation) 공격 방지: 로그인 전부터 세션이 있었다면(예: 익명 상태에서 발급된 쿠키를
        // 공격자가 미리 심어두는 경우) 그 세션 ID를 새로 발급한다. 세션이 아직 없으면 changeSessionId()가
        // 예외를 던지므로(HttpServletRequest 스펙상 대상 세션이 있어야 함) 호출하지 않는다 — 이 경우 바로 아래
        // securityContextRepository.saveContext()가 처음부터 새 랜덤 ID로 세션을 만들어주므로 고정 위험이 없다.
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Spring Security 6부터 커스텀 로그인 엔드포인트는 SecurityContext를 세션에 명시적으로 저장해야 한다
        // (필터 체인의 자동 저장은 SecurityContextHolderFilter가 "요청 시작 시점"에만 세션→컨텍스트로 복원해줄 뿐,
        //  이 요청 안에서 새로 만든 컨텍스트를 세션에 써주지는 않는다).
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        MemberDetails memberDetails = (MemberDetails) authentication.getPrincipal();
        return ApiResponse.success(memberService.getMe(memberDetails.getMemberId()));
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, authentication);
        return ApiResponse.success();
    }
}
