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
import com.tikkit.api.security.login.LoginCredentialWriter;
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
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final LoginCredentialWriter loginCredentialWriter;

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

        // 인증 성공을 "다음 요청에도 알아볼 수 있는 형태"로 남긴다. 세션에 저장하는지 토큰을 쿠키로
        // 내리는지는 LoginCredentialWriter 구현이 정한다 (Task 026에서 전략을 비교하려고 뽑아냈다).
        loginCredentialWriter.write(authentication, httpRequest, httpResponse);

        MemberDetails memberDetails = (MemberDetails) authentication.getPrincipal();
        return ApiResponse.success(memberService.getMe(memberDetails.getMemberId()));
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        loginCredentialWriter.clear(authentication, httpRequest, httpResponse);
        return ApiResponse.success();
    }
}
